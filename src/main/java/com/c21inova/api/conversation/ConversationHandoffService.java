package com.c21inova.api.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.c21inova.api.advisor.Advisor;
import com.c21inova.api.advisor.AdvisorRepository;
import com.c21inova.api.advisor.AdvisorRounds;
import com.c21inova.api.assignment.AssignmentEngine;
import com.c21inova.api.assignment.AssignmentService;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.ManyChatService;
import com.c21inova.api.lead.AdvisorNotice;
import com.c21inova.api.lead.Lead;
import com.c21inova.api.lead.LeadRepository;
import com.c21inova.api.lead.LeadRules;
import com.c21inova.api.lead.LeadService;
import com.c21inova.api.property.PropertyCatalogService;

/**
 * Canalización comercial. La IA solo la SOLICITA: aquí se validan intención,
 * propiedad, asignación previa e idempotencia, y se reutiliza el pipeline de
 * leads (asesor propio / comodín / ruleta / restricciones / EasyBroker /
 * reintentos / aviso ManyChat) sin tocar sus reglas.
 */
@Service
public class ConversationHandoffService {

	private static final Logger log = LoggerFactory.getLogger(ConversationHandoffService.class);
	private static final String ASSISTANT_ORIGIN = "WhatsApp IA";

	private final ConversationRepository repo;
	private final LeadService leadService;
	private final LeadRepository leads;
	private final AdvisorRepository advisors;
	private final AssignmentService assignmentService;
	private final PropertyCatalogService catalog;
	private final ManyChatService manyChat;
	private final AuditService audit;
	private final Env env;

	public ConversationHandoffService(ConversationRepository repo, LeadService leadService, LeadRepository leads, AdvisorRepository advisors,
			AssignmentService assignmentService, PropertyCatalogService catalog, ManyChatService manyChat, AuditService audit, Env env) {
		this.repo = repo;
		this.leadService = leadService;
		this.leads = leads;
		this.advisors = advisors;
		this.assignmentService = assignmentService;
		this.catalog = catalog;
		this.manyChat = manyChat;
		this.audit = audit;
		this.env = env;
	}

	static String advisorDisplayName(String name) {
		if (name == null) return null;
		String value = name.replaceAll("\\s+", " ").trim();
		return value.isEmpty() ? null : value;
	}

	public String conversationPanelUrl(String conversationId) {
		String base = env.panelUrl();
		return base.isEmpty() ? null : base + "/conversaciones/" + conversationId;
	}

	/** Campos que abren la espera tras una canalización (contada desde ahora). reason: commercial | human | management. */
	public static Map<String, Object> waitFields(AssistantSettingsService.Settings settings, String reason, Instant now) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("reopenAt", Session.computeReopenAt(now, settings.handoffReopenMinutes()));
		fields.put("reopenReason", reason);
		fields.put("waitNoticeAt", null);
		return fields;
	}

	public record EscalationRef(String id, boolean created) {
	}

	/** Una sola escalación abierta por tipo y conversación: evita spam de pendientes. */
	public EscalationRef recordEscalation(String conversationId, String companyId, String type, String reason) {
		String text = reason.length() > 1000 ? reason.substring(0, 1000) : reason;
		String open = repo.jdbc().sql("""
				SELECT id FROM conversation_escalations WHERE "conversationId" = :conv AND type = CAST(:type AS "EscalationType") AND status <> 'RESOLVED'
				LIMIT 1
				""").param("conv", conversationId).param("type", type).query(String.class).optional().orElse(null);
		Instant now = Instant.now();
		if (open != null) {
			repo.jdbc().sql("UPDATE conversation_escalations SET reason = :reason, \"updatedAt\" = :now WHERE id = :id").param("reason", text)
					.param("now", Db.ts(now)).param("id", open).update();
			return new EscalationRef(open, false);
		}
		String id = Ids.cuid();
		repo.jdbc().sql("""
				INSERT INTO conversation_escalations (id, "companyId", "conversationId", type, status, reason, "createdAt", "updatedAt")
				VALUES (:id, :c, :conv, CAST(:type AS "EscalationType"), 'PENDING', :reason, :now, :now)
				""").param("id", id).param("c", companyId).param("conv", conversationId).param("type", type).param("reason", text).param("now", Db.ts(now))
				.update();
		return new EscalationRef(id, true);
	}

	public EscalationRef recordEscalation(Conversation conversation, String type, String reason) {
		return recordEscalation(conversation.id(), conversation.companyId(), type, reason);
	}

	private static final Pattern SIMULATED_ADVISOR = Pattern.compile("tocaría a (.+?) por ");

	/** Estado real de la canalización para decírselo al cliente (nombre del asesor desde la base, no de la IA). */
	public Session.AssignmentFacts loadAssignmentFacts(String leadId, String handoffState, String handoffReason, boolean isTest) {
		if ("NO_ADVISOR".equals(handoffState)) return new Session.AssignmentFacts("no_advisor", null, false);
		if (!"ASSIGNED".equals(handoffState) && !"EXISTING_LEAD".equals(handoffState)) return null;
		if (leadId == null) {
			// Conversación de prueba: no hay lead; se usa el asesor que tocaría (simulación).
			if (!isTest || handoffReason == null) return null;
			Matcher matcher = SIMULATED_ADVISOR.matcher(handoffReason);
			return matcher.find() ? new Session.AssignmentFacts("assigned", matcher.group(1), false) : null;
		}
		Map<String, Object> row = repo.jdbc().sql("""
				SELECT a.name AS name, (SELECT la."manyChatNotified" FROM lead_assignments la WHERE la."leadId" = l.id ORDER BY la."assignedAt" DESC LIMIT 1) AS notified
				FROM leads l LEFT JOIN advisors a ON a.id = l."assignedAdvisorId" WHERE l.id = :id
				""").param("id", leadId).query((rs, i) -> Json.obj("name", rs.getString("name"), "notified", rs.getBoolean("notified"))).optional().orElse(null);
		String advisorName = advisorDisplayName(row == null ? null : (String) row.get("name"));
		if (advisorName == null) return new Session.AssignmentFacts("no_advisor", null, false);
		return new Session.AssignmentFacts("EXISTING_LEAD".equals(handoffState) ? "existing" : "assigned", advisorName,
				Boolean.TRUE.equals(row.get("notified")));
	}

	public Session.AssignmentFacts loadAssignmentFacts(Conversation conversation) {
		return loadAssignmentFacts(conversation.leadId(), conversation.handoffState(), conversation.handoffReason(), conversation.isTest());
	}

	static String describeBudget(Map<String, Object> facts) {
		String min = Policy.knownFact(facts, "budget_min");
		String max = Policy.knownFact(facts, "budget_max");
		String currencyValue = Policy.knownFact(facts, "currency");
		String currency = currencyValue != null ? " " + currencyValue : "";
		if (min != null && max != null) return min + " – " + max + currency;
		if (max != null) return "hasta " + max + currency;
		if (min != null) return "desde " + min + currency;
		return null;
	}

	/** Propiedades verificadas que el cliente mencionó (para el aviso al asesor). */
	static List<AdvisorNotice.OtherProperty> mentionedProperties(Conversation conversation) {
		List<AdvisorNotice.OtherProperty> out = new ArrayList<>();
		for (Map<String, Object> p : Json.objects(conversation.properties())) {
			if (!(p.get("publicId") instanceof String publicId) || Boolean.FALSE.equals(p.get("verified"))) continue;
			out.add(new AdvisorNotice.OtherProperty(publicId, Json.str(p, "title"), Json.str(p, "url")));
		}
		return out;
	}

	private record DryRun(String advisorName, String method) {
	}

	/** Qué asesor tocaría, SIN escribir nada (conversaciones de prueba). Misma regla que el motor real. */
	private DryRun dryRunAssignment(String companyId, String interesCliente, String agentEmail) {
		if (agentEmail != null && !agentEmail.toLowerCase().equals(env.easyBrokerFallbackAgentEmail())) {
			Advisor direct = advisors.findByEasyBrokerEmail(companyId, agentEmail).orElse(null);
			if (direct != null) return new DryRun(direct.name(), "DIRECT_PROPERTY_ADVISOR");
		}
		String route = LeadRules.classifyInterest(interesCliente).assignmentRoute();
		AssignmentService.SimulationCandidates sim = assignmentService.rotationCandidatesForSimulation(companyId, route, Instant.now());
		Advisor picked = AssignmentEngine.pickWeightedLeastAssigned(
				AdvisorRounds.applyRounds(sim.candidates(), Advisor::id, Advisor::dailyLimit, sim.todayCounts(), sim.onRound()).pool(), sim.todayCounts());
		return picked != null ? new DryRun(picked.name(), "WEIGHTED_ROTATION") : null;
	}

	/** Idempotencia por sesión: reintentos del mismo turno no crean otro lead; una sesión nueva sí puede. */
	static String handoffRequestId(Conversation conversation) {
		return conversation.sessionStartSeq() > 1 ? "conv:" + conversation.id() + ":s" + conversation.sessionStartSeq() + ":handoff"
				: "conv:" + conversation.id() + ":handoff";
	}

	private Map<String, Object> handoffUpdate(String leadId, String state, Instant now, String reason, Map<String, Object> wait) {
		Map<String, Object> fields = new LinkedHashMap<>();
		if (leadId != null) fields.put("leadId", leadId);
		fields.put("handoffState", state);
		fields.put("handoffAt", now);
		if (reason != null) fields.put("handoffReason", reason.length() > 1000 ? reason.substring(0, 1000) : reason);
		fields.putAll(wait);
		fields.put("assignmentNoticeAt", null);
		return fields;
	}

	private static Map<String, Object> noAdvisorResult(boolean pendingRecorded) {
		return Json.obj("status", "no_advisor_available", "pending_recorded", pendingRecorded, "customer_message",
				Session.buildAssignmentConfirmation(new Session.AssignmentFacts("no_advisor", null, false)));
	}

	/**
	 * Resultado real de la canalización (status: assigned | already_assigned |
	 * existing_lead | no_advisor_available | rejected | error). advisor_name y
	 * customer_message los arma el backend con lo que ocurrió.
	 * trigger: assistant | human_request | abandonment.
	 */
	public Map<String, Object> requestCommercialHandoff(Conversation conversation, AssistantSettingsService.Settings settings, String primaryIntent,
			List<String> secondaryIntents, Map<String, Object> facts, String summary, String reason, String propertyPublicId, String trigger) {
		Instant now = Instant.now();

		// 1. Ya canalizado en ESTA sesión: no se reasigna en silencio.
		if (("ASSIGNED".equals(conversation.handoffState()) || "EXISTING_LEAD".equals(conversation.handoffState()))
				&& conversation.handoffInCurrentSession()) {
			Session.AssignmentFacts assignment = loadAssignmentFacts(conversation);
			EscalationRef followUp = recordEscalation(conversation, "FOLLOW_UP", "El cliente ya canalizado vuelve a solicitar atención: " + reason
					+ (propertyPublicId != null ? " (propiedad " + propertyPublicId + ")" : ""));
			return Json.obj("status", "already_assigned", "advisor_name", assignment != null ? assignment.advisorName() : null, "follow_up_recorded",
					followUp.id() != null, "customer_message", assignment != null
							? Session.buildAssignmentConfirmation(new Session.AssignmentFacts("existing", assignment.advisorName(), assignment.advisorNotified()))
							: null);
		}

		// 2. Propiedad: solo se acepta un código verificado contra EasyBroker.
		String propertyId = null;
		String agentEmail = null;
		if (propertyPublicId != null) {
			PropertyCatalogService.Lookup lookup = catalog.lookup(propertyPublicId);
			if (lookup.ok()) {
				propertyId = lookup.property().publicId();
				agentEmail = lookup.property().agentEmail();
			} else if ("easybroker_unavailable".equals(lookup.error())) {
				// Como el flujo anterior: el motor reintenta y, si sigue caído, cae a ruleta.
				propertyId = propertyPublicId.toUpperCase();
			}
		}

		Policy.HandoffDecision decision = Policy.decideCommercialHandoff(primaryIntent, secondaryIntents, propertyId, trigger,
				conversation.campaignRef() != null && propertyId != null && conversation.campaignRef().equals(propertyId));
		if (!decision.allowed()) return Json.obj("status", "rejected", "reason", decision.reason());

		List<String> additionalNeeds = secondaryIntents.stream().filter(i -> !i.equals(primaryIntent)).map(Policy.INTENT_LABELS::get).toList();
		String operation = Policy.knownFact(facts, "operation");
		if (operation == null && Policy.isRealEstateIntent(primaryIntent)) operation = Policy.INTENT_LABELS.get(primaryIntent);
		String zone = Policy.knownFact(facts, "zone");
		if (zone == null) zone = Policy.knownFact(facts, "own_property_location");
		AdvisorNotice.ClientContext context = new AdvisorNotice.ClientContext(reason, operation, zone, describeBudget(facts), additionalNeeds, summary,
				mentionedProperties(conversation));
		Map<String, Object> wait = waitFields(settings, "commercial", now);

		// 3. Conversación de prueba: a quién tocaría, sin crear lead ni mover la ruleta ni avisar.
		if (conversation.isTest()) {
			DryRun dryRun = dryRunAssignment(conversation.companyId(), decision.interesCliente(), agentEmail);
			String simulatedReason = ("SIMULACIÓN (" + decision.interesCliente() + (propertyId != null ? " " + propertyId : "") + "): "
					+ (dryRun != null ? "tocaría a " + dryRun.advisorName() + " por " + dryRun.method() : "sin asesores disponibles") + ". " + reason);
			repo.update(conversation.id(), handoffUpdate(null, dryRun != null ? "ASSIGNED" : "NO_ADVISOR", now, simulatedReason, wait));
			if (dryRun == null) return noAdvisorResult(false);
			String name = advisorDisplayName(dryRun.advisorName());
			return Json.obj("status", "assigned", "simulated", true, "advisor_name", name, "assignment_recorded", false, "advisor_notified", false,
					"crm_synced", false, "customer_message", Session.buildAssignmentConfirmation(new Session.AssignmentFacts("assigned", name, false)));
		}

		String phone = conversation.phone();
		if (phone == null) {
			recordEscalation(conversation, "HUMAN", "No se pudo canalizar: ManyChat no envió el teléfono del contacto.");
			return Json.obj("status", "error", "retryable", false);
		}

		// 4. El teléfono ya tiene un lead reciente: se vincula como seguimiento para su mismo asesor, sin reasignar.
		Map<String, Object> recent = leads.findRecentForPhone(conversation.companyId(), phone, now.minusSeconds(settings.existingLeadWindowDays() * 86_400L))
				.orElse(null);
		if (recent != null) {
			String recentId = (String) recent.get("id");
			repo.update(conversation.id(), handoffUpdate(recentId, "EXISTING_LEAD", now, reason, wait));
			EscalationRef followUp = recordEscalation(conversation, "FOLLOW_UP", "Cliente con lead existente (" + recentId + ") trae una solicitud: " + reason
					+ (propertyId != null ? " (propiedad " + propertyId + ")" : ""));
			audit.log(conversation.companyId(), recentId, null, "LEAD_DUPLICATE_SKIPPED", "skipped",
					"El asistente encontró un lead reciente del mismo teléfono; se vinculó la conversación sin reasignar.",
					Json.obj("conversationId", conversation.id(), "propertyId", propertyId));
			String name = advisorDisplayName((String) recent.get("advisorName"));
			return Json.obj("status", "existing_lead", "advisor_name", name, "follow_up_recorded", followUp.created(), "customer_message",
					Session.buildAssignmentConfirmation(name != null ? new Session.AssignmentFacts("existing", name, false)
							: new Session.AssignmentFacts("no_advisor", null, false)));
		}

		// 5. Lead nuevo por el motor existente.
		String requestId = handoffRequestId(conversation);
		try {
			String origin = conversation.campaignRef() != null ? ASSISTANT_ORIGIN + " · Campaña " + conversation.campaignRef() : ASSISTANT_ORIGIN;
			String leadName = conversation.name() != null && !conversation.name().trim().isEmpty() ? conversation.name().trim() : "Sin nombre (WhatsApp)";
			LeadService.Result result = leadService.processIncomingLead(conversation.companyId(),
					new LeadService.IncomingLead(leadName, phone, decision.interesCliente(), decision.datosPropiedad(), origin,
							conversation.manyChatSubscriberId(), requestId, null, null, context),
					"assistant_conversation", requestId, null, 24);
			if (!result.selectionOk()) {
				repo.update(conversation.id(), handoffUpdate(result.leadId(), "NO_ADVISOR", now, reason, wait));
				recordEscalation(conversation, "HUMAN", "Sin asesores disponibles para canalizar al cliente.");
				return noAdvisorResult(true);
			}
			repo.update(conversation.id(), handoffUpdate(result.leadId(), "ASSIGNED", now, reason, wait));
			String name = advisorDisplayName(result.advisorName());
			return Json.obj("status", "assigned", "simulated", "shadow".equals(result.mode()), "advisor_name", name, "assignment_recorded", true,
					"advisor_notified", result.manyChatNotified(), "crm_synced", result.easyBrokerConfirmed(), "customer_message",
					Session.buildAssignmentConfirmation(new Session.AssignmentFacts("assigned", name, result.manyChatNotified())));
		} catch (LeadService.DuplicatePhoneLeadException duplicate) {
			Lead existing = duplicate.existing;
			String advisorName = existing.assignedAdvisorId() == null ? null
					: advisors.findById(existing.assignedAdvisorId()).map(Advisor::name).orElse(null);
			repo.update(conversation.id(), handoffUpdate(existing.id(), "EXISTING_LEAD", now, reason, wait));
			String name = advisorDisplayName(advisorName);
			return Json.obj("status", "existing_lead", "advisor_name", name, "follow_up_recorded", false, "customer_message",
					Session.buildAssignmentConfirmation(name != null ? new Session.AssignmentFacts("existing", name, false)
							: new Session.AssignmentFacts("no_advisor", null, false)));
		} catch (DuplicateKeyException uniqueViolation) {
			Lead lead = leads.findByRequestId(requestId).orElse(null);
			if (lead != null) {
				String state = lead.assignedAdvisorId() != null ? "ASSIGNED" : "NO_ADVISOR";
				Map<String, Object> fields = new LinkedHashMap<>();
				fields.put("leadId", lead.id());
				fields.put("handoffState", state);
				fields.put("handoffAt", now);
				fields.putAll(wait);
				repo.update(conversation.id(), fields);
				Session.AssignmentFacts assignment = loadAssignmentFacts(lead.id(), state, null, false);
				return Json.obj("status", "already_assigned", "advisor_name", assignment != null ? assignment.advisorName() : null, "follow_up_recorded", false,
						"customer_message", assignment != null ? Session.buildAssignmentConfirmation(assignment) : null);
			}
			return failed(conversation, uniqueViolation);
		} catch (RuntimeException error) {
			return failed(conversation, error);
		}
	}

	private Map<String, Object> failed(Conversation conversation, RuntimeException error) {
		log.error("[ASSISTANT] handoff comercial falló", error);
		String message = ("Error al canalizar: " + (error.getMessage() != null ? error.getMessage() : "desconocido"));
		repo.update(conversation.id(), Json.obj("handoffState", "FAILED", "handoffReason", message.length() > 1000 ? message.substring(0, 1000) : message));
		recordEscalation(conversation, "PROCESSING_ERROR", "Falló la canalización comercial automática; revisar y asignar manualmente.");
		return Json.obj("status", "error", "retryable", false);
	}

	/**
	 * Avisa a gerencia con el mismo flujo de ManyChat de avisos. Sin
	 * destinatarios, o en shadow/prueba, el pendiente queda SOLO en el panel
	 * y se reporta como no notificado.
	 */
	private boolean notifyManagement(Conversation conversation, AssistantSettingsService.Settings settings, String title, String detail) {
		if (conversation.isTest() || !env.isLiveAutomation()) return false;
		if (settings.managementSubscriberIds().isEmpty() || env.manyChatAdvisorFlowId() == null) return false;
		String cleanPhone = conversation.phone() == null ? "" : conversation.phone().replaceAll("\\D", "");
		String related = String.join("\n", java.util.stream.Stream.of(detail, conversationPanelUrl(conversation.id())).filter(s -> s != null && !s.isEmpty())
				.toList());
		List<AdvisorNotice.Field> fields = List.of(
				new AdvisorNotice.Field(AdvisorNotice.FIELD_NAME, conversation.name() != null ? conversation.name() : "Sin nombre"),
				new AdvisorNotice.Field(AdvisorNotice.FIELD_PHONE, conversation.phone() != null ? conversation.phone() : "Sin teléfono"),
				new AdvisorNotice.Field(AdvisorNotice.FIELD_REQUEST_TYPE, title),
				new AdvisorNotice.Field(AdvisorNotice.FIELD_REFERENCE, "Gerencia"),
				new AdvisorNotice.Field(AdvisorNotice.FIELD_RELATED_INFO, related.length() > 900 ? related.substring(0, 900) : related),
				new AdvisorNotice.Field(AdvisorNotice.FIELD_CONTACT_URL, cleanPhone.isEmpty() ? "" : "https://wa.me/" + cleanPhone));
		boolean anySent = false;
		for (String subscriberId : settings.managementSubscriberIds()) {
			try {
				manyChat.notifyAdvisor(subscriberId, null, fields);
				anySent = true;
			} catch (RuntimeException error) {
				log.error("[ASSISTANT] aviso a gerencia falló {}", subscriberId, error);
			}
		}
		return anySent;
	}

	private void markNotified(String escalationId) {
		Instant now = Instant.now();
		repo.jdbc().sql("UPDATE conversation_escalations SET status = 'NOTIFIED', \"notifiedAt\" = :now, \"updatedAt\" = :now WHERE id = :id")
				.param("now", Db.ts(now)).param("id", escalationId).update();
	}

	public Map<String, Object> requestManagement(Conversation conversation, AssistantSettingsService.Settings settings, String category, String reason,
			String summary) {
		String text = "[" + category + "] " + reason;
		EscalationRef escalation = recordEscalation(conversation, "MANAGEMENT", text);
		boolean notified = escalation.created()
				&& notifyManagement(conversation, settings, "Gerencia: " + category, reason + "\n" + (summary == null ? "" : summary));
		if (notified) markNotified(escalation.id());
		Instant now = Instant.now();
		Map<String, Object> fields = new LinkedHashMap<>();
		if ("NONE".equals(conversation.handoffState())) {
			fields.put("handoffState", "NOT_APPLICABLE");
			fields.put("handoffReason", text.length() > 1000 ? text.substring(0, 1000) : text);
			fields.put("handoffAt", now);
		}
		fields.putAll(waitFields(settings, "management", now));
		repo.update(conversation.id(), fields);
		return Json.obj("recorded", true, "notified", notified);
	}

	/**
	 * El cliente pide una persona: pendiente HUMAN en el panel, aviso a gerencia
	 * si está configurado y la espera automática. No detiene la IA para siempre.
	 */
	public Map<String, Object> requestHuman(Conversation conversation, AssistantSettingsService.Settings settings, String reason, String summary) {
		EscalationRef escalation = recordEscalation(conversation, "HUMAN", reason);
		boolean notified = escalation.created()
				&& notifyManagement(conversation, settings, "Solicita atención humana", reason + "\n" + (summary == null ? "" : summary));
		if (notified) markNotified(escalation.id());
		repo.update(conversation.id(), waitFields(settings, "human", Instant.now()));
		return Json.obj("recorded", true, "notified", notified);
	}
}

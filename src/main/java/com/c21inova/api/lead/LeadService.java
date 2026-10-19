package com.c21inova.api.lead;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.advisor.Advisor;
import com.c21inova.api.advisor.AdvisorRepository;
import com.c21inova.api.assignment.AssignmentRepository;
import com.c21inova.api.assignment.AssignmentService;
import com.c21inova.api.assignment.LeadAssignment;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.Phone;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.EasyBrokerClient.EasyBrokerApiException;
import com.c21inova.api.integration.EasyBrokerService;
import com.c21inova.api.integration.ManyChatErrors.ManyChatApiException;
import com.c21inova.api.integration.ManyChatService;
import com.c21inova.api.integration.RetryService;
import com.c21inova.api.lead.AdvisorNotice.ClientContext;
import com.c21inova.api.lead.AdvisorNotice.Field;
import com.c21inova.api.lead.AdvisorNotice.NoticeProperty;
import com.c21inova.api.property.PropertyInquiryService;

/**
 * Pipeline único detrás del webhook de leads de ManyChat, del asistente y
 * del simulador. AUTOMATION_MODE controla toda escritura externa: en
 * "shadow" el Lead y la asignación son reales pero EasyBroker/ManyChat
 * nunca se llaman (solo se reportan como "would_*").
 */
@Service
public class LeadService {

	private static final String MANYCHAT_SOURCE = "WhatsApp ManyChat";

	/** Datos del lead tal como llegan (nombres del webhook de ManyChat). */
	public record IncomingLead(String nombre, String telefonoCliente, String interesCliente, String datosPropiedad, String origen, String subscriberId,
			String requestId, String tituloPropiedad, String urlPropiedad, ClientContext conversation) {
	}

	/** Ya existe un lead reciente del mismo teléfono: no se creó, asignó ni envió nada. */
	public static class DuplicatePhoneLeadException extends RuntimeException {
		public final Lead existing;

		public DuplicatePhoneLeadException(Lead existing) {
			super("Ya existe un lead reciente (" + existing.id() + ") para el teléfono " + existing.phone() + ".");
			this.existing = existing;
		}
	}

	public record Result(String mode, String leadId, String routeLabel, String interestType, Map<String, Object> property, boolean selectionOk,
			String advisorId, String advisorName, String method, Integer weight, String reason, List<AssignmentService.Candidate> candidates,
			List<String> actions, String contactRequestId, String contactId, boolean easyBrokerConfirmed, boolean manyChatNotified) {

		/** Misma forma JSON que devolvía el panel (ProcessLeadResult). */
		public Map<String, Object> toJson() {
			Map<String, Object> selection = selectionOk
					? Json.obj("ok", true, "advisorId", advisorId, "advisorName", advisorName, "method", method, "weight", weight, "reason", reason,
							"candidatesConsidered", candidates)
					: Json.obj("ok", false, "error", "NO_ADVISORS_AVAILABLE", "candidatesConsidered", candidates);
			return Json.obj(
					"mode", mode,
					"duplicate", false,
					"leadId", leadId,
					"routeLabel", routeLabel,
					"interestType", interestType,
					"property", property,
					"selection", selection,
					"actions", actions,
					"easyBroker", Json.obj("contactRequestId", contactRequestId, "contactId", contactId, "confirmed", easyBrokerConfirmed),
					"manyChat", Json.obj("notified", manyChatNotified));
		}
	}

	private record Resolved(LeadAssignment assignment, Advisor advisor, List<AssignmentService.Candidate> candidates) {
	}

	private final Env env;
	private final LeadRepository leads;
	private final AdvisorRepository advisors;
	private final AssignmentService assignmentService;
	private final AssignmentRepository assignmentRepository;
	private final EasyBrokerService easyBroker;
	private final ManyChatService manyChat;
	private final RetryService retry;
	private final AuditService audit;
	private final PropertyInquiryService inquiries;
	private final JdbcClient jdbc;

	public LeadService(Env env, LeadRepository leads, AdvisorRepository advisors, AssignmentService assignmentService,
			AssignmentRepository assignmentRepository, EasyBrokerService easyBroker, ManyChatService manyChat, RetryService retry, AuditService audit,
			PropertyInquiryService inquiries, JdbcClient jdbc) {
		this.env = env;
		this.leads = leads;
		this.advisors = advisors;
		this.assignmentService = assignmentService;
		this.assignmentRepository = assignmentRepository;
		this.easyBroker = easyBroker;
		this.manyChat = manyChat;
		this.retry = retry;
		this.audit = audit;
		this.inquiries = inquiries;
		this.jdbc = jdbc;
	}

	// --- Aviso al asesor ------------------------------------------------------------

	private static List<String> conversationLines(ClientContext context) {
		List<String> lines = new ArrayList<>();
		lines.add("Motivo: " + context.reason());
		if (notEmpty(context.operation())) lines.add("Operación: " + context.operation());
		if (notEmpty(context.zone())) lines.add("Zona: " + context.zone());
		if (notEmpty(context.budget())) lines.add("Presupuesto: " + context.budget());
		if (context.additionalNeeds() != null && !context.additionalNeeds().isEmpty()) lines.add("Otras necesidades: " + String.join(", ", context.additionalNeeds()));
		if (notEmpty(context.summary())) lines.add("Resumen: " + context.summary());
		return lines;
	}

	private static boolean notEmpty(String value) {
		return value != null && !value.isEmpty();
	}

	public static List<Field> buildAdvisorLeadFields(String name, String phone, String interest, String routeLabel, String propertyPublicId,
			NoticeProperty property, String propertyData, ClientContext conversation) {
		String cleanPhone = phone.replaceAll("\\D", "");
		String reference = notEmpty(propertyPublicId) ? propertyPublicId : routeLabel;
		String trimmedName = name.trim();
		String requestType = interest != null && !interest.trim().isEmpty() ? interest.trim() : routeLabel;
		String contactUrl = "https://wa.me/" + cleanPhone;
		ClientContext client = conversation == null ? null
				: new ClientContext(conversation.reason(), conversation.operation(), conversation.zone(), conversation.budget(),
						conversation.additionalNeeds(), conversation.summary(),
						conversation.otherProperties() == null ? null
								: conversation.otherProperties().stream()
										.filter(p -> property == null || !p.publicId().equals(property.publicId())).toList());
		String relatedInfo = AdvisorNotice.build(property, propertyData, client,
				AdvisorNotice.budget(List.of(trimmedName, phone, requestType, reference, contactUrl)));
		return List.of(
				new Field(AdvisorNotice.FIELD_NAME, trimmedName),
				new Field(AdvisorNotice.FIELD_PHONE, phone),
				new Field(AdvisorNotice.FIELD_REQUEST_TYPE, requestType),
				new Field(AdvisorNotice.FIELD_REFERENCE, reference),
				new Field(AdvisorNotice.FIELD_RELATED_INFO, relatedInfo),
				new Field(AdvisorNotice.FIELD_CONTACT_URL, contactUrl));
	}

	/** Ficha desde el índice local cuando EasyBroker no respondió o en reasignaciones. */
	public NoticeProperty propertyNoticeFromCache(String companyId, String publicId) {
		try {
			return jdbc.sql("""
					SELECT "publicId", title, "propertyType", location, "publicUrl", operations::text AS operations, bedrooms, bathrooms
					FROM property_cache WHERE "companyId" = :companyId AND "publicId" = :publicId
					""").param("companyId", companyId).param("publicId", publicId).query((rs, i) -> {
				List<AdvisorNotice.Operation> operations = new ArrayList<>();
				for (Map<String, Object> op : Json.objects(Json.parse(rs.getString("operations")))) {
					operations.add(new AdvisorNotice.Operation(Json.str(op, "type"), Json.str(op, "formatted_amount"), Json.number(op, "amount")));
				}
				Integer bedrooms = com.c21inova.api.common.Db.integer(rs, "bedrooms");
				return new NoticeProperty(rs.getString("publicId"), rs.getString("title"), rs.getString("propertyType"), rs.getString("location"),
						rs.getString("publicUrl"), operations, bedrooms == null ? null : bedrooms.doubleValue(),
						com.c21inova.api.common.Db.dbl(rs, "bathrooms"), null, null, null, null, null, null, null);
			}).optional().orElse(null);
		} catch (RuntimeException error) {
			return null;
		}
	}

	// --- Pipeline ---------------------------------------------------------------------

	private Resolved resolvePropertyAdvisor(String companyId, String leadId, EasyBrokerService.Property property, String route, String method) {
		String agentEmail = property.agentEmail() == null ? null : property.agentEmail().trim().toLowerCase(Locale.ROOT);
		if (notEmpty(agentEmail) && !agentEmail.equals(env.easyBrokerFallbackAgentEmail())) {
			Advisor advisor = advisors.findByEasyBrokerEmail(companyId, agentEmail).orElse(null);
			if (advisor != null) {
				LeadAssignment assignment = assignmentService.assignDirect(leadId, advisor.id(), advisor.weight(), method,
						"Asesor propio de la propiedad " + property.publicId() + " (" + agentEmail + ").");
				audit.log(companyId, leadId, advisor.id(), "ADVISOR_DIRECT_MATCH", "ok",
						"Asignación directa a " + advisor.name() + " por ser agente EasyBroker de la propiedad.");
				return new Resolved(assignment, advisor, List.of());
			}
			audit.log(companyId, leadId, null, "ADVISOR_DIRECT_MATCH", "warning", "La propiedad " + property.publicId() + " tiene agente EasyBroker \""
					+ agentEmail + "\" sin Advisor correspondiente en la base de datos. Cae a ruleta ponderada.");
		}
		AssignmentService.Selection result = assignmentService.selectAndAssign(companyId, leadId, route, "WEIGHTED_ROTATION", null);
		return result.ok() ? new Resolved(result.assignment(), result.advisor(), result.candidatesConsidered())
				: new Resolved(null, null, result.candidatesConsidered());
	}

	private static Map<String, Object> propertySummary(EasyBrokerService.Property property) {
		return property == null ? null : Json.obj("publicId", property.publicId(), "title", property.title(), "agentEmail", property.agentEmail());
	}

	/**
	 * @param automationMode null = según AUTOMATION_MODE; "shadow" lo fuerza (simulador).
	 * @param dedupeByPhoneHours si se indica, lanza DuplicatePhoneLeadException cuando el teléfono ya tiene lead en la ventana.
	 */
	public Result processIncomingLead(String companyId, IncomingLead input, String source, String requestId, String automationMode,
			Integer dedupeByPhoneHours) {
		String mode = automationMode != null ? automationMode : env.automationMode();
		String normalized = Phone.normalizeE164(input.telefonoCliente());
		String phone = !normalized.isEmpty() ? normalized : input.telefonoCliente().trim();
		LeadRules.Interest interest = LeadRules.classifyInterest(input.interesCliente());

		LeadRepository.NewLead data = new LeadRepository.NewLead(companyId, input.nombre().trim(), phone, interest.interestType(),
				notEmpty(input.datosPropiedad()) ? input.datosPropiedad() : null, notEmpty(input.origen()) ? input.origen() : null, interest.routeLabel(),
				"PROCESSING", source, notEmpty(input.subscriberId()) ? input.subscriberId() : null, "PENDING", requestId, rawPayload(input));

		Lead lead;
		if (dedupeByPhoneHours != null) {
			Instant since = Instant.now().minusSeconds(dedupeByPhoneHours * 3600L);
			LeadRepository.Claim claim = leads.createUnlessRecentForPhone(data, since);
			if (claim instanceof LeadRepository.Existing existing) throw new DuplicatePhoneLeadException(existing.existing());
			lead = ((LeadRepository.Created) claim).lead();
		} else {
			lead = leads.create(data);
		}

		audit.log(companyId, lead.id(), null, "LEAD_RECEIVED", "ok", "Lead recibido: " + interest.routeLabel() + " (" + source + ").",
				Json.obj("interestType", interest.interestType(), "origin", input.origen()));

		List<String> actions = new ArrayList<>();
		EasyBrokerService.Property property = null;
		String propertyPublicId = null;
		if ("PROPERTY".equals(interest.assignmentRoute())) {
			propertyPublicId = input.datosPropiedad() == null ? null : input.datosPropiedad().trim();
			if (propertyPublicId != null && propertyPublicId.isEmpty()) propertyPublicId = null;
		} else if ("CAMPAIGN".equals(interest.assignmentRoute())) {
			propertyPublicId = LeadRules.extractCampaignPropertyCode(input.datosPropiedad());
		}

		if (propertyPublicId != null) {
			try {
				property = easyBroker.getProperty(propertyPublicId);
				audit.log(companyId, lead.id(), null, "PROPERTY_FETCHED", "ok", "Propiedad " + propertyPublicId + " obtenida de EasyBroker.");
				// Consulta por propiedad para los reportes (el asistente registra las suyas con más contexto).
				if (!"assistant_conversation".equals(source) && !"testing_ui".equals(source)) {
					inquiries.recordSafe(new PropertyInquiryService.Inquiry(companyId, property.publicId(), phone, null, lead.id(), "lead_property_code",
							property.publicId(), null, null, null, 1, "lead_webhook", false, null));
				}
			} catch (RuntimeException error) {
				audit.log(companyId, lead.id(), null, "PROPERTY_FETCH_FAILED", "error", error.getMessage() != null ? error.getMessage() : "Error desconocido");
			}
		}

		Resolved resolved;
		if (property != null) {
			resolved = resolvePropertyAdvisor(companyId, lead.id(), property, interest.assignmentRoute(),
					"CAMPAIGN".equals(interest.assignmentRoute()) ? "CAMPAIGN_DIRECT" : "DIRECT_PROPERTY_ADVISOR");
		} else {
			AssignmentService.Selection rotation = assignmentService.selectAndAssign(companyId, lead.id(), interest.assignmentRoute(), "WEIGHTED_ROTATION",
					null);
			resolved = rotation.ok() ? new Resolved(rotation.assignment(), rotation.advisor(), rotation.candidatesConsidered())
					: new Resolved(null, null, rotation.candidatesConsidered());
			if (rotation.ok()) {
				audit.log(companyId, lead.id(), rotation.advisor().id(), "ADVISOR_ROTATION_SELECTED", "ok",
						rotation.advisor().name() + " seleccionado por ruleta ponderada.", Json.obj("candidatesConsidered", rotation.candidatesConsidered()));
			}
		}

		LeadAssignment assignment = resolved.assignment();
		Advisor advisor = resolved.advisor();
		if (advisor == null || assignment == null) {
			audit.log(companyId, lead.id(), null, "ADVISOR_SELECTION_FAILED", "error", "Sin asesores disponibles para la ruta \"" + interest.routeLabel() + "\".",
					Json.obj("candidatesConsidered", resolved.candidates()));
			leads.update(lead.id(), Map.of("status", "FAILED", "assignmentStatus", "FAILED"));
			return new Result(mode, lead.id(), interest.routeLabel(), interest.interestType(), propertySummary(property), false, null, null, null, null,
					null, resolved.candidates(), List.of(), null, null, false, false);
		}

		Map<String, Object> assigned = new LinkedHashMap<>();
		assigned.put("assignedAdvisorId", advisor.id());
		assigned.put("assignmentStatus", "ASSIGNED");
		assigned.put("status", "ASSIGNED");
		leads.update(lead.id(), assigned);

		if ("shadow".equals(mode)) {
			actions.addAll(List.of("would_create_easybroker_contact", "would_assign_advisor", "would_notify_manychat"));
			audit.log(companyId, lead.id(), advisor.id(), "AUTOMATION_SIMULATED", "skipped",
					"Simulación interna: no se envió información real a EasyBroker ni a ManyChat.");
			return new Result(mode, lead.id(), interest.routeLabel(), interest.interestType(), propertySummary(property), true, advisor.id(), advisor.name(),
					assignment.method(), assignment.weightAtAssignment(), assignment.reason() == null ? "" : assignment.reason(), resolved.candidates(),
					actions, null, null, false, false);
		}

		// --- live: EasyBroker + ManyChat reales ---
		// POST /contact_requests solo responde {"status":"successful"} (nunca un id): el
		// contacto se localiza después por teléfono + origen + propiedad.
		String baseMessage = property != null
				? "Lead recibido desde WhatsApp. El cliente vio la propiedad " + property.publicId() + ".\n\nDato enviado: "
						+ (input.datosPropiedad() == null ? "" : input.datosPropiedad()) + "\nInterés: " + input.interesCliente() + "\nOrigen: " + MANYCHAT_SOURCE
				: "Lead recibido desde WhatsApp. Interés: " + input.interesCliente() + ". Ref: " + requestId;
		String contactRequestMessage = input.conversation() != null
				? truncate(baseMessage + "\n\n" + String.join("\n", conversationLines(input.conversation())), 2000)
				: baseMessage;
		String propertyId = property != null ? property.publicId() : null;

		boolean contactRequestCreated = false;
		String contactId = null;
		boolean confirmed = false;
		try {
			easyBroker.createContactRequest(input.nombre(), phone, contactRequestMessage, MANYCHAT_SOURCE, propertyId);
			contactRequestCreated = true;
			actions.add("created_easybroker_contact");
			leads.update(lead.id(), "status", "CREATED_IN_EASYBROKER");
			audit.log(companyId, lead.id(), advisor.id(), "EASYBROKER_CONTACT_REQUEST_CREATED", "ok", "contact_request creado en EasyBroker.");
		} catch (RuntimeException error) {
			actions.add("easybroker_contact_failed");
			audit.log(companyId, lead.id(), advisor.id(), "EASYBROKER_CONTACT_REQUEST_FAILED", "error",
					error instanceof EasyBrokerApiException ? error.getMessage() : error.getMessage() != null ? error.getMessage() : "Error desconocido");
			Map<String, Object> payload = Json.obj("name", input.nombre(), "phone", phone, "message", contactRequestMessage, "source", MANYCHAT_SOURCE);
			if (propertyId != null) payload.put("propertyId", propertyId);
			if (notEmpty(advisor.easyBrokerEmail())) {
				payload.put("assign", Json.obj("advisorEmail", advisor.easyBrokerEmail(), "advisorId", advisor.id(), "assignmentId", assignment.id()));
			}
			retry.enqueueEasyBrokerCreate(companyId, lead.id(), payload);
		}

		if (contactRequestCreated) {
			EasyBrokerService.ContactRequest found = easyBroker.findRecentContactRequest(phone, MANYCHAT_SOURCE, propertyId);
			if (found != null && found.contactId() != null) {
				contactId = found.contactId();
				leads.update(lead.id(), "easyBrokerContactId", contactId);
				audit.log(companyId, lead.id(), advisor.id(), "EASYBROKER_CONTACT_FOUND", "ok", "Contacto " + contactId + " localizado en EasyBroker.");
				try {
					easyBroker.assignContactToAdvisor(contactId, advisor.easyBrokerEmail() == null ? "" : advisor.easyBrokerEmail());
					confirmed = true;
					actions.add("assigned_advisor_in_easybroker");
					audit.log(companyId, lead.id(), advisor.id(), "EASYBROKER_CONTACT_ASSIGNED", "ok",
							"Asesor " + advisor.name() + " confirmado en EasyBroker para el contacto " + contactId + ".");
				} catch (RuntimeException error) {
					actions.add("easybroker_assignment_failed");
					audit.log(companyId, lead.id(), advisor.id(), "EASYBROKER_ASSIGNMENT_FAILED", "error",
							error.getMessage() != null ? error.getMessage() : "Error desconocido");
					if (notEmpty(advisor.easyBrokerEmail())) {
						Map<String, Object> payload = Json.obj("contactId", contactId, "phone", phone, "source", MANYCHAT_SOURCE);
						if (propertyId != null) payload.put("propertyId", propertyId);
						payload.put("advisorEmail", advisor.easyBrokerEmail());
						payload.put("advisorId", advisor.id());
						payload.put("assignmentId", assignment.id());
						retry.enqueueEasyBrokerAssign(companyId, lead.id(), payload);
					}
				}
			} else {
				actions.add("easybroker_contact_not_found");
				audit.log(companyId, lead.id(), advisor.id(), "EASYBROKER_CONTACT_NOT_FOUND", "warning",
						"EasyBroker no expuso el contact_request a tiempo; se reintentará en segundo plano en vez de perder la confirmación.");
				if (notEmpty(advisor.easyBrokerEmail())) {
					Map<String, Object> payload = Json.obj("phone", phone, "source", MANYCHAT_SOURCE);
					if (propertyId != null) payload.put("propertyId", propertyId);
					payload.put("advisorEmail", advisor.easyBrokerEmail());
					payload.put("advisorId", advisor.id());
					payload.put("assignmentId", assignment.id());
					retry.enqueueEasyBrokerAssign(companyId, lead.id(), payload);
				}
			}
		}

		assignmentRepository.updateStatus(assignment.id(), confirmed ? "CONFIRMED" : "ASSIGNED", confirmed, null);

		boolean notified = false;
		if (notEmpty(advisor.manyChatSubscriberId())) {
			NoticeProperty noticeProperty = property != null ? property.toNotice()
					: propertyPublicId != null ? propertyNoticeFromCache(companyId, propertyPublicId) : null;
			List<Field> fields = buildAdvisorLeadFields(input.nombre(), phone, input.interesCliente(), interest.routeLabel(), propertyPublicId,
					noticeProperty, input.datosPropiedad(), input.conversation());
			try {
				// CRÍTICO: el flujo del asesor lee Custom Fields de su contacto; se escriben con ESTE lead antes del flujo.
				manyChat.notifyAdvisor(advisor.manyChatSubscriberId(), null, fields);
				notified = true;
				actions.add("notified_manychat");
				audit.log(companyId, lead.id(), advisor.id(), "MANYCHAT_NOTIFICATION_SENT", "ok", "Asesor " + advisor.name() + " notificado por ManyChat.");
			} catch (RuntimeException error) {
				actions.add("manychat_notification_failed");
				audit.log(companyId, lead.id(), advisor.id(), "MANYCHAT_NOTIFICATION_FAILED", "error",
						error instanceof ManyChatApiException ? error.getMessage() : error.getMessage() != null ? error.getMessage() : "Error desconocido");
				// La asignación ya quedó: solo este paso queda pendiente, con su clasificación real.
				Map<String, Object> payload = Json.obj(
						"subscriberId", advisor.manyChatSubscriberId(),
						"flowNs", env.manyChatAdvisorFlowId() == null ? "" : env.manyChatAdvisorFlowId(),
						"fields", fields.stream().map(f -> Json.obj("fieldId", f.fieldId(), "value", f.value())).toList(),
						"advisorId", advisor.id(),
						"assignmentId", assignment.id());
				retry.enqueueManyChatFlow(companyId, lead.id(), payload, error);
			}
		} else {
			audit.log(companyId, lead.id(), advisor.id(), "MANYCHAT_NOTIFICATION_FAILED", "skipped",
					advisor.name() + " no tiene manyChatSubscriberId configurado; no se pudo notificar.");
		}

		assignmentRepository.updateStatus(assignment.id(), null, null, notified);
		leads.update(lead.id(), "status", notified ? "COMPLETED" : "NOTIFIED");
		audit.log(companyId, lead.id(), advisor.id(), "LEAD_COMPLETED", "ok",
				"Pipeline terminado. EasyBroker confirmado=" + confirmed + ", ManyChat notificado=" + notified + ".");

		return new Result(mode, lead.id(), interest.routeLabel(), interest.interestType(), propertySummary(property), true, advisor.id(), advisor.name(),
				assignment.method(), assignment.weightAtAssignment(), assignment.reason() == null ? "" : assignment.reason(), resolved.candidates(), actions,
				null, contactId, confirmed, notified);
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

	/** rawPayload con los mismos nombres de campo que guardaba el panel. */
	private static Map<String, Object> rawPayload(IncomingLead input) {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("nombre", input.nombre());
		raw.put("telefonoCliente", input.telefonoCliente());
		raw.put("interesCliente", input.interesCliente());
		if (input.datosPropiedad() != null) raw.put("datosPropiedad", input.datosPropiedad());
		if (input.origen() != null) raw.put("origen", input.origen());
		if (input.subscriberId() != null) raw.put("subscriberId", input.subscriberId());
		if (input.requestId() != null) raw.put("requestId", input.requestId());
		if (input.tituloPropiedad() != null) raw.put("tituloPropiedad", input.tituloPropiedad());
		if (input.urlPropiedad() != null) raw.put("urlPropiedad", input.urlPropiedad());
		if (input.conversation() != null) raw.put("conversation", input.conversation());
		return raw;
	}
}

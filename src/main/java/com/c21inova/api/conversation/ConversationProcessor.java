package com.c21inova.api.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.c21inova.api.common.Brand;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.Retry;
import com.c21inova.api.integration.ManyChatErrors.ManyChatApiException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatTimeoutException;
import com.c21inova.api.integration.ManyChatService;
import com.c21inova.api.property.PropertyInquiryService;

/**
 * Procesamiento persistente de conversaciones: un solo procesador por
 * conversación (lease), turnos con control de versión, aviso fijo durante la
 * espera tras canalizar y una bandeja de salida ordenada con estados honestos.
 */
@Service
public class ConversationProcessor {

	private static final Logger log = LoggerFactory.getLogger(ConversationProcessor.class);

	/** Reintentos del turno completo si OpenAI/infra falla; luego respuesta fija + pendiente humano. */
	private static final int[] TURN_RETRY_DELAYS_S = { 10, 30, 90 };
	/** Reintentos de envío ante error explícito de ManyChat (nunca ante timeout ambiguo). */
	private static final int MAX_SEND_ATTEMPTS = 3;
	/** Ventana de servicio de WhatsApp: fuera de ella el texto libre no es válido. */
	private static final long WHATSAPP_WINDOW_MS = 24L * 60 * 60 * 1000 - 5L * 60 * 1000;

	private static final String FALLBACK_REPLY = "Gracias por tu mensaje. En este momento no pude procesarlo automáticamente; ya quedó registrado para que una persona del equipo de "
			+ Brand.BRAND_NAME + " te atienda.";

	private final ConversationRepository repo;
	private final ConversationService conversations;
	private final AssistantSettingsService settingsService;
	private final ConversationAgentService agent;
	private final ConversationHandoffService handoffs;
	private final PropertyInquiryService inquiries;
	private final ManyChatService manyChat;
	private final TransactionTemplate tx;

	public ConversationProcessor(ConversationRepository repo, ConversationService conversations, AssistantSettingsService settingsService,
			ConversationAgentService agent, ConversationHandoffService handoffs, PropertyInquiryService inquiries, ManyChatService manyChat,
			TransactionTemplate tx) {
		this.repo = repo;
		this.conversations = conversations;
		this.settingsService = settingsService;
		this.agent = agent;
		this.handoffs = handoffs;
		this.inquiries = inquiries;
		this.manyChat = manyChat;
		this.tx = tx;
	}

	private static String newOwner() {
		return "p_" + UUID.randomUUID();
	}

	private List<ConversationAgentService.HistoryMessage> loadHistory(Conversation conversation) {
		return repo.recentHistory(conversation.id(), 60).stream()
				.map(m -> new ConversationAgentService.HistoryMessage(m.role(), m.text(), m.seq() < conversation.sessionStartSeq(),
						m.seq() > conversation.processedSeq()))
				.toList();
	}

	private void clearBurstIfUnchanged(Conversation conversation, Map<String, Object> extra) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("processAfter", null);
		fields.put("burstStartedAt", null);
		fields.putAll(extra);
		repo.updateWhere("conversations", "id = :id AND \"lastSeq\" = :lastSeq", Map.of("id", conversation.id(), "lastSeq", conversation.lastSeq()), fields);
	}

	/**
	 * Procesa una conversación hasta dejarla al día o agotar `budgetMs`. Seguro en
	 * paralelo: solo quien obtiene el lease trabaja; los demás esperan o lo dejan al cron.
	 * Resultado: processed | idle | busy | waiting | not_handled | failed.
	 */
	public String process(String conversationId, long budgetMs) {
		long deadline = System.currentTimeMillis() + budgetMs;
		String owner = newOwner();
		while (!conversations.tryClaimLease(conversationId, owner)) {
			if (System.currentTimeMillis() + 1500 > deadline) return "busy";
			Retry.sleep(1500);
		}

		String outcome = "idle";
		try {
			for (int iteration = 0; iteration < 6; iteration++) {
				Conversation conversation = repo.findById(conversationId).orElse(null);
				if (conversation == null) return "idle";
				conversations.tryClaimLease(conversationId, owner); // renueva

				if (conversation.processedSeq() >= conversation.lastSeq()) {
					clearBurstIfUnchanged(conversation, Map.of());
					break;
				}

				AssistantSettingsService.Settings settings = settingsService.get(conversation.companyId());
				if (!"AI".equals(conversation.control())
						|| (!conversation.isSimulator() && !settingsService.handles(settings, conversation.manyChatSubscriberId()))) {
					// Otra persona/el flujo anterior atiende: no se responde ni se reintenta.
					clearBurstIfUnchanged(conversation, "AI".equals(conversation.control()) ? Map.of("processedSeq", conversation.lastSeq()) : Map.of());
					outcome = "not_handled";
					break;
				}

				Instant now = Instant.now();
				if (!Session.isBurstReady(conversation.processAfter(), now)) {
					long waitMs = conversation.processAfter().toEpochMilli() - now.toEpochMilli();
					if (System.currentTimeMillis() + waitMs + 20_000 > deadline) {
						outcome = "waiting"; // lo retoma otra invocación o el cron
						break;
					}
					Retry.sleep(Math.min(waitMs + 50, 30_000));
					continue;
				}

				// Espera tras canalizar: aviso fijo. Vencida, los pendientes abren sesión nueva.
				if (conversation.reopenAt() != null) {
					if (Session.isWaiting(conversation.reopenAt(), now)) {
						runWaitNotice(conversation);
						outcome = "processed";
					} else {
						openSessionForPending(conversation.id(), now);
					}
					continue;
				}

				String result = runTurn(conversation);
				outcome = "failed".equals(result) ? "failed" : "processed";
				if ("failed".equals(result)) break;
				// "superseded": llegó otro mensaje durante el turno; se repite con todo junto.
			}
			flushOutbox(conversationId);
		} finally {
			conversations.releaseLease(conversationId, owner);
		}

		// Cierre de carrera: si llegó un mensaje justo al soltar el lease y su invocación ya se rindió, se toma aquí.
		Conversation after = repo.findById(conversationId).orElse(null);
		if (after != null && "AI".equals(after.control()) && after.consecutiveFailures() == 0 && !"failed".equals(outcome)
				&& after.processedSeq() < after.lastSeq() && after.leaseUntil() == null && after.processAfter() != null
				&& System.currentTimeMillis() + 25_000 < deadline) {
			return process(conversationId, deadline - System.currentTimeMillis());
		}
		return outcome;
	}

	@SuppressWarnings("unchecked")
	private String runTurn(Conversation conversation) {
		AssistantSettingsService.Settings settings = settingsService.get(conversation.companyId());
		int targetSeq = conversation.lastSeq();
		int fromSeq = conversation.processedSeq() + 1;
		long startedAt = System.currentTimeMillis();
		String turnId = repo.insertTurn(conversation.id(), fromSeq, targetSeq);

		List<ConversationAgentService.HistoryMessage> history = loadHistory(conversation);
		int previousReplies = repo.jdbc().sql("""
				SELECT count(*)::int FROM conversation_messages WHERE "conversationId" = :c AND role = 'ASSISTANT' AND status NOT IN ('FAILED', 'CANCELLED')
				""").param("c", conversation.id()).query(Integer.class).single();

		ConversationAgentService.TurnResult result;
		try {
			result = agent.runTurn(conversation, settings, history, previousReplies == 0);
		} catch (RuntimeException error) {
			String message = error.getMessage() != null ? error.getMessage() : "Error desconocido";
			log.error("[ASSISTANT] turno falló {} {}", conversation.id(), message);
			handleTurnFailure(conversation, turnId, message, System.currentTimeMillis() - startedAt);
			return "failed";
		}

		AssistantContract.FinalOutput output = result.finalOutput();
		String primary = output.primaryIntent();
		List<String> secondary = new ArrayList<>(new LinkedHashSet<>(output.secondaryIntents()));
		secondary.removeIf(i -> i.equals(primary) || "UNKNOWN".equals(i));
		int clarificationCount = output.askedClarification() && !output.madeProgress() ? conversation.clarificationCount() + 1
				: output.madeProgress() ? 0 : conversation.clarificationCount();
		List<Map<String, Object>> properties = mergeProperties(conversation.properties(), result.verifiedProperties());
		String replyText = output.reply().trim();

		Map<String, Object> stateUpdate = new LinkedHashMap<>();
		stateUpdate.put("primaryIntent", primary);
		stateUpdate.put("secondaryIntents", secondary);
		stateUpdate.put("facts", result.facts());
		stateUpdate.put("properties", properties);
		stateUpdate.put("missingData", Policy.computeMissingFacts(primary, result.facts()));
		String summary = output.summary().length() > 1200 ? output.summary().substring(0, 1200) : output.summary();
		stateUpdate.put("summary", !summary.isEmpty() ? summary : conversation.summary());
		stateUpdate.put("clarificationCount", clarificationCount);
		stateUpdate.put("consecutiveFailures", 0);
		stateUpdate.put("lastError", null);
		stateUpdate.put("lastActivityAt", Instant.now());

		Map<String, Object> turnData = new LinkedHashMap<>();
		turnData.put("model", result.model());
		turnData.put("inputTokens", result.inputTokens());
		turnData.put("outputTokens", result.outputTokens());
		turnData.put("toolCalls", Json.obj("tools", result.toolTrace(), "corrections", result.corrections(), "promptVersion", Prompt.PROMPT_VERSION));
		turnData.put("durationMs", (int) (System.currentTimeMillis() - startedAt));

		// El cliente debe saber a quién quedó asignada su solicitud, una sola vez por canalización.
		Conversation afterTools = repo.require(conversation.id());
		boolean assignmentNotice = false;
		if (afterTools.assignmentNoticeAt() == null && afterTools.handoffInCurrentSession() && ("ASSIGNED".equals(afterTools.handoffState())
				|| "EXISTING_LEAD".equals(afterTools.handoffState()) || "NO_ADVISOR".equals(afterTools.handoffState()))) {
			Session.AssignmentFacts facts = handoffs.loadAssignmentFacts(afterTools);
			if (facts != null) {
				if (replyText.isEmpty()) replyText = Session.buildAssignmentConfirmation(facts);
				else if (facts.advisorName() != null && !Session.mentionsAdvisor(replyText, facts.advisorName())) {
					replyText = replyText + "\n\n" + Session.buildAssignmentConfirmation(facts);
				}
				assignmentNotice = true;
			}
		}
		String reply = replyText;
		boolean notice = assignmentNotice;

		// Control de versión: solo se responde si no llegó nada nuevo desde que empezó el turno.
		Boolean committed = tx.execute(status -> {
			Conversation current = repo.lock(conversation.id());
			if (current.lastSeq() != targetSeq) {
				repo.update(conversation.id(), stateUpdate);
				Map<String, Object> superseded = new LinkedHashMap<>(turnData);
				superseded.put("status", "SUPERSEDED");
				repo.updateTurn(turnId, superseded);
				return false;
			}
			int seq = current.lastSeq();
			if (!reply.isEmpty()) {
				seq++;
				repo.insertMessage(conversation.id(), conversation.companyId(), seq, "ASSISTANT", reply, conversation.isSimulator() ? "SIMULATED" : "QUEUED", null,
						turnId, null);
			}
			Map<String, Object> fields = new LinkedHashMap<>(stateUpdate);
			fields.put("lastSeq", seq);
			fields.put("processedSeq", seq);
			fields.put("processAfter", null);
			fields.put("burstStartedAt", null);
			if (notice && !reply.isEmpty()) fields.put("assignmentNoticeAt", Instant.now());
			repo.update(conversation.id(), fields);
			Map<String, Object> completed = new LinkedHashMap<>(turnData);
			completed.put("status", "COMPLETED");
			repo.updateTurn(turnId, completed);
			return true;
		});

		recordTurnInquiries(afterTools, result, fromSeq, targetSeq);
		if (Boolean.TRUE.equals(committed) && result.handoff() == null) recordPostHandoffFollowUp(conversation, primary, secondary, output.summary());
		return Boolean.TRUE.equals(committed) ? "completed" : "superseded";
	}

	/** Contacto ↔ propiedad consultada, solo con propiedades identificadas con certeza o confirmadas. */
	private void recordTurnInquiries(Conversation conversation, ConversationAgentService.TurnResult result, int fromSeq, int toSeq) {
		Set<String> ids = new LinkedHashSet<>();
		for (String id : result.finalOutput().propertyIds()) {
			String upper = id.toUpperCase(Locale.ROOT);
			if (result.identified().containsKey(upper)) ids.add(upper);
		}
		if (result.handoffPropertyId() != null) ids.add(result.handoffPropertyId());
		if (ids.isEmpty()) return;
		int messageCount = repo.jdbc().sql("""
				SELECT count(*)::int FROM conversation_messages WHERE "conversationId" = :c AND role = 'USER' AND seq >= :from AND seq <= :to
				""").param("c", conversation.id()).param("from", fromSeq).param("to", toSeq).query(Integer.class).single();
		String heardFrom = Policy.knownFact(result.facts(), "heard_from");
		for (String publicId : ids) {
			ConversationAgentService.Identified identified = result.identified().get(publicId);
			inquiries.recordSafe(new PropertyInquiryService.Inquiry(conversation.companyId(), publicId,
					PropertyInquiryService.contactKeyFor(conversation.phone(), conversation.manyChatSubscriberId()), conversation.id(), conversation.leadId(),
					identified != null ? identified.method() : "handoff", identified != null && identified.evidence() != null ? identified.evidence() : publicId,
					identified != null ? identified.linkPortal() : null, heardFrom, null, Math.max(1, messageCount), "assistant", conversation.isTest(), null));
		}
	}

	/** Mensajes que llegaron durante la espera pero se procesan ya vencida: abren la sesión nueva desde el primero pendiente. */
	private void openSessionForPending(String conversationId, Instant now) {
		tx.executeWithoutResult(status -> {
			Conversation current = repo.lock(conversationId);
			if (current.reopenAt() == null || Session.isWaiting(current.reopenAt(), now)) return;
			repo.update(conversationId, conversations.sessionOpeningData(current, current.processedSeq() + 1, now));
		});
	}

	/**
	 * Aviso fijo durante la espera tras canalizar (nombre real del asesor y tiempo
	 * restante), máximo uno cada 5 minutos. No reasigna ni prolonga la espera; lo
	 * útil que agregue el cliente queda como pendiente de seguimiento.
	 */
	private void runWaitNotice(Conversation conversation) {
		Instant now = Instant.now();
		int targetSeq = conversation.lastSeq();
		int fromSeq = conversation.processedSeq() + 1;
		List<String> pending = repo.jdbc().sql("""
				SELECT text FROM conversation_messages WHERE "conversationId" = :c AND role = 'USER' AND seq >= :from AND seq <= :to ORDER BY seq ASC
				""").param("c", conversation.id()).param("from", fromSeq).param("to", targetSeq).query(String.class).list();
		List<String> useful = pending.stream().filter(text -> !Session.isLowSignalMessage(text)).toList();
		boolean savedForTeam = false;
		if (!useful.isEmpty()) {
			String text = "Mensaje del cliente durante la espera tras canalizar: " + String.join(" / ", useful);
			handoffs.recordEscalation(conversation, "FOLLOW_UP", text.length() > 1000 ? text.substring(0, 1000) : text);
			savedForTeam = true;
		}
		boolean send = Session.shouldSendWaitNotice(now, conversation.waitNoticeAt());
		String reason = conversation.reopenReason() != null ? conversation.reopenReason() : "commercial";
		Session.AssignmentFacts assignment = "commercial".equals(reason) ? handoffs.loadAssignmentFacts(conversation) : null;
		String text = send ? Session.buildWaitNotice(reason, assignment, Session.remainingWaitMinutes(conversation.reopenAt(), now), savedForTeam) : null;

		tx.executeWithoutResult(status -> {
			Conversation current = repo.lock(conversation.id());
			if (current.lastSeq() != targetSeq) return;
			int seq = current.lastSeq();
			if (text != null) {
				seq++;
				repo.insertMessage(conversation.id(), conversation.companyId(), seq, "ASSISTANT", text, conversation.isSimulator() ? "SIMULATED" : "QUEUED", null,
						null, Json.obj("waitNotice", true, "reopenAt", com.c21inova.api.common.Texts.iso(current.reopenAt())));
			}
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("lastSeq", seq);
			fields.put("processedSeq", seq);
			fields.put("processAfter", null);
			fields.put("burstStartedAt", null);
			fields.put("lastActivityAt", now);
			if (text != null) fields.put("waitNoticeAt", now);
			if (text != null && assignment != null && assignment.advisorName() != null && current.assignmentNoticeAt() == null) {
				fields.put("assignmentNoticeAt", now);
			}
			repo.update(conversation.id(), fields);
		});
	}

	/**
	 * Tras canalizar NO se reasigna ni se crea otro lead: una necesidad nueva o
	 * un reclamo de seguimiento queda como pendiente FOLLOW_UP en el panel.
	 */
	private void recordPostHandoffFollowUp(Conversation before, String primary, List<String> secondary, String summary) {
		if (!"ASSIGNED".equals(before.handoffState()) && !"EXISTING_LEAD".equals(before.handoffState())) return;
		Map<String, Object> previous = before.previousContextMap();
		Set<String> known = new LinkedHashSet<>();
		known.add(before.primaryIntent());
		known.addAll(before.secondaryIntents());
		String previousPrimary = Json.str(previous, "primaryIntent");
		known.add(previousPrimary != null ? previousPrimary : "UNKNOWN");
		for (Object intent : Json.asList(previous == null ? null : previous.get("secondaryIntents")) == null ? List.of()
				: Json.asList(previous.get("secondaryIntents"))) {
			known.add(String.valueOf(intent));
		}
		List<String> all = new ArrayList<>();
		all.add(primary);
		all.addAll(secondary);
		List<String> added = all.stream().filter(i -> !"UNKNOWN".equals(i) && !"HUMAN_REQUEST".equals(i) && !known.contains(i)).toList();
		if (!added.isEmpty()) {
			handoffs.recordEscalation(before, "FOLLOW_UP", "Nueva necesidad tras la canalización: "
					+ String.join(", ", added.stream().map(Policy.INTENT_LABELS::get).toList()) + ". " + summary);
		} else if ("HUMAN_REQUEST".equals(primary)) {
			handoffs.recordEscalation(before, "FOLLOW_UP", "El cliente ya canalizado pide atención o seguimiento. " + summary);
		}
	}

	static List<Map<String, Object>> mergeProperties(Object stored, List<Prompt.VerifiedProperty> verified) {
		Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
		for (Map<String, Object> property : Json.objects(stored)) {
			if (property.get("publicId") instanceof String id) byId.put(id, property);
		}
		for (Prompt.VerifiedProperty property : verified) {
			byId.put(property.publicId(), Json.obj("publicId", property.publicId(), "title", property.title(), "url", property.url()));
		}
		List<Map<String, Object>> list = new ArrayList<>(byId.values());
		return list.size() > 10 ? new ArrayList<>(list.subList(list.size() - 10, list.size())) : list;
	}

	private void handleTurnFailure(Conversation conversation, String turnId, String message, long durationMs) {
		int failures = conversation.consecutiveFailures() + 1;
		String error = message.length() > 1000 ? message.substring(0, 1000) : message;
		repo.updateTurn(turnId, Json.obj("status", "FAILED", "error", error, "durationMs", (int) durationMs));

		if (failures <= TURN_RETRY_DELAYS_S.length) {
			// Mensajes conservados; se reintenta el turno completo más tarde.
			repo.update(conversation.id(), Json.obj("consecutiveFailures", failures, "lastError", error, "processAfter",
					Instant.now().plusSeconds(TURN_RETRY_DELAYS_S[failures - 1])));
			return;
		}

		// Agotado: respuesta fija una sola vez, pendiente visible y la IA se detiene hasta que alguien la reanude.
		tx.executeWithoutResult(status -> {
			Conversation current = repo.lock(conversation.id());
			int seq = current.lastSeq() + 1;
			repo.insertMessage(conversation.id(), conversation.companyId(), seq, "ASSISTANT", FALLBACK_REPLY, conversation.isSimulator() ? "SIMULATED" : "QUEUED",
					null, turnId, Json.obj("fallback", true));
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("lastSeq", seq);
			fields.put("processedSeq", seq);
			fields.put("processAfter", null);
			fields.put("burstStartedAt", null);
			fields.put("consecutiveFailures", failures);
			fields.put("lastError", error);
			fields.put("control", "HUMAN");
			fields.put("controlReason", "La IA falló varias veces seguidas; requiere atención humana.");
			fields.put("controlChangedAt", Instant.now());
			fields.put("controlChangedBy", "system");
			repo.update(conversation.id(), fields);
		});
		handoffs.recordEscalation(conversation, "PROCESSING_ERROR", "El asistente no pudo responder tras " + failures + " intentos: " + message);
	}

	// --- Outbox: envíos ordenados por conversación, con estados honestos. -----------------------

	private record Queued(String id, String text, int attempts) {
	}

	public void flushOutbox(String conversationId) {
		List<Queued> queued = repo.jdbc().sql("""
				SELECT id, text, attempts FROM conversation_messages WHERE "conversationId" = :c AND status = 'QUEUED' ORDER BY seq ASC
				""").param("c", conversationId).query((rs, i) -> new Queued(rs.getString("id"), rs.getString("text"), rs.getInt("attempts"))).list();
		if (queued.isEmpty()) return;
		Conversation conversation = repo.require(conversationId);
		for (Queued message : queued) {
			// Un mensaje en espera de reintento bloquea los siguientes: el orden importa.
			if (!sendOne(conversation, message)) break;
		}
	}

	private void updateMessage(String id, Map<String, Object> fields) {
		repo.updateWhere("conversation_messages", "id = :id", Map.of("id", id), fields);
	}

	/** true si quedó en estado final (enviado, fallido o incierto) y se puede seguir con el siguiente. */
	private boolean sendOne(Conversation conversation, Queued message) {
		if (conversation.isSimulator()) {
			updateMessage(message.id(), Json.obj("status", "SIMULATED"));
			return true;
		}
		if (conversation.lastInboundAt() == null || System.currentTimeMillis() - conversation.lastInboundAt().toEpochMilli() > WHATSAPP_WINDOW_MS) {
			updateMessage(message.id(), Json.obj("status", "FAILED", "lastError", "Fuera de la ventana de 24 h de WhatsApp: requiere plantilla aprobada."));
			return true;
		}
		// Reclamo atómico QUEUED → SENDING: si otro proceso lo tomó, no se duplica.
		int claimed = repo.updateWhere("conversation_messages", "id = :id AND status = 'QUEUED'", Map.of("id", message.id()),
				Json.obj("status", "SENDING", "attempts", new ConversationRepository.Increment(1)));
		if (claimed == 0) return false;

		try {
			manyChat.sendWhatsAppText(conversation.manyChatSubscriberId(), message.text());
			Instant sentAt = Instant.now();
			updateMessage(message.id(), Json.obj("status", "SENT", "sentAt", sentAt, "lastError", null));
			repo.update(conversation.id(), Json.obj("lastOutboundAt", sentAt));
			return true;
		} catch (ManyChatTimeoutException timeout) {
			// Ambiguo: ManyChat pudo haberlo entregado. No se reintenta solo.
			updateMessage(message.id(),
					Json.obj("status", "UNCERTAIN", "lastError", "Timeout de ManyChat: el mensaje pudo haberse entregado. Revisar antes de reenviar."));
			return true;
		} catch (RuntimeException error) {
			int attempts = message.attempts() + 1;
			String detail = error instanceof ManyChatApiException api
					? api.getMessage() + " " + truncate(Json.write(api.body == null ? "null" : api.body), 300)
					: error.getMessage() != null ? error.getMessage() : "Error desconocido";
			boolean finalFailure = attempts >= MAX_SEND_ATTEMPTS
					|| (error instanceof ManyChatApiException api && api.status >= 400 && api.status < 500 && api.status != 429);
			updateMessage(message.id(), Json.obj("status", finalFailure ? "FAILED" : "QUEUED", "lastError", truncate(detail, 1000)));
			if (finalFailure) {
				handoffs.recordEscalation(conversation, "PROCESSING_ERROR", "No se pudo enviar una respuesta por ManyChat: " + truncate(detail, 300));
			}
			return finalFailure;
		}
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

	// --- Cron de recuperación (cada minuto) --------------------------------------------------------

	public record SweepSummary(int conversations, int outboxes, int abandoned, int staleSending) {
	}

	public SweepSummary sweep(long budgetMs) {
		long deadline = System.currentTimeMillis() + budgetMs;
		Instant now = Instant.now();
		// Un proceso que murió entre "SENDING" y el resultado deja el envío en duda: se marca incierto.
		int stale = repo.jdbc().sql("""
				UPDATE conversation_messages SET status = 'UNCERTAIN', "lastError" = 'El proceso se interrumpió durante el envío; pudo haberse entregado.',
				  "updatedAt" = :now WHERE status = 'SENDING' AND "updatedAt" < :cutoff
				""").param("now", Db.ts(now)).param("cutoff", Db.ts(now.minusSeconds(120))).update();
		repo.jdbc().sql("""
				UPDATE conversation_turns SET status = 'FAILED', error = 'El proceso terminó antes de completar el turno.', "updatedAt" = :now
				WHERE status = 'RUNNING' AND "createdAt" < :cutoff
				""").param("now", Db.ts(now)).param("cutoff", Db.ts(now.minusSeconds(300))).update();

		int processed = 0;
		for (String conversationId : conversations.findDueForProcessing(10)) {
			if (System.currentTimeMillis() + 20_000 > deadline) break;
			process(conversationId, Math.min(45_000, deadline - System.currentTimeMillis()));
			processed++;
		}

		int outboxes = 0;
		List<String> pendingOutbox = repo.jdbc().sql("""
				SELECT DISTINCT "conversationId" FROM conversation_messages WHERE status = 'QUEUED' AND "updatedAt" < :cutoff LIMIT 10
				""").param("cutoff", Db.ts(Instant.now().minusSeconds(20))).query(String.class).list();
		for (String conversationId : pendingOutbox) {
			if (System.currentTimeMillis() + 10_000 > deadline) break;
			String owner = newOwner();
			if (conversations.tryClaimLease(conversationId, owner)) {
				try {
					flushOutbox(conversationId);
					outboxes++;
				} finally {
					conversations.releaseLease(conversationId, owner);
				}
			}
		}

		int abandoned = System.currentTimeMillis() + 15_000 < deadline ? handleAbandoned(deadline) : 0;
		return new SweepSummary(processed, outboxes, abandoned, stale);
	}

	/**
	 * Cliente con interés inmobiliario CONFIRMADO que deja de responder: se
	 * canaliza lo disponible. Nunca a proveedores/gerencia ni a quien solo saludó.
	 */
	private int handleAbandoned(long deadline) {
		Instant now = Instant.now();
		List<Conversation> candidates = repo.jdbc().sql("SELECT " + Conversation.COLUMNS + """
				 FROM conversations WHERE control = 'AI' AND "handoffState" = 'NONE'
				 AND "primaryIntent" NOT IN ('UNKNOWN', 'PROVIDER', 'MANAGEMENT', 'OTHER', 'HUMAN_REQUEST')
				 AND "lastInboundAt" < :recent AND "lastInboundAt" > :week AND "lastOutboundAt" IS NOT NULL
				 ORDER BY "lastInboundAt" ASC LIMIT 20
				""").param("recent", Db.ts(now.minusSeconds(60))).param("week", Db.ts(now.minusSeconds(7L * 24 * 3600))).query(Conversation::map).list();
		int handled = 0;
		for (Conversation conversation : candidates) {
			if (System.currentTimeMillis() + 10_000 > deadline) break;
			AssistantSettingsService.Settings settings = settingsService.get(conversation.companyId());
			if (settings.abandonHandoffMinutes() <= 0) continue;
			long idleSince = System.currentTimeMillis() - settings.abandonHandoffMinutes() * 60_000L;
			if (conversation.lastInboundAt() == null || conversation.lastInboundAt().toEpochMilli() > idleSince) continue;
			// El bot respondió último: esperábamos al cliente y no volvió.
			if (conversation.lastOutboundAt() == null || conversation.lastOutboundAt().isBefore(conversation.lastInboundAt())) continue;
			if (conversation.processedSeq() < conversation.lastSeq()) continue;
			String primary = conversation.primaryIntent();
			List<String> secondary = conversation.secondaryIntents();
			if (Policy.isNonCommercial(primary) || !Policy.hasRealEstateInterest(primary, secondary)) continue;

			String owner = newOwner();
			if (!conversations.tryClaimLease(conversation.id(), owner)) continue;
			try {
				List<Map<String, Object>> properties = Json.objects(conversation.properties());
				Map<String, Object> handoff = handoffs.requestCommercialHandoff(conversation, settings, primary, secondary, conversation.factsMap(),
						conversation.summary(), "El cliente dejó de responder tras mostrar interés inmobiliario; se canaliza con la información disponible.",
						properties.size() == 1 ? Json.str(properties.get(0), "publicId") : null, "abandonment");
				Object status = handoff.get("status");
				if ("assigned".equals(status) || "existing_lead".equals(status) || "no_advisor_available".equals(status)) {
					queueAssignmentConfirmation(conversation.id());
					flushOutbox(conversation.id());
				}
				handled++;
			} finally {
				conversations.releaseLease(conversation.id(), owner);
			}
		}
		return handled;
	}

	/** Encola la confirmación de la canalización si todavía no se le comunicó al cliente. */
	private void queueAssignmentConfirmation(String conversationId) {
		Conversation fresh = repo.require(conversationId);
		if (fresh.assignmentNoticeAt() != null) return;
		Session.AssignmentFacts facts = handoffs.loadAssignmentFacts(fresh);
		if (facts == null) return;
		String text = Session.buildAssignmentConfirmation(facts);
		tx.executeWithoutResult(status -> {
			Conversation current = repo.lock(conversationId);
			if (current.assignmentNoticeAt() != null) return;
			int seq = current.lastSeq() + 1;
			boolean caughtUp = current.processedSeq() == current.lastSeq();
			repo.insertMessage(conversationId, current.companyId(), seq, "ASSISTANT", text, current.isSimulator() ? "SIMULATED" : "QUEUED", null, null,
					Json.obj("assignmentConfirmation", true));
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("lastSeq", seq);
			if (caughtUp) fields.put("processedSeq", seq);
			fields.put("assignmentNoticeAt", Instant.now());
			fields.put("lastActivityAt", Instant.now());
			repo.update(conversationId, fields);
		});
	}
}

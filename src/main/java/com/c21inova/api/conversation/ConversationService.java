package com.c21inova.api.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.Phone;
import com.c21inova.api.conversation.ConversationRepository.Increment;

/**
 * Ingesta de mensajes de WhatsApp: se persiste ANTES de confirmar recepción,
 * serializado por conversación con un bloqueo de fila (secuencias
 * consecutivas, nunca se pisan) e idempotente ante reentregas.
 */
@Service
public class ConversationService {

	public static final int MAX_INBOUND_TEXT = 4000;
	/** Un solo procesador por conversación a la vez; si el proceso muere, el lease vence y el cron lo retoma. */
	public static final long LEASE_MS = 150_000;

	public record Inbound(String subscriberId, String text, String phone, String name, String messageId, String interactionAt, boolean simulated,
			Map<String, Object> metadata) {
	}

	/** handled: el backend se hace cargo (ManyChat NO debe correr el flujo anterior). shouldProcess: hay que interpretar y responder. */
	public record IngestResult(boolean duplicate, String conversationId, int seq, boolean handled, boolean shouldProcess, Instant processAfter) {
	}

	private final ConversationRepository repo;
	private final AssistantSettingsService settingsService;
	private final TransactionTemplate tx;

	public ConversationService(ConversationRepository repo, AssistantSettingsService settingsService, TransactionTemplate tx) {
		this.repo = repo;
		this.settingsService = settingsService;
		this.tx = tx;
	}

	public IngestResult ingest(String companyId, Inbound input) {
		AssistantSettingsService.Settings settings = settingsService.get(companyId);
		String text = input.text().length() > MAX_INBOUND_TEXT ? input.text().substring(0, MAX_INBOUND_TEXT) : input.text();
		boolean isTest = input.simulated() || AssistantSettingsService.isTestSubscriber(settings, input.subscriberId());
		String dedupeKey = Session.inboundDedupeKey(input.messageId(), input.subscriberId(), text, input.interactionAt());
		String normalizedPhone = Phone.normalizeE164(input.phone());
		String phone = normalizedPhone.isEmpty() ? null : normalizedPhone;
		String name = input.name() == null || input.name().trim().isEmpty() ? null
				: input.name().trim().length() > 120 ? input.name().trim().substring(0, 120) : input.name().trim();

		String conversationId = upsertConversation(companyId, input.subscriberId(), phone, name, isTest);

		return tx.execute(status -> {
			Conversation current = repo.lock(conversationId);
			boolean handledByAssistant = input.simulated() || settingsService.handles(settings, input.subscriberId());

			if (dedupeKey != null) {
				boolean exists = repo.jdbc().sql("SELECT count(*) FROM conversation_messages WHERE \"conversationId\" = :c AND \"dedupeKey\" = :k")
						.param("c", current.id()).param("k", dedupeKey).query(Integer.class).single() > 0;
				if (exists) return new IngestResult(true, current.id(), 0, handledByAssistant, false, null);
			}

			Instant now = Instant.now();
			int seq = current.lastSeq() + 1;
			// Espera tras canalizar / sesión nueva: solo si la IA atiende a este contacto y nadie la pausó a mano.
			String session = handledByAssistant && "AI".equals(current.control())
					? Session.decideSession(now, current.reopenAt(), current.lastInboundAt(), current.lastSeq() > 0)
					: "continue";
			Map<String, Object> metadata = input.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input.metadata());
			if ("wait".equals(session)) metadata.put("duringWait", true);
			repo.insertMessage(current.id(), companyId, seq, "USER", text, "RECEIVED", dedupeKey, null, metadata.isEmpty() ? null : metadata);

			String campaignCode = current.campaignRef() != null ? null
					: PropertyReference.extractEasyBrokerCodes(text).stream().findFirst().orElse(null);
			Map<String, Object> base = new LinkedHashMap<>();
			base.put("lastSeq", seq);
			base.put("lastInboundAt", now);
			base.put("lastActivityAt", now);
			base.put("isTest", current.isTest() || isTest);
			if (phone != null && current.phone() == null) base.put("phone", phone);
			if (name != null) base.put("name", name);
			if (campaignCode != null) base.put("campaignRef", campaignCode);
			if ("new_session".equals(session)) base.putAll(sessionOpeningData(current, seq, now));

			if (!handledByAssistant) {
				// IA apagada: queda en memoria, pero no se acumula como pendiente.
				base.put("processedSeq", seq);
				base.put("processAfter", null);
				base.put("burstStartedAt", null);
				repo.update(current.id(), base);
				return new IngestResult(false, current.id(), seq, false, false, null);
			}
			if (!"AI".equals(current.control())) {
				// Una persona atiende o la IA está pausada: se guarda, no se contesta.
				repo.update(current.id(), base);
				return new IngestResult(false, current.id(), seq, true, false, null);
			}
			boolean hadPending = current.processedSeq() < current.lastSeq();
			Session.Burst burst = Session.computeProcessAfter(now, hadPending ? current.burstStartedAt() : null, settings.debounceSeconds(),
					settings.maxWaitSeconds());
			base.put("processAfter", burst.processAfter());
			base.put("burstStartedAt", burst.burstStartedAt());
			repo.update(current.id(), base);
			return new IngestResult(false, current.id(), seq, true, true, burst.processAfter());
		});
	}

	private String upsertConversation(String companyId, String subscriberId, String phone, String name, boolean isTest) {
		String sql = "SELECT id FROM conversations WHERE \"companyId\" = :c AND channel = 'whatsapp' AND \"manyChatSubscriberId\" = :s";
		String existing = repo.jdbc().sql(sql).param("c", companyId).param("s", subscriberId).query(String.class).optional().orElse(null);
		if (existing != null) return existing;
		Instant now = Instant.now();
		try {
			repo.jdbc().sql("""
					INSERT INTO conversations (id, "companyId", channel, "manyChatSubscriberId", phone, name, "isTest", "lastActivityAt", "createdAt", "updatedAt")
					VALUES (:id, :c, 'whatsapp', :s, :phone, :name, :isTest, :now, :now, :now)
					""").param("id", Ids.cuid()).param("c", companyId).param("s", subscriberId).param("phone", phone).param("name", name)
					.param("isTest", isTest).param("now", Db.ts(now)).update();
		} catch (DuplicateKeyException race) {
			// Dos primeros mensajes simultáneos del mismo contacto: uno gana el insert.
		}
		return repo.jdbc().sql(sql).param("c", companyId).param("s", subscriberId).query(String.class).single();
	}

	/**
	 * Abre una sesión nueva desde `startSeq`: lo anterior pasa a previousContext
	 * y se limpia la espera. No toca lead, asesor ni estado de canalización.
	 */
	public Map<String, Object> sessionOpeningData(Conversation current, int startSeq, Instant now) {
		List<Object> properties = Json.asList(current.properties()) != null ? Json.asList(current.properties()) : List.of();
		boolean handedOff = "ASSIGNED".equals(current.handoffState()) || "EXISTING_LEAD".equals(current.handoffState());
		boolean sessionHadContent = (current.summary() != null && !current.summary().isEmpty()) || !properties.isEmpty()
				|| !"NONE".equals(current.handoffState());
		Map<String, Object> data = new LinkedHashMap<>();
		if (sessionHadContent) {
			String advisorName = null;
			if (handedOff && current.leadId() != null) {
				advisorName = repo.jdbc().sql("SELECT a.name FROM leads l JOIN advisors a ON a.id = l.\"assignedAdvisorId\" WHERE l.id = :id")
						.param("id", current.leadId()).query(String.class).optional().orElse(null);
			}
			Map<String, Object> previous = new LinkedHashMap<>();
			previous.put("endedAt", com.c21inova.api.common.Texts.iso(current.lastInboundAt() != null ? current.lastInboundAt() : current.lastActivityAt()));
			previous.put("summary", current.summary());
			previous.put("facts", current.facts() != null ? current.facts() : Map.of());
			previous.put("properties", properties);
			previous.put("primaryIntent", current.primaryIntent());
			previous.put("secondaryIntents", current.secondaryIntents());
			previous.put("handoffState", current.handoffState());
			previous.put("handoffAt", com.c21inova.api.common.Texts.iso(current.handoffAt()));
			previous.put("handoffInSession", current.handoffInCurrentSession());
			previous.put("advisorName", advisorName);
			data.put("previousContext", previous);
		}
		Map<String, Object> facts = current.factsMap();
		// Solo el nombre sobrevive: lo demás de antes no se mezcla con la solicitud nueva.
		data.put("facts", facts.get("name") != null ? Json.obj("name", facts.get("name")) : Map.of());
		data.put("properties", new ArrayList<>());
		data.put("summary", null);
		data.put("missingData", new ArrayList<String>());
		data.put("clarificationCount", 0);
		data.put("primaryIntent", "UNKNOWN");
		data.put("secondaryIntents", new ArrayList<String>());
		data.put("reopenAt", null);
		data.put("reopenReason", null);
		data.put("waitNoticeAt", null);
		data.put("sessionStartSeq", startSeq);
		data.put("sessionStartedAt", now);
		data.put("sessionCount", new Increment(1));
		return data;
	}

	// --- Lease --------------------------------------------------------------------------------

	public boolean tryClaimLease(String conversationId, String owner) {
		Instant now = Instant.now();
		return repo.jdbc().sql("""
				UPDATE conversations SET "leaseOwner" = :owner, "leaseUntil" = :until, "updatedAt" = :now
				WHERE id = :id AND ("leaseUntil" IS NULL OR "leaseUntil" < :now OR "leaseOwner" = :owner)
				""").param("owner", owner).param("until", Db.ts(now.plusMillis(LEASE_MS))).param("now", Db.ts(now)).param("id", conversationId).update() == 1;
	}

	public void releaseLease(String conversationId, String owner) {
		repo.jdbc().sql("UPDATE conversations SET \"leaseOwner\" = NULL, \"leaseUntil\" = NULL, \"updatedAt\" = :now WHERE id = :id AND \"leaseOwner\" = :owner")
				.param("now", Db.ts(Instant.now())).param("id", conversationId).param("owner", owner).update();
	}

	/** Conversaciones con mensajes sin interpretar cuya ráfaga ya cerró y nadie procesa (recuperación por cron). */
	public List<String> findDueForProcessing(int limit) {
		Instant now = Instant.now();
		return repo.jdbc().sql("""
				SELECT id FROM conversations WHERE control = 'AI' AND "processAfter" <= :now AND ("leaseUntil" IS NULL OR "leaseUntil" < :now)
				ORDER BY "processAfter" ASC LIMIT :limit
				""").param("now", Db.ts(now)).param("limit", limit).query(String.class).list();
	}
}

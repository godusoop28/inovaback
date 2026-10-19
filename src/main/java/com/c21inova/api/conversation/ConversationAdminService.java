package com.c21inova.api.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.Phone;

/**
 * Lecturas y acciones del panel sobre conversaciones. Todo filtrado por
 * companyId; los permisos por rol se validan en los controladores.
 */
@Service
public class ConversationAdminService {

	public static final Map<String, String> CONTROL_LABELS = Map.of("AI", "IA activa", "HUMAN", "Pausada: atención humana", "PAUSED", "Pausada manualmente");

	public static final Map<String, String> HANDOFF_LABELS = Map.of("NONE", "Sin canalizar", "ASSIGNED", "Asesor asignado", "EXISTING_LEAD", "Lead existente",
			"NO_ADVISOR", "Sin asesor disponible", "FAILED", "Canalización fallida", "NOT_APPLICABLE", "Gerencia / no comercial");

	private final ConversationRepository repo;
	private final TransactionTemplate tx;

	public ConversationAdminService(ConversationRepository repo, TransactionTemplate tx) {
		this.repo = repo;
		this.tx = tx;
	}

	/** Estado visible: pausa manual > espera tras canalizar > IA activa. */
	public static Map<String, Object> status(String control, Instant reopenAt, Instant now) {
		if ("HUMAN".equals(control)) return Json.obj("key", "human", "label", CONTROL_LABELS.get("HUMAN"), "tone", "warning");
		if ("PAUSED".equals(control)) return Json.obj("key", "paused", "label", CONTROL_LABELS.get("PAUSED"), "tone", "warning");
		if (reopenAt != null && reopenAt.isAfter(now)) {
			long minutes = Math.max(1, (long) Math.ceil((reopenAt.toEpochMilli() - now.toEpochMilli()) / 60_000.0));
			return Json.obj("key", "waiting", "label", "Esperando tras canalizar (" + minutes + " min)", "tone", "gold");
		}
		return Json.obj("key", "ai", "label", CONTROL_LABELS.get("AI"), "tone", "success");
	}

	private record AdvisorKeys(List<String> subscriberIds, List<String> phones) {
	}

	/** Contactos de WhatsApp que son asesores del equipo: su conversación va a la pestaña "Asesores". */
	private AdvisorKeys advisorKeys(String companyId) {
		List<String> subscribers = new ArrayList<>();
		List<String> phones = new ArrayList<>();
		repo.jdbc().sql("SELECT phone, \"manyChatSubscriberId\" FROM advisors WHERE \"companyId\" = :c").param("c", companyId).query((rs, i) -> {
			if (rs.getString("manyChatSubscriberId") != null) subscribers.add(rs.getString("manyChatSubscriberId"));
			String phone = Phone.normalizeE164(rs.getString("phone"));
			if (!phone.isEmpty()) phones.add(phone);
			return null;
		}).list();
		return new AdvisorKeys(subscribers, phones);
	}

	/** filter: all | attention | ai | waiting | human | paused | handed_off | errors | test. */
	public Map<String, Object> list(String companyId, String filter, String search, int page, int pageSize) {
		StringBuilder where = new StringBuilder("c.\"companyId\" = :companyId");
		Map<String, Object> params = new HashMap<>();
		params.put("companyId", companyId);
		params.put("now", Db.ts(Instant.now()));
		AdvisorKeys keys = advisorKeys(companyId);
		if (!keys.subscriberIds().isEmpty()) {
			where.append(" AND c.\"manyChatSubscriberId\" NOT IN (:advisorSubscribers)");
			params.put("advisorSubscribers", keys.subscriberIds());
		}
		if (!keys.phones().isEmpty()) {
			where.append(" AND (c.phone IS NULL OR c.phone NOT IN (:advisorPhones))");
			params.put("advisorPhones", keys.phones());
		}
		switch (filter == null ? "all" : filter) {
			case "attention" -> where.append("""
					 AND (c.control IN ('HUMAN', 'PAUSED') OR c."lastError" IS NOT NULL
					   OR EXISTS (SELECT 1 FROM conversation_escalations e WHERE e."conversationId" = c.id AND e.status <> 'RESOLVED'))""");
			case "ai" -> where.append(" AND c.control = 'AI' AND (c.\"reopenAt\" IS NULL OR c.\"reopenAt\" <= :now)");
			case "waiting" -> where.append(" AND c.control = 'AI' AND c.\"reopenAt\" > :now");
			case "human" -> where.append(" AND c.control = 'HUMAN'");
			case "paused" -> where.append(" AND c.control = 'PAUSED'");
			case "handed_off" -> where.append(" AND c.\"handoffState\" IN ('ASSIGNED', 'EXISTING_LEAD')");
			case "errors" -> where.append("""
					 AND (c."lastError" IS NOT NULL OR c."handoffState" IN ('FAILED', 'NO_ADVISOR')
					   OR EXISTS (SELECT 1 FROM conversation_messages m WHERE m."conversationId" = c.id AND m.status IN ('FAILED', 'UNCERTAIN')))""");
			case "test" -> where.append(" AND c.\"isTest\" = true");
			default -> {
			}
		}
		if (!"test".equals(filter) && !"all".equals(filter) && filter != null) where.append(" AND c.\"isTest\" = false");
		if (search != null && !search.trim().isEmpty()) {
			String term = search.trim();
			where.append(" AND (c.name ILIKE :search OR c.phone LIKE :phoneSearch OR c.\"manyChatSubscriberId\" = :exact)");
			params.put("search", "%" + term.replace("%", "\\%").replace("_", "\\_") + "%");
			params.put("phoneSearch", "%" + term.replaceAll("\\s+", "") + "%");
			params.put("exact", term);
		}

		JdbcSpec count = new JdbcSpec("SELECT count(*)::int FROM conversations c WHERE " + where, params);
		int total = count.bind(repo).query(Integer.class).single();
		Map<String, Object> pageParams = new HashMap<>(params);
		pageParams.put("offset", (page - 1) * pageSize);
		pageParams.put("limit", pageSize);
		List<Conversation> rows = new JdbcSpec("SELECT " + Conversation.COLUMNS + " FROM conversations c WHERE " + where
				+ " ORDER BY c.\"lastActivityAt\" DESC OFFSET :offset LIMIT :limit", pageParams).bind(repo).query(Conversation::map).list();

		List<Map<String, Object>> items = new ArrayList<>();
		Instant now = Instant.now();
		for (Conversation conversation : rows) {
			List<Map<String, Object>> escalations = repo.jdbc().sql("""
					SELECT type::text AS type, status::text AS status FROM conversation_escalations
					WHERE "conversationId" = :id AND status <> 'RESOLVED' ORDER BY "createdAt" DESC
					""").param("id", conversation.id()).query((rs, i) -> Json.obj("type", rs.getString("type"), "status", rs.getString("status"))).list();
			Map<String, Object> lastMessage = repo.jdbc().sql("""
					SELECT text, role::text AS role, "createdAt" FROM conversation_messages WHERE "conversationId" = :id ORDER BY seq DESC LIMIT 1
					""").param("id", conversation.id())
					.query((rs, i) -> Json.obj("text", rs.getString("text"), "role", rs.getString("role"), "createdAt", Db.instant(rs, "createdAt"))).optional()
					.orElse(null);
			String advisorName = conversation.leadId() == null ? null
					: repo.jdbc().sql("SELECT a.name FROM leads l JOIN advisors a ON a.id = l.\"assignedAdvisorId\" WHERE l.id = :id")
							.param("id", conversation.leadId()).query(String.class).optional().orElse(null);
			Map<String, Object> item = summaryView(conversation, now);
			item.put("openEscalations", escalations.size());
			item.put("escalations", escalations.size() > 3 ? escalations.subList(0, 3) : escalations);
			item.put("lastMessage", lastMessage);
			item.put("lead", conversation.leadId() == null ? null : Json.obj("id", conversation.leadId(), "advisorName", advisorName));
			items.add(item);
		}
		return Json.obj("items", items, "total", total, "page", page, "pageSize", pageSize);
	}

	/** Pequeño ayudante para enlazar parámetros nombrados. */
	private record JdbcSpec(String sql, Map<String, Object> params) {
		org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bind(ConversationRepository repo) {
			org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec = repo.jdbc().sql(sql);
			for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), entry.getValue());
			return spec;
		}
	}

	static Map<String, Object> summaryView(Conversation c, Instant now) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", c.id());
		view.put("manyChatSubscriberId", c.manyChatSubscriberId());
		view.put("phone", c.phone());
		view.put("name", c.name());
		view.put("isTest", c.isTest());
		view.put("control", c.control());
		view.put("controlReason", c.controlReason());
		view.put("status", status(c.control(), c.reopenAt(), now));
		view.put("handoffState", c.handoffState());
		view.put("handoffLabel", HANDOFF_LABELS.get(c.handoffState()));
		view.put("handoffReason", c.handoffReason());
		view.put("handoffAt", c.handoffAt());
		view.put("leadId", c.leadId());
		view.put("reopenAt", c.reopenAt());
		view.put("primaryIntent", c.primaryIntent());
		view.put("primaryIntentLabel", Policy.INTENT_LABELS.get(c.primaryIntent()));
		view.put("secondaryIntents", c.secondaryIntents());
		view.put("summary", c.summary());
		view.put("lastError", c.lastError());
		view.put("lastInboundAt", c.lastInboundAt());
		view.put("lastOutboundAt", c.lastOutboundAt());
		view.put("lastActivityAt", c.lastActivityAt());
		view.put("createdAt", c.createdAt());
		return view;
	}

	public Map<String, Object> attentionCounts(String companyId) {
		int pending = repo.jdbc().sql("SELECT count(*)::int FROM conversation_escalations WHERE \"companyId\" = :c AND status <> 'RESOLVED'")
				.param("c", companyId).query(Integer.class).single();
		int human = repo.jdbc().sql("SELECT count(*)::int FROM conversations WHERE \"companyId\" = :c AND \"isTest\" = false AND control IN ('HUMAN', 'PAUSED')")
				.param("c", companyId).query(Integer.class).single();
		int delivery = repo.jdbc().sql("""
				SELECT count(*)::int FROM conversation_messages WHERE "companyId" = :c AND status IN ('FAILED', 'UNCERTAIN') AND "createdAt" >= :since
				""").param("c", companyId).param("since", Db.ts(Instant.now().minusSeconds(7 * 86_400))).query(Integer.class).single();
		return Json.obj("pendingEscalations", pending, "humanControl", human, "deliveryIssues", delivery);
	}

	/** Hilos con asesores: leads que se les canalizaron y, si escriben al bot, su conversación. */
	public List<Map<String, Object>> advisorThreads(String companyId, String search) {
		String term = search == null ? null : search.trim();
		Instant weekAgo = Instant.now().minusSeconds(7 * 86_400);
		String sql = """
				SELECT a.id, a.name, a.phone, a.active, a."pausedUntil", a."manyChatSubscriberId",
				  (SELECT count(*)::int FROM lead_assignments la WHERE la."advisorId" = a.id AND la."assignedAt" >= :week) AS "leadsThisWeek",
				  la."assignedAt" AS "lastAssignedAt", la."manyChatNotified" AS "lastNotified", l.name AS "lastLeadName"
				FROM advisors a
				LEFT JOIN LATERAL (SELECT "assignedAt", "manyChatNotified", "leadId" FROM lead_assignments WHERE "advisorId" = a.id ORDER BY "assignedAt" DESC LIMIT 1) la ON true
				LEFT JOIN leads l ON l.id = la."leadId"
				WHERE a."companyId" = :c
				""" + (term != null && !term.isEmpty() ? " AND (a.name ILIKE :search OR a.phone LIKE :phone)" : "") + " ORDER BY a.name ASC";
		org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec = repo.jdbc().sql(sql).param("c", companyId).param("week", Db.ts(weekAgo));
		if (term != null && !term.isEmpty()) spec = spec.param("search", "%" + term + "%").param("phone", "%" + term.replaceAll("\\s+", "") + "%");
		List<Map<String, Object>> advisors = spec.query((rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getString("id"));
			row.put("name", rs.getString("name"));
			row.put("phone", rs.getString("phone"));
			row.put("active", rs.getBoolean("active"));
			row.put("pausedUntil", Db.instant(rs, "pausedUntil"));
			row.put("subscriber", rs.getString("manyChatSubscriberId"));
			row.put("leadsThisWeek", rs.getInt("leadsThisWeek"));
			row.put("lastAssignedAt", Db.instant(rs, "lastAssignedAt"));
			row.put("lastNotified", rs.getBoolean("lastNotified"));
			row.put("lastLeadName", rs.getString("lastLeadName"));
			return row;
		}).list();

		List<Map<String, Object>> threads = new ArrayList<>();
		for (Map<String, Object> advisor : advisors) {
			String phone = Phone.normalizeE164((String) advisor.get("phone"));
			Map<String, Object> conversation = findAdvisorConversation(companyId, (String) advisor.get("subscriber"), phone, false);
			Instant assignmentAt = (Instant) advisor.get("lastAssignedAt");
			Map<String, Object> lastMessage = conversation == null ? null : Json.asMap(conversation.get("lastMessage"));
			Instant messageAt = lastMessage == null ? null : (Instant) lastMessage.get("createdAt");
			boolean showMessage = lastMessage != null && messageAt != null && (assignmentAt == null || messageAt.isAfter(assignmentAt));
			Map<String, Object> preview = showMessage ? Json.obj("kind", "message", "text", lastMessage.get("text"), "role", lastMessage.get("role"))
					: assignmentAt != null ? Json.obj("kind", "lead", "text", "Nuevo lead: " + advisor.get("lastLeadName"), "notified", advisor.get("lastNotified"))
							: null;
			threads.add(Json.obj("advisor", Json.obj("id", advisor.get("id"), "name", advisor.get("name"), "phone", advisor.get("phone"), "active",
					advisor.get("active"), "pausedUntil", advisor.get("pausedUntil")), "conversationId", conversation == null ? null : conversation.get("id"),
					"leadsThisWeek", advisor.get("leadsThisWeek"), "lastAt", showMessage ? messageAt : assignmentAt, "preview", preview));
		}
		threads.sort(Comparator.<Map<String, Object>, Boolean>comparing(t -> !Boolean.TRUE.equals(Json.asMap(t.get("advisor")).get("active")))
				.thenComparing(t -> -(t.get("lastAt") instanceof Instant at ? at.toEpochMilli() : 0L)));
		return threads;
	}

	private Map<String, Object> findAdvisorConversation(String companyId, String subscriber, String phone, boolean latestFirst) {
		if (subscriber == null && (phone == null || phone.isEmpty())) return null;
		String id = repo.jdbc().sql("""
				SELECT id FROM conversations WHERE "companyId" = :c AND ("manyChatSubscriberId" = :s OR phone = :p) ORDER BY "lastActivityAt" DESC LIMIT 1
				""").param("c", companyId).param("s", subscriber == null ? "" : subscriber).param("p", phone == null || phone.isEmpty() ? "" : phone)
				.query(String.class).optional().orElse(null);
		if (id == null) return null;
		Map<String, Object> lastMessage = repo.jdbc().sql("""
				SELECT text, role::text AS role, "createdAt" FROM conversation_messages WHERE "conversationId" = :id ORDER BY seq DESC LIMIT 1
				""").param("id", id).query((rs, i) -> Json.obj("text", rs.getString("text"), "role", rs.getString("role"), "createdAt", Db.instant(rs, "createdAt")))
				.optional().orElse(null);
		return Json.obj("id", id, "lastMessage", lastMessage);
	}

	public Map<String, Object> advisorThread(String companyId, String advisorId) {
		Map<String, Object> advisor = repo.jdbc().sql("SELECT id, name, phone, active, \"pausedUntil\", \"manyChatSubscriberId\" FROM advisors WHERE id = :id AND \"companyId\" = :c")
				.param("id", advisorId).param("c", companyId).query((rs, i) -> Json.obj("id", rs.getString("id"), "name", rs.getString("name"), "phone",
						rs.getString("phone"), "active", rs.getBoolean("active"), "pausedUntil", Db.instant(rs, "pausedUntil"), "manyChatSubscriberId",
						rs.getString("manyChatSubscriberId")))
				.optional().orElse(null);
		if (advisor == null) return null;
		List<Map<String, Object>> assignments = repo.jdbc().sql("""
				SELECT la.id, la."assignedAt", la.method::text AS method, la."manyChatNotified", la."easyBrokerConfirmed", la.reason,
				  l.id AS "leadId", l.name, l.phone, l.route, l.origin, l."propertyData",
				  (SELECT c.id FROM conversations c WHERE c."leadId" = l.id ORDER BY c."lastActivityAt" DESC LIMIT 1) AS "conversationId"
				FROM lead_assignments la JOIN leads l ON l.id = la."leadId"
				WHERE la."advisorId" = :a AND l."companyId" = :c ORDER BY la."assignedAt" DESC LIMIT 100
				""").param("a", advisorId).param("c", companyId).query((rs, i) -> Json.obj("id", rs.getString("id"), "assignedAt", Db.instant(rs, "assignedAt"),
				"method", rs.getString("method"), "manyChatNotified", rs.getBoolean("manyChatNotified"), "easyBrokerConfirmed",
				rs.getBoolean("easyBrokerConfirmed"), "reason", rs.getString("reason"), "lead", Json.obj("id", rs.getString("leadId"), "name",
						rs.getString("name"), "phone", rs.getString("phone"), "route", rs.getString("route"), "origin", rs.getString("origin"), "propertyData",
						rs.getString("propertyData"), "conversationId", rs.getString("conversationId"))))
				.list();
		Map<String, Object> conversationRef = findAdvisorConversation(companyId, (String) advisor.get("manyChatSubscriberId"),
				Phone.normalizeE164((String) advisor.get("phone")), true);
		Map<String, Object> conversation = null;
		if (conversationRef != null) {
			String id = (String) conversationRef.get("id");
			Conversation c = repo.require(id);
			conversation = summaryView(c, Instant.now());
			List<Map<String, Object>> messages = new ArrayList<>(messages(id, 300, true));
			java.util.Collections.reverse(messages);
			conversation.put("messages", messages);
		}
		return Json.obj("advisor", advisor, "assignments", assignments, "conversation", conversation);
	}

	private List<Map<String, Object>> messages(String conversationId, int limit, boolean desc) {
		return repo.jdbc().sql("SELECT id, seq, role::text AS role, text, status::text AS status, attempts, \"lastError\", \"sentAt\", metadata::text AS metadata, "
				+ "\"createdAt\" FROM conversation_messages WHERE \"conversationId\" = :id ORDER BY seq " + (desc ? "DESC" : "ASC") + " LIMIT :limit")
				.param("id", conversationId).param("limit", limit).query((rs, i) -> Json.obj("id", rs.getString("id"), "seq", rs.getInt("seq"), "role",
						rs.getString("role"), "text", rs.getString("text"), "status", rs.getString("status"), "attempts", rs.getInt("attempts"), "lastError",
						rs.getString("lastError"), "sentAt", Db.instant(rs, "sentAt"), "metadata", Db.json(rs, "metadata"), "createdAt",
						Db.instant(rs, "createdAt")))
				.list();
	}

	public List<Map<String, Object>> openEscalations(String companyId, int limit) {
		return repo.jdbc().sql("""
				SELECT e.id, e.type::text AS type, e.status::text AS status, e.reason, e."notifiedAt", e."createdAt", e."updatedAt",
				  c.id AS "conversationId", c.name, c.phone, c."isTest"
				FROM conversation_escalations e JOIN conversations c ON c.id = e."conversationId"
				WHERE e."companyId" = :c AND e.status <> 'RESOLVED' ORDER BY e."createdAt" DESC LIMIT :limit
				""").param("c", companyId).param("limit", limit).query((rs, i) -> Json.obj("id", rs.getString("id"), "type", rs.getString("type"), "status",
				rs.getString("status"), "reason", rs.getString("reason"), "notifiedAt", Db.instant(rs, "notifiedAt"), "createdAt", Db.instant(rs, "createdAt"),
				"updatedAt", Db.instant(rs, "updatedAt"), "conversation", Json.obj("id", rs.getString("conversationId"), "name", rs.getString("name"), "phone",
						rs.getString("phone"), "isTest", rs.getBoolean("isTest"))))
				.list();
	}

	public Map<String, Object> detail(String companyId, String conversationId) {
		Conversation c = repo.findForCompany(companyId, conversationId).orElse(null);
		if (c == null) return null;
		Map<String, Object> view = summaryView(c, Instant.now());
		view.put("facts", c.facts());
		view.put("properties", c.properties());
		view.put("missingData", c.missingData());
		view.put("previousContext", c.previousContext());
		view.put("campaignRef", c.campaignRef());
		view.put("clarificationCount", c.clarificationCount());
		view.put("sessionCount", c.sessionCount());
		view.put("sessionStartedAt", c.sessionStartedAt());
		view.put("lastSeq", c.lastSeq());
		view.put("processedSeq", c.processedSeq());
		view.put("consecutiveFailures", c.consecutiveFailures());
		view.put("assignmentNoticeAt", c.assignmentNoticeAt());
		view.put("messages", messages(c.id(), 500, false));
		view.put("turns", repo.jdbc().sql("""
				SELECT id, "fromSeq", "toSeq", status::text AS status, model, "inputTokens", "outputTokens", "toolCalls"::text AS "toolCalls", error, "durationMs", "createdAt"
				FROM conversation_turns WHERE "conversationId" = :id ORDER BY "createdAt" DESC LIMIT 30
				""").param("id", c.id()).query((rs, i) -> Json.obj("id", rs.getString("id"), "fromSeq", rs.getInt("fromSeq"), "toSeq", rs.getInt("toSeq"),
				"status", rs.getString("status"), "model", rs.getString("model"), "inputTokens", Db.integer(rs, "inputTokens"), "outputTokens",
				Db.integer(rs, "outputTokens"), "toolCalls", Db.json(rs, "toolCalls"), "error", rs.getString("error"), "durationMs",
				Db.integer(rs, "durationMs"), "createdAt", Db.instant(rs, "createdAt"))).list());
		view.put("escalations", repo.jdbc().sql("""
				SELECT id, type::text AS type, status::text AS status, reason, "notifiedAt", "resolvedAt", "resolvedBy", notes, "createdAt"
				FROM conversation_escalations WHERE "conversationId" = :id ORDER BY "createdAt" DESC
				""").param("id", c.id()).query((rs, i) -> Json.obj("id", rs.getString("id"), "type", rs.getString("type"), "status", rs.getString("status"),
				"reason", rs.getString("reason"), "notifiedAt", Db.instant(rs, "notifiedAt"), "resolvedAt", Db.instant(rs, "resolvedAt"), "resolvedBy",
				rs.getString("resolvedBy"), "notes", rs.getString("notes"), "createdAt", Db.instant(rs, "createdAt"))).list());
		if (c.leadId() != null) {
			view.put("lead", repo.jdbc().sql("""
					SELECT l.id, l.name, l.phone, l.status::text AS status, l.route, a.id AS "advisorId", a.name AS "advisorName", a.phone AS "advisorPhone",
					  (SELECT la."manyChatNotified" FROM lead_assignments la WHERE la."leadId" = l.id ORDER BY la."assignedAt" DESC LIMIT 1) AS notified
					FROM leads l LEFT JOIN advisors a ON a.id = l."assignedAdvisorId" WHERE l.id = :id
					""").param("id", c.leadId()).query((rs, i) -> Json.obj("id", rs.getString("id"), "name", rs.getString("name"), "phone", rs.getString("phone"),
					"status", rs.getString("status"), "route", rs.getString("route"), "assignedAdvisor", rs.getString("advisorId") == null ? null
							: Json.obj("id", rs.getString("advisorId"), "name", rs.getString("advisorName"), "phone", rs.getString("advisorPhone")),
					"manyChatNotified", rs.getBoolean("notified"))).optional().orElse(null));
		}
		return view;
	}

	private Conversation requireConversation(String companyId, String conversationId) {
		return repo.findForCompany(companyId, conversationId).orElseThrow(() -> ApiException.notFound("Conversación no encontrada."));
	}

	/** Pausa la IA. HUMAN: alguien del equipo responde en el mismo número; PAUSED: solo detenerla. */
	public void setControl(String companyId, String conversationId, String control, String reason, String by) {
		requireConversation(companyId, conversationId);
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("control", control);
		fields.put("controlReason", reason.length() > 500 ? reason.substring(0, 500) : reason);
		fields.put("controlChangedAt", Instant.now());
		fields.put("controlChangedBy", by);
		fields.put("processAfter", null);
		fields.put("burstStartedAt", null);
		repo.update(conversationId, fields);
	}

	/**
	 * Reanuda la IA. Por defecto NO contesta mensajes que llegaron durante la
	 * pausa (los marca como vistos); answerPending sí los procesa ahora.
	 */
	public boolean resume(String companyId, String conversationId, String by, boolean answerPending) {
		Conversation conversation = requireConversation(companyId, conversationId);
		boolean pending = conversation.processedSeq() < conversation.lastSeq();
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("control", "AI");
		fields.put("controlReason", "Reanudada por " + by);
		fields.put("controlChangedAt", Instant.now());
		fields.put("controlChangedBy", by);
		fields.put("consecutiveFailures", 0);
		fields.put("lastError", null);
		if (answerPending && pending) {
			fields.put("processAfter", Instant.now());
			fields.put("burstStartedAt", Instant.now());
		} else {
			fields.put("processedSeq", conversation.lastSeq());
			fields.put("processAfter", null);
			fields.put("burstStartedAt", null);
		}
		repo.update(conversationId, fields);
		return answerPending && pending;
	}

	/** Nota de lo que una persona respondió fuera del bot, para que la IA la tenga en memoria (no se envía nada). */
	public void recordHumanReply(String companyId, String conversationId, String text, String by) {
		requireConversation(companyId, conversationId);
		tx.executeWithoutResult(status -> {
			Conversation current = repo.lock(conversationId);
			int seq = current.lastSeq() + 1;
			repo.insertMessage(current.id(), current.companyId(), seq, "HUMAN_AGENT", text.length() > 2000 ? text.substring(0, 2000) : text, "SENT", null, null,
					Json.obj("recordedBy", by, "recordedFromPanel", true));
			boolean caughtUp = current.processedSeq() == current.lastSeq();
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("lastSeq", seq);
			if (caughtUp) fields.put("processedSeq", seq);
			fields.put("lastActivityAt", Instant.now());
			repo.update(current.id(), fields);
		});
	}

	public String resolveEscalation(String companyId, String escalationId, String by, String notes) {
		String conversationId = repo.jdbc().sql("SELECT \"conversationId\" FROM conversation_escalations WHERE id = :id AND \"companyId\" = :c")
				.param("id", escalationId).param("c", companyId).query(String.class).optional()
				.orElseThrow(() -> ApiException.notFound("Pendiente no encontrado."));
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("status", "RESOLVED");
		fields.put("resolvedAt", Instant.now());
		fields.put("resolvedBy", by);
		fields.put("notes", notes == null ? null : notes.length() > 1000 ? notes.substring(0, 1000) : notes);
		repo.updateWhere("conversation_escalations", "id = :id", Map.of("id", escalationId), fields);
		return conversationId;
	}

	/** Reintento manual de un envío. UNCERTAIN requiere confirmación explícita (el cliente pudo haberlo recibido). */
	public String requeueMessage(String companyId, String messageId, boolean confirmUncertain) {
		Map<String, Object> message = repo.jdbc().sql("SELECT \"conversationId\", status::text AS status FROM conversation_messages WHERE id = :id AND \"companyId\" = :c")
				.param("id", messageId).param("c", companyId)
				.query((rs, i) -> Json.obj("conversationId", rs.getString("conversationId"), "status", rs.getString("status"))).optional()
				.orElseThrow(() -> ApiException.notFound("Mensaje no encontrado."));
		String status = (String) message.get("status");
		if ("UNCERTAIN".equals(status) && !confirmUncertain) {
			throw ApiException.badRequest("El envío es incierto: confirma que el cliente no lo recibió antes de reenviarlo.");
		}
		if (!"FAILED".equals(status) && !"UNCERTAIN".equals(status)) throw ApiException.badRequest("Solo se reintentan envíos fallidos o inciertos.");
		repo.updateWhere("conversation_messages", "id = :id", Map.of("id", messageId), Json.obj("status", "QUEUED", "attempts", 0, "lastError", null));
		return (String) message.get("conversationId");
	}
}

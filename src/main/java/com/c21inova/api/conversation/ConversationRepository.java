package com.c21inova.api.conversation;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;

/** Acceso a conversations / conversation_messages / turns / escalations con los tipos de Prisma. */
@Repository
public class ConversationRepository {

	/** Valor especial para "columna = columna + n". */
	public record Increment(int by) {
	}

	/** Tipo de Postgres de cada columna con cast (enums, arreglos, JSONB), por tabla. */
	static String castFor(String table, String column) {
		return switch (table + "." + column) {
			case "conversations.control" -> "\"ConversationControl\"";
			case "conversations.handoffState" -> "\"ConversationHandoffState\"";
			case "conversations.primaryIntent" -> "\"ConversationIntent\"";
			case "conversations.secondaryIntents" -> "\"ConversationIntent\"[]";
			case "conversations.missingData" -> "text[]";
			case "conversations.facts", "conversations.properties", "conversations.previousContext" -> "jsonb";
			case "conversation_messages.status" -> "\"ConversationMessageStatus\"";
			case "conversation_messages.role" -> "\"ConversationMessageRole\"";
			case "conversation_messages.metadata", "conversation_turns.toolCalls" -> "jsonb";
			case "conversation_turns.status" -> "\"ConversationTurnStatus\"";
			case "conversation_escalations.status" -> "\"EscalationStatus\"";
			case "conversation_escalations.type" -> "\"EscalationType\"";
			default -> null;
		};
	}

	private final JdbcClient jdbc;

	public ConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public JdbcClient jdbc() {
		return jdbc;
	}

	public Optional<Conversation> findById(String id) {
		return jdbc.sql("SELECT " + Conversation.COLUMNS + " FROM conversations WHERE id = :id").param("id", id).query(Conversation::map).optional();
	}

	public Conversation require(String id) {
		return findById(id).orElseThrow(() -> new IllegalStateException("Conversación " + id + " no encontrada."));
	}

	public Optional<Conversation> findForCompany(String companyId, String id) {
		return jdbc.sql("SELECT " + Conversation.COLUMNS + " FROM conversations WHERE id = :id AND \"companyId\" = :c").param("id", id).param("c", companyId)
				.query(Conversation::map).optional();
	}

	/** SELECT ... FOR UPDATE: serializa por conversación (debe correr dentro de una transacción). */
	public Conversation lock(String id) {
		jdbc.sql("SELECT id FROM conversations WHERE id = :id FOR UPDATE").param("id", id).query(String.class).list();
		return require(id);
	}

	static Object paramValue(Object value) {
		if (value instanceof Instant instant) return Db.ts(instant);
		return value;
	}

	/** UPDATE conversations SET ... con los casts de Prisma; siempre actualiza updatedAt. */
	public int update(String id, Map<String, Object> fields) {
		return updateWhere("conversations", "id = :id", Map.of("id", id), fields);
	}

	/** UPDATE ... WHERE <condición> (para actualizaciones condicionales tipo updateMany). */
	public int updateWhere(String table, String where, Map<String, Object> whereParams, Map<String, Object> fields) {
		StringBuilder set = new StringBuilder();
		Map<String, Object> params = new LinkedHashMap<>(whereParams);
		int i = 0;
		for (Map.Entry<String, Object> entry : fields.entrySet()) {
			String column = entry.getKey();
			String param = "f" + (i++);
			Object value = entry.getValue();
			if (!set.isEmpty()) set.append(", ");
			set.append('"').append(column).append("\" = ");
			if (value instanceof Increment increment) {
				set.append('"').append(column).append("\" + ").append(increment.by());
				continue;
			}
			String cast = castFor(table, column);
			if (cast != null && value != null) {
				if (cast.equals("jsonb")) value = Db.jsonParam(value);
				else if (cast.endsWith("[]") && value instanceof Collection<?> collection) value = Db.arrayLiteral(collection);
				set.append("CAST(:").append(param).append(" AS ").append(cast).append(')');
			} else if (cast != null) {
				set.append("NULL");
				continue;
			} else {
				set.append(':').append(param);
			}
			params.put(param, paramValue(value));
		}
		set.append(", \"updatedAt\" = :__now");
		params.put("__now", Db.ts(Instant.now()));
		JdbcClient.StatementSpec spec = jdbc.sql("UPDATE " + table + " SET " + set + " WHERE " + where);
		for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), paramValue(entry.getValue()));
		return spec.update();
	}

	public String insertMessage(String conversationId, String companyId, int seq, String role, String text, String status, String dedupeKey, String turnId,
			Object metadata) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO conversation_messages (id, "conversationId", "companyId", seq, role, text, status, "dedupeKey", "turnId", attempts, metadata,
				  "createdAt", "updatedAt")
				VALUES (:id, :conv, :c, :seq, CAST(:role AS "ConversationMessageRole"), :text, CAST(:status AS "ConversationMessageStatus"), :dedupe, :turn, 0,
				  CAST(:metadata AS jsonb), :now, :now)
				""").param("id", id).param("conv", conversationId).param("c", companyId).param("seq", seq).param("role", role).param("text", text)
				.param("status", status).param("dedupe", dedupeKey).param("turn", turnId).param("metadata", Db.jsonParam(metadata)).param("now", Db.ts(now))
				.update();
		return id;
	}

	public String insertTurn(String conversationId, int fromSeq, int toSeq) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO conversation_turns (id, "conversationId", "fromSeq", "toSeq", status, "createdAt", "updatedAt")
				VALUES (:id, :conv, :from, :to, 'RUNNING', :now, :now)
				""").param("id", id).param("conv", conversationId).param("from", fromSeq).param("to", toSeq).param("now", Db.ts(now)).update();
		return id;
	}

	public void updateTurn(String turnId, Map<String, Object> fields) {
		updateWhere("conversation_turns", "id = :id", Map.of("id", turnId), fields);
	}

	public record HistoryRow(String role, String text, int seq) {
	}

	public List<HistoryRow> recentHistory(String conversationId, int limit) {
		List<HistoryRow> rows = new java.util.ArrayList<>(jdbc.sql("""
				SELECT role::text AS role, text, seq FROM conversation_messages
				WHERE "conversationId" = :conv AND (role = 'USER' OR (role IN ('ASSISTANT', 'HUMAN_AGENT')
				  AND status IN ('SENT', 'SIMULATED', 'UNCERTAIN', 'QUEUED', 'SENDING')))
				ORDER BY seq DESC LIMIT :limit
				""").param("conv", conversationId).param("limit", limit)
				.query((rs, i) -> new HistoryRow(rs.getString("role"), rs.getString("text"), rs.getInt("seq"))).list());
		java.util.Collections.reverse(rows);
		return rows;
	}
}

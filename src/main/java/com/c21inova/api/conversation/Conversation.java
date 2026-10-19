package com.c21inova.api.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;

/** Fila de "conversations". */
public record Conversation(
		String id,
		String companyId,
		String channel,
		String manyChatSubscriberId,
		String phone,
		String name,
		boolean isTest,
		String control,
		String controlReason,
		Instant controlChangedAt,
		String controlChangedBy,
		String handoffState,
		String handoffReason,
		Instant handoffAt,
		String leadId,
		Instant reopenAt,
		String reopenReason,
		Instant waitNoticeAt,
		Instant assignmentNoticeAt,
		int sessionStartSeq,
		Instant sessionStartedAt,
		int sessionCount,
		Object previousContext,
		String primaryIntent,
		List<String> secondaryIntents,
		Object facts,
		Object properties,
		List<String> missingData,
		String summary,
		int clarificationCount,
		String campaignRef,
		int lastSeq,
		int processedSeq,
		Instant burstStartedAt,
		Instant processAfter,
		String leaseOwner,
		Instant leaseUntil,
		int consecutiveFailures,
		String lastError,
		Instant lastInboundAt,
		Instant lastOutboundAt,
		Instant lastActivityAt,
		Instant createdAt,
		Instant updatedAt) {

	public static final String COLUMNS = """
			id, "companyId", channel, "manyChatSubscriberId", phone, name, "isTest", control::text AS control, "controlReason", "controlChangedAt",
			"controlChangedBy", "handoffState"::text AS "handoffState", "handoffReason", "handoffAt", "leadId", "reopenAt", "reopenReason", "waitNoticeAt",
			"assignmentNoticeAt", "sessionStartSeq", "sessionStartedAt", "sessionCount", "previousContext"::text AS "previousContext",
			"primaryIntent"::text AS "primaryIntent", "secondaryIntents"::text[] AS "secondaryIntents", facts::text AS facts, properties::text AS properties,
			"missingData", summary, "clarificationCount", "campaignRef", "lastSeq", "processedSeq", "burstStartedAt", "processAfter", "leaseOwner",
			"leaseUntil", "consecutiveFailures", "lastError", "lastInboundAt", "lastOutboundAt", "lastActivityAt", "createdAt", "updatedAt"
			""";

	public static Conversation map(ResultSet rs, int row) throws SQLException {
		return new Conversation(
				rs.getString("id"),
				rs.getString("companyId"),
				rs.getString("channel"),
				rs.getString("manyChatSubscriberId"),
				rs.getString("phone"),
				rs.getString("name"),
				rs.getBoolean("isTest"),
				rs.getString("control"),
				rs.getString("controlReason"),
				Db.instant(rs, "controlChangedAt"),
				rs.getString("controlChangedBy"),
				rs.getString("handoffState"),
				rs.getString("handoffReason"),
				Db.instant(rs, "handoffAt"),
				rs.getString("leadId"),
				Db.instant(rs, "reopenAt"),
				rs.getString("reopenReason"),
				Db.instant(rs, "waitNoticeAt"),
				Db.instant(rs, "assignmentNoticeAt"),
				rs.getInt("sessionStartSeq"),
				Db.instant(rs, "sessionStartedAt"),
				rs.getInt("sessionCount"),
				Db.json(rs, "previousContext"),
				rs.getString("primaryIntent"),
				Db.stringArray(rs, "secondaryIntents"),
				Db.json(rs, "facts"),
				Db.json(rs, "properties"),
				Db.stringArray(rs, "missingData"),
				rs.getString("summary"),
				rs.getInt("clarificationCount"),
				rs.getString("campaignRef"),
				rs.getInt("lastSeq"),
				rs.getInt("processedSeq"),
				Db.instant(rs, "burstStartedAt"),
				Db.instant(rs, "processAfter"),
				rs.getString("leaseOwner"),
				Db.instant(rs, "leaseUntil"),
				rs.getInt("consecutiveFailures"),
				rs.getString("lastError"),
				Db.instant(rs, "lastInboundAt"),
				Db.instant(rs, "lastOutboundAt"),
				Db.instant(rs, "lastActivityAt"),
				Db.instant(rs, "createdAt"),
				Db.instant(rs, "updatedAt"));
	}

	/** Contactos del simulador del panel: nunca envían nada por WhatsApp. */
	public static final String SIMULATOR_SUBSCRIBER_PREFIX = "sim-";

	public boolean isSimulator() {
		return manyChatSubscriberId.startsWith(SIMULATOR_SUBSCRIBER_PREFIX);
	}

	public boolean handoffInCurrentSession() {
		return Session.handoffInCurrentSession(handoffAt, sessionStartedAt);
	}

	/** facts como objeto (vacío si no hay). */
	public Map<String, Object> factsMap() {
		Map<String, Object> map = Json.asMap(facts);
		return map != null ? map : new java.util.LinkedHashMap<>();
	}

	public Map<String, Object> previousContextMap() {
		return Json.asMap(previousContext);
	}
}

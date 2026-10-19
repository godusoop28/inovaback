package com.c21inova.api.common;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Bitácora (audit_logs). Registrar nunca debe ocultar el resultado real de
 * lo que se audita: un fallo al escribir se reporta en consola y se ignora.
 */
@Service
public class AuditService {

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	private final JdbcClient jdbc;

	public AuditService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void log(String companyId, String leadId, String advisorId, String eventType, String status, String message, Map<String, Object> metadata) {
		try {
			jdbc.sql("""
					INSERT INTO audit_logs (id, "companyId", "leadId", "advisorId", "eventType", status, message, metadata, "createdAt")
					VALUES (:id, :companyId, :leadId, :advisorId, :eventType, :status, :message, CAST(:metadata AS jsonb), :now)
					""")
					.param("id", Ids.cuid())
					.param("companyId", companyId)
					.param("leadId", leadId)
					.param("advisorId", advisorId)
					.param("eventType", eventType)
					.param("status", status)
					.param("message", message)
					.param("metadata", Db.jsonParam(metadata))
					.param("now", Db.ts(Instant.now()))
					.update();
		} catch (RuntimeException error) {
			log.error("[AUDIT] no se pudo escribir la bitácora {}: {}", eventType, error.getMessage());
		}
	}

	public void log(String companyId, String leadId, String advisorId, String eventType, String status, String message) {
		log(companyId, leadId, advisorId, eventType, status, message, null);
	}

	public List<Map<String, Object>> recent(String companyId, int limit) {
		return jdbc.sql("""
				SELECT id, "leadId", "advisorId", "eventType", status, message, "createdAt" FROM audit_logs
				WHERE "companyId" = :companyId ORDER BY "createdAt" DESC LIMIT :limit
				""").param("companyId", companyId).param("limit", limit).query((rs, i) -> Json.obj(
				"id", rs.getString("id"),
				"leadId", rs.getString("leadId"),
				"advisorId", rs.getString("advisorId"),
				"eventType", rs.getString("eventType"),
				"status", rs.getString("status"),
				"message", rs.getString("message"),
				"createdAt", Db.instant(rs, "createdAt"))).list();
	}
}

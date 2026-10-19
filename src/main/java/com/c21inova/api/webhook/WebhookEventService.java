package com.c21inova.api.webhook;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;

/**
 * Registra cada entrega de webhook (bitácora de intentos) e informa si ya
 * existe un Lead con este requestId: esa es la verdadera barrera de
 * duplicados antes de crear lead, llamar a EasyBroker o avisar.
 */
@Service
public class WebhookEventService {

	private static final Logger log = LoggerFactory.getLogger(WebhookEventService.class);

	public record Delivery(boolean duplicateLead, String webhookEventId) {
	}

	private final JdbcClient jdbc;

	public WebhookEventService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Delivery record(String provider, String externalId, String eventType, Object payload, String requestId) {
		boolean existingLead = jdbc.sql("SELECT count(*) FROM leads WHERE \"requestId\" = :r").param("r", requestId).query(Integer.class).single() > 0;
		String id = Ids.cuid();
		try {
			jdbc.sql("""
					INSERT INTO webhook_events (id, provider, "externalId", "eventType", payload, status, "processingAttempts", "createdAt")
					VALUES (:id, :provider, :externalId, :eventType, CAST(:payload AS jsonb), :status, 0, :now)
					""").param("id", id).param("provider", provider).param("externalId", externalId).param("eventType", eventType)
					.param("payload", Db.jsonParam(payload)).param("status", existingLead ? "duplicate" : "received").param("now", Db.ts(Instant.now()))
					.update();
		} catch (RuntimeException uniqueViolation) {
			// Reentrega del mismo evento (provider, externalId): la barrera real es Lead.requestId.
			id = null;
		}
		return new Delivery(existingLead, id);
	}

	public void markProcessed(String webhookEventId, String error) {
		if (webhookEventId == null) return;
		try {
			jdbc.sql("""
					UPDATE webhook_events SET status = :status, "processedAt" = :now, "processingAttempts" = "processingAttempts" + 1, "lastError" = :error
					WHERE id = :id
					""").param("status", error != null ? "failed" : "processed").param("now", Db.ts(Instant.now())).param("error", error)
					.param("id", webhookEventId).update();
		} catch (RuntimeException updateError) {
			log.error("[WEBHOOK] no se pudo marcar el evento como procesado", updateError);
		}
	}
}

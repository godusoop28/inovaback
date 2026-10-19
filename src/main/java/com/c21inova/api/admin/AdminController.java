package com.c21inova.api.admin;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.IntegrationRecoveryService;

@RestController
public class AdminController {

	private final Env env;
	private final JdbcClient jdbc;
	private final AuditService audit;
	private final IntegrationRecoveryService recovery;

	public AdminController(Env env, JdbcClient jdbc, AuditService audit, IntegrationRecoveryService recovery) {
		this.env = env;
		this.jdbc = jdbc;
		this.audit = audit;
		this.recovery = recovery;
	}

	private boolean databaseReachable() {
		try {
			jdbc.sql("SELECT 1").query(Integer.class).single();
			return true;
		} catch (RuntimeException error) {
			return false;
		}
	}

	/** Público, sin secretos: apto para un monitor de disponibilidad. */
	@GetMapping("/api/health")
	public ResponseEntity<Map<String, Object>> health() {
		boolean ok = databaseReachable();
		return ResponseEntity.ok(Json.obj("status", ok ? "ok" : "degraded", "database", ok ? "connected" : "unavailable", "automationMode",
				env.automationMode()));
	}

	@GetMapping("/")
	public Map<String, Object> root() {
		return Json.obj("service", "inovaback", "status", "ok", "health", "/api/health");
	}

	/** Solo ADMIN; solo booleanos, nunca el valor de un secreto. */
	@GetMapping("/api/admin/readiness")
	public Map<String, Object> readiness() {
		SessionUser.requireRole("ADMIN");
		return Json.obj("databaseConfigured", env.databaseConfigured(), "databaseReachable", databaseReachable(), "easyBrokerConfigured",
				env.easyBrokerConfigured(), "manyChatConfigured", env.manyChatConfigured(), "openAIConfigured", env.openAiConfigured(),
				"integrationSecretConfigured", env.integrationSecretConfigured(), "automationMode", env.automationMode(), "schedulerEnabled",
				env.schedulerEnabled(), "internalTestingEnabled", env.internalTestingEnabled());
	}

	@GetMapping("/api/admin/audit")
	public Map<String, Object> recentAudit(@RequestParam(defaultValue = "25") int limit) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		return Json.obj("ok", true, "events", audit.recent(user.companyId(), Math.min(Math.max(limit, 1), 200)));
	}

	/** Reporte en seco de avisos MANYCHAT_FLOW abiertos (no cambia nada ni llama a ManyChat). */
	@GetMapping("/api/admin/integration-jobs/recovery")
	public Map<String, Object> recoveryReport() {
		SessionUser user = SessionUser.requireRole("ADMIN");
		Map<String, Object> report = recovery.report(user.companyId());
		Map<String, Object> out = Json.obj("ok", true, "dryRun", true);
		out.putAll(report);
		return out;
	}

	public record RecoveryRequest(List<String> requeue, List<String> close, Boolean confirm) {
	}

	/** { requeue?: [jobId], close?: [jobId], confirm: true }: ejecuta solo esos trabajos tras revalidar su categoría. */
	@PostMapping("/api/admin/integration-jobs/recovery")
	public ResponseEntity<Map<String, Object>> recoveryExecute(@RequestBody(required = false) RecoveryRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		if (body == null || !Boolean.TRUE.equals(body.confirm()) || (body.requeue() != null && body.requeue().size() > 50)
				|| (body.close() != null && body.close().size() > 200)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
					"Envía { requeue?: string[], close?: string[], confirm: true } con IDs revisados en el reporte (GET).");
		}
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(recovery.execute(user.companyId(), body.requeue(), body.close(), user.email()));
		return ResponseEntity.ok(out);
	}
}

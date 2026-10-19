package com.c21inova.api.webhook;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.c21inova.api.common.Json;
import com.c21inova.api.common.Secrets;
import com.c21inova.api.config.Env;

import jakarta.servlet.http.HttpServletRequest;

/** Secretos compartidos de webhooks (x-inova-secret) y crons (CRON_SECRET). */
@Component
public class IntegrationSecrets {

	private final Env env;

	public IntegrationSecrets(Env env) {
		this.env = env;
	}

	/** null si pasa; si no, la respuesta lista para devolver. */
	public ResponseEntity<Map<String, Object>> checkIntegration(HttpServletRequest request) {
		String expected = env.optional("INTEGRATION_SECRET");
		if (expected == null) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Json.obj("ok", false, "error",
					Json.obj("code", "SERVER_MISCONFIGURED", "message", "INTEGRATION_SECRET no está configurado en el servidor.")));
		}
		if (!Secrets.matches(request.getHeader("x-inova-secret"), expected)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Json.obj("ok", false, "error", Json.obj("code", "UNAUTHORIZED", "message", "Secreto inválido.")));
		}
		return null;
	}

	/** "Authorization: Bearer <CRON_SECRET>", "x-cron-secret" o "?secret=" (para cron-job.org u otros). */
	public ResponseEntity<Map<String, Object>> checkCron(HttpServletRequest request) {
		String expected = env.optional("CRON_SECRET");
		if (expected == null) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Json.obj("ok", false, "error", "CRON_SECRET no está configurado."));
		String header = request.getHeader("authorization");
		String provided = header != null && header.toLowerCase().startsWith("bearer ") ? header.substring(7) : null;
		if (provided == null) provided = request.getHeader("x-cron-secret");
		if (provided == null) provided = request.getParameter("secret");
		if (!Secrets.matches(provided, expected)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Json.obj("ok", false, "error", "Secreto inválido."));
		return null;
	}
}

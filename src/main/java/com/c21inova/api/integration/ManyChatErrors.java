package com.c21inova.api.integration;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.c21inova.api.common.Json;

/**
 * Errores de ManyChat y su clasificación: decide si un fallo se reintenta
 * solo, si requiere revisión humana o si el resultado es incierto. Se basa
 * en el HTTP y el cuerpo ({status:"error", message, details}); lo que no se
 * reconoce queda REJECTED con el mensaje del proveedor visible.
 */
public final class ManyChatErrors {

	private static final int MAX_REASON = 300;

	private ManyChatErrors() {
	}

	public static class ManyChatApiException extends RuntimeException {
		public final int status;
		public final String statusText;
		public final Object body;
		public final String endpoint;
		public final Long retryAfterMs;

		public ManyChatApiException(int status, String statusText, Object body, String endpoint, Long retryAfterMs) {
			super(buildMessage(status, statusText, body, endpoint));
			this.status = status;
			this.statusText = statusText;
			this.body = body;
			this.endpoint = endpoint;
			this.retryAfterMs = retryAfterMs;
		}

		private static String buildMessage(int status, String statusText, Object body, String endpoint) {
			String providerMessage = providerMessage(body);
			return "ManyChat API error " + status + " " + (statusText == null ? "" : statusText) + (endpoint != null ? " en " + endpoint : "")
					+ (providerMessage != null ? ": " + providerMessage : "");
		}
	}

	/** La petición salió pero no hubo respuesta a tiempo: ManyChat pudo haberla aceptado. */
	public static class ManyChatTimeoutException extends RuntimeException {
		public final String path;

		public ManyChatTimeoutException(String path, long timeoutMs) {
			super("[MANYCHAT] Timeout tras " + timeoutMs + "ms en " + path);
			this.path = path;
		}
	}

	/** Falló la conexión (DNS, reset…): no se sabe si la petición llegó. */
	public static class ManyChatNetworkException extends RuntimeException {
		public final String path;

		public ManyChatNetworkException(String path, Throwable cause) {
			super("[MANYCHAT] Error de red en " + path + ": " + (cause.getMessage() != null ? cause.getMessage() : cause.toString()), cause);
			this.path = path;
		}
	}

	/** Falta configuración local indispensable (no se llamó a ManyChat). */
	public static class ManyChatConfigException extends RuntimeException {
		public final String missing;

		public ManyChatConfigException(String missing, String message) {
			super(message);
			this.missing = missing;
		}
	}

	/**
	 * kind: CONFIG, AUTH, FLOW, CONTACT, CHANNEL, RATE_LIMIT, PROVIDER, TIMEOUT,
	 * NETWORK, REJECTED, LOCAL.
	 */
	public record Failure(String kind, boolean retryable, boolean uncertain, String code, String reason, Long retryAfterMs) {
	}

	/** Mensaje del cuerpo de error de ManyChat, recortado y sin datos sensibles. */
	public static String providerMessage(Object body) {
		if (body == null) return null;
		if (body instanceof String text) return sanitize(text);
		Map<String, Object> record = Json.asMap(body);
		if (record == null) return null;
		List<String> parts = new ArrayList<>();
		Object message = record.get("message");
		if (message instanceof String m && !m.trim().isEmpty()) parts.add(m.trim());
		Object details = record.get("details");
		if (details != null && (details instanceof Map || details instanceof List)) {
			Map<String, Object> detailsMap = Json.asMap(details);
			Object messages = detailsMap != null ? detailsMap.get("messages") : null;
			List<Object> list = messages instanceof List<?> ? Json.asList(messages) : details instanceof List<?> ? Json.asList(details) : null;
			if (list != null) {
				for (Object item : list) {
					String text = item instanceof String s ? s : Json.str(Json.asMap(item), "message");
					if (text != null) parts.add(text);
				}
			} else if (detailsMap != null) {
				for (Map.Entry<String, Object> entry : detailsMap.entrySet()) {
					if (entry.getValue() instanceof String s) parts.add(entry.getKey() + ": " + s);
					else if (entry.getValue() instanceof List<?> values) {
						parts.add(entry.getKey() + ": " + String.join(", ", values.stream().filter(v -> v instanceof String).map(Object::toString).toList()));
					}
				}
			}
		}
		return parts.isEmpty() ? null : sanitize(String.join(" | ", parts));
	}

	/** Quita lo que parezca token/clave y números largos (teléfonos) antes de mostrar. */
	public static String sanitize(String text) {
		String out = text.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [oculto]")
				.replaceAll("\\b\\d{4,}:[A-Za-z0-9_-]{8,}\\b", "[oculto]")
				.replaceAll("\\+?\\d[\\d\\s-]{9,}\\d", "[número]")
				.replaceAll("\\s+", " ")
				.trim();
		return out.length() > MAX_REASON ? out.substring(0, MAX_REASON) : out;
	}

	private static final Pattern CHANNEL = Pattern.compile(
			"24[\\s-]*(h|hour|hora)|window|ventana|template|plantilla|message[_\\s]?tag|outside|opt[\\s-]?in|not\\s+subscribed|unsubscribed|channel|canal|whatsapp.*(disabled|not\\s+connected)|can'?t\\s+be\\s+sent|cannot\\s+send",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern FLOW = Pattern.compile("flow|flujo|content\\d|flow_ns", Pattern.CASE_INSENSITIVE);
	private static final Pattern CONTACT = Pattern.compile("subscriber|suscriptor|contact|user\\s+not\\s+found|blocked|bloque", Pattern.CASE_INSENSITIVE);
	private static final Pattern AUTH = Pattern.compile("token|unauthori[sz]ed|forbidden|permission|access\\s+denied|api\\s+key", Pattern.CASE_INSENSITIVE);

	/**
	 * `idempotent`: la operación puede repetirse sin efectos visibles (escribir
	 * Custom Fields). Para envíos, un timeout o caída de red es incierto.
	 */
	public static Failure classify(Throwable error, boolean idempotent) {
		if (error instanceof ManyChatConfigException config) {
			return new Failure("CONFIG", false, false, "CONFIG_" + config.missing, config.getMessage(), null);
		}
		if (error instanceof ManyChatTimeoutException) {
			return idempotent ? new Failure("TIMEOUT", true, false, "TIMEOUT", "ManyChat no respondió a tiempo.", null)
					: new Failure("TIMEOUT", false, true, "TIMEOUT", "ManyChat no respondió a tiempo: el envío pudo haberse ejecutado.", null);
		}
		if (error instanceof ManyChatNetworkException) {
			return idempotent ? new Failure("NETWORK", true, false, "NETWORK", "Falló la conexión con ManyChat.", null)
					: new Failure("NETWORK", false, true, "NETWORK", "Se perdió la conexión con ManyChat durante el envío: pudo haberse ejecutado.", null);
		}
		if (error instanceof ManyChatApiException api) {
			String provider = providerMessage(api.body);
			int http = api.status;
			boolean bodyError = http >= 200 && http < 300;
			String code = bodyError ? "BODY_ERROR_" + http : "HTTP_" + http;
			String reason = provider != null ? provider
					: bodyError ? "ManyChat respondió con status \"error\" sin detalle." : "ManyChat respondió HTTP " + http + " sin detalle en el cuerpo.";
			Long retryAfter = api.retryAfterMs;
			if (http == 429) return new Failure("RATE_LIMIT", true, false, code, reason, retryAfter);
			if (http >= 500) return new Failure("PROVIDER", true, false, code, reason, retryAfter);
			if (http == 401 || http == 403) return new Failure("AUTH", false, false, code, reason, retryAfter);
			String text = provider == null ? "" : provider;
			// El orden importa: "flow can't be sent outside the 24h window" es del canal, no del flujo.
			if (CHANNEL.matcher(text).find()) return new Failure("CHANNEL", false, false, code, reason, retryAfter);
			if (AUTH.matcher(text).find()) return new Failure("AUTH", false, false, code, reason, retryAfter);
			if (FLOW.matcher(text).find()) return new Failure("FLOW", false, false, code, reason, retryAfter);
			if (CONTACT.matcher(text).find()) return new Failure("CONTACT", false, false, code, reason, retryAfter);
			return new Failure("REJECTED", false, false, code, reason, retryAfter);
		}
		String message = error.getMessage() != null ? error.getMessage() : error.toString();
		return new Failure("LOCAL", false, false, "LOCAL", sanitize(message.isEmpty() ? "Error desconocido" : message), null);
	}

	/** Retry-After: segundos o fecha HTTP. */
	public static Long parseRetryAfter(String value, Instant now) {
		if (value == null || value.isBlank()) return null;
		try {
			double seconds = Double.parseDouble(value.trim());
			if (seconds >= 0) return Math.round(seconds * 1000);
		} catch (NumberFormatException ignored) {
			// Puede ser fecha HTTP.
		}
		try {
			Instant date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			return Math.max(0, date.toEpochMilli() - now.toEpochMilli());
		} catch (RuntimeException ignored) {
			return null;
		}
	}
}

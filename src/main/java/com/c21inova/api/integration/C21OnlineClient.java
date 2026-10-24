package com.c21inova.api.integration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;

/**
 * API Externa de 21Online (Genio Inmobiliario), formato RESO. Autenticación
 * OAuth2 client_credentials con la credencial que se crea en 21Online →
 * Administración y Config → API Externa → Credenciales API. El token se
 * guarda en memoria y se renueva antes de vencer. Nunca registra secretos.
 */
@Component
public class C21OnlineClient {

	private static final String DEFAULT_BASE_URL = "https://api.21online.lat";
	private static final long TIMEOUT_MS = 30_000;

	public static class C21ApiException extends RuntimeException {
		public final int status;
		public final Object body;

		public C21ApiException(int status, Object body) {
			super("21Online API error " + status + ": " + summarize(body));
			this.status = status;
			this.body = body;
		}

		/** Mensaje legible de los errores de API Platform (detail / violations) o del envelope {error:{message}}. */
		static String summarize(Object body) {
			Map<String, Object> map = Json.asMap(body);
			if (map == null) return body == null ? "" : truncate(body.toString(), 400);
			StringJoiner parts = new StringJoiner("; ");
			String detail = Json.str(map, "detail");
			if (detail != null) parts.add(detail);
			for (Map<String, Object> violation : Json.objects(map.get("violations"))) {
				parts.add(Json.str(violation, "propertyPath") + ": " + Json.str(violation, "message"));
			}
			Map<String, Object> error = Json.asMap(map.get("error"));
			if (error != null && Json.str(error, "message") != null) parts.add(Json.str(error, "message"));
			if (Json.str(map, "error_description") != null) parts.add(Json.str(map, "error_description"));
			String text = parts.toString();
			return truncate(text.isEmpty() ? Json.write(map) : text, 400);
		}

		private static String truncate(String text, int max) {
			return text.length() > max ? text.substring(0, max) : text;
		}
	}

	private final HttpClient http;
	private final Env env;
	private String accessToken;
	private Instant tokenExpiresAt = Instant.EPOCH;

	public C21OnlineClient(HttpClient http, Env env) {
		this.http = http;
		this.env = env;
	}

	public boolean configured() {
		return env.optional("C21_CLIENT_ID") != null && env.optional("C21_CLIENT_SECRET") != null;
	}

	private String baseUrl() {
		String override = env.optional("C21_API_BASE_URL");
		return override != null ? override.replaceAll("/$", "") : DEFAULT_BASE_URL;
	}

	private HttpResponse<String> send(HttpRequest request, String label) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (HttpTimeoutException e) {
			throw new IllegalStateException("[21ONLINE] Timeout en " + label, e);
		} catch (IOException e) {
			throw new IllegalStateException("[21ONLINE] Error de red en " + label + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("[21ONLINE] Interrumpido", e);
		}
	}

	private static String form(Map<String, String> fields) {
		StringJoiner joiner = new StringJoiner("&");
		for (Map.Entry<String, String> field : fields.entrySet()) {
			if (field.getValue() == null) continue;
			joiner.add(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
		}
		return joiner.toString();
	}

	private synchronized String token() {
		if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) return accessToken;
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("grant_type", "client_credentials");
		fields.put("client_id", env.require("C21_CLIENT_ID"));
		fields.put("client_secret", env.require("C21_CLIENT_SECRET"));
		fields.put("scope", env.optional("C21_SCOPE"));
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/oauth2/token")).timeout(Duration.ofMillis(TIMEOUT_MS))
				.header("Content-Type", "application/x-www-form-urlencoded").header("accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
		HttpResponse<String> response = send(request, "POST /oauth2/token");
		Object data = HttpClients.parseBody(response.body());
		if (response.statusCode() < 200 || response.statusCode() >= 300) throw new C21ApiException(response.statusCode(), data);
		Map<String, Object> body = Json.asMap(data);
		String token = Json.str(body, "access_token");
		if (token == null) throw new C21ApiException(response.statusCode(), "Respuesta de token sin access_token");
		Integer expiresIn = Json.integer(body, "expires_in");
		accessToken = token;
		tokenExpiresAt = Instant.now().plusSeconds(expiresIn != null ? expiresIn : 3600);
		return accessToken;
	}

	/** path relativo a la base (/api/v1/external/...) o URL absoluta (nextLink de la paginación). */
	public Object request(String method, String path, Object body) {
		String url = path.startsWith("http") ? path : baseUrl() + path;
		for (int attempt = 1;; attempt++) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(TIMEOUT_MS))
					.header("Authorization", "Bearer " + token()).header("accept", "application/json");
			if (body != null) {
				String contentType = "PATCH".equals(method) ? "application/merge-patch+json" : "application/json";
				builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
			} else {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}
			HttpResponse<String> response = send(builder.build(), method + " " + path);
			Object data = HttpClients.parseBody(response.body());
			if (response.statusCode() == 401 && attempt == 1) {
				// Token revocado o vencido antes de tiempo: se pide uno nuevo una vez.
				synchronized (this) {
					accessToken = null;
				}
				continue;
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) throw new C21ApiException(response.statusCode(), data);
			return data;
		}
	}
}

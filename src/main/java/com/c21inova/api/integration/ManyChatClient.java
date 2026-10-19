package com.c21inova.api.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.ManyChatErrors.ManyChatApiException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatNetworkException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatTimeoutException;

/** Nunca registra MANYCHAT_API_KEY ni el header Authorization. */
@Component
public class ManyChatClient {

	private static final String DEFAULT_BASE_URL = "https://api.manychat.com";
	public static final long DEFAULT_TIMEOUT_MS = 10_000;

	private final HttpClient http;
	private final Env env;

	public ManyChatClient(HttpClient http, Env env) {
		this.http = http;
		this.env = env;
	}

	/** MANYCHAT_BASE_URL solo para pruebas o un proxy; por defecto la API oficial. */
	private String baseUrl() {
		String override = env.optional("MANYCHAT_BASE_URL");
		return override != null ? override.replaceAll("/$", "") : DEFAULT_BASE_URL;
	}

	public Object post(String path, Object body, long timeoutMs) {
		return request("POST", path, body, null, timeoutMs);
	}

	public Object get(String path, Map<String, String> query) {
		return request("GET", path, null, query, DEFAULT_TIMEOUT_MS);
	}

	private Object request(String method, String path, Object body, Map<String, String> query, long timeoutMs) {
		String endpoint = method + " " + path;
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path + HttpClients.query(query)))
				.timeout(Duration.ofMillis(timeoutMs))
				.header("Authorization", "Bearer " + env.manyChatApiKey());
		if ("POST".equals(method)) {
			builder.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(Json.write(body == null ? Map.of() : body)));
		} else {
			builder.GET();
		}
		HttpResponse<String> response;
		try {
			response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (HttpTimeoutException e) {
			throw new ManyChatTimeoutException(endpoint, timeoutMs);
		} catch (IOException e) {
			// No se sabe si la petición llegó.
			throw new ManyChatNetworkException(endpoint, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ManyChatNetworkException(endpoint, e);
		}
		Object data = HttpClients.parseBody(response.body());
		// ManyChat a veces responde HTTP 200 con {"status":"error"}: no es éxito.
		Object bodyStatus = Json.asMap(data) != null ? Json.asMap(data).get("status") : null;
		boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
		if (!ok || "error".equals(bodyStatus)) {
			Long retryAfter = ManyChatErrors.parseRetryAfter(response.headers().firstValue("retry-after").orElse(null), Instant.now());
			throw new ManyChatApiException(response.statusCode(), "", data, endpoint, retryAfter);
		}
		return data;
	}
}

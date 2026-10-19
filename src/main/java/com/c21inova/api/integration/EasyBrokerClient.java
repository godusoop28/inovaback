package com.c21inova.api.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;

/** URL base y header de autenticación de EasyBroker en un solo lugar. Nunca registra la clave. */
@Component
public class EasyBrokerClient {

	private static final String DEFAULT_BASE_URL = "https://api.easybroker.com/v1";
	private static final long DEFAULT_TIMEOUT_MS = 15_000;

	public static class EasyBrokerApiException extends RuntimeException {
		public final int status;
		public final Object body;

		public EasyBrokerApiException(int status, Object body) {
			super("EasyBroker API error " + status);
			this.status = status;
			this.body = body;
		}
	}

	private final HttpClient http;
	private final Env env;

	public EasyBrokerClient(HttpClient http, Env env) {
		this.http = http;
		this.env = env;
	}

	/** EASYBROKER_BASE_URL solo para pruebas o un proxy; por defecto la API oficial. */
	private String baseUrl() {
		String override = env.optional("EASYBROKER_BASE_URL");
		return override != null ? override.replaceAll("/$", "") : DEFAULT_BASE_URL;
	}

	public Object request(String method, String path, Map<String, ?> query, Object body) {
		return request(method, path, query, body, DEFAULT_TIMEOUT_MS);
	}

	public Object request(String method, String path, Map<String, ?> query, Object body, long timeoutMs) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path + HttpClients.query(query)))
				.timeout(Duration.ofMillis(timeoutMs))
				.header("X-Authorization", env.easyBrokerApiKey())
				.header("accept", "application/json");
		if (body != null) {
			builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		HttpResponse<String> response;
		try {
			response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (HttpTimeoutException e) {
			throw new IllegalStateException("[EASYBROKER] Timeout tras " + timeoutMs + "ms en " + method + " " + path, e);
		} catch (IOException e) {
			throw new IllegalStateException("[EASYBROKER] Error de red en " + method + " " + path + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("[EASYBROKER] Interrumpido", e);
		}
		Object data = HttpClients.parseBody(response.body());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new EasyBrokerApiException(response.statusCode(), data);
		}
		return data;
	}
}

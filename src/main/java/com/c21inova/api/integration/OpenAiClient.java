package com.c21inova.api.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;

/** Llamadas directas a OpenAI sin SDK. Nunca registra la clave. */
@Component
public class OpenAiClient {

	private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	private static final long DEFAULT_TIMEOUT_MS = 30_000;

	public static class OpenAiApiException extends RuntimeException {
		public final int status;
		public final Object body;

		public OpenAiApiException(int status, Object body) {
			super("OpenAI API error " + status);
			this.status = status;
			this.body = body;
		}
	}

	public record ResponsesResult(String id, String status, List<Map<String, Object>> output, String outputText, Integer inputTokens,
			Integer outputTokens, String incompleteReason) {
	}

	private final HttpClient http;
	private final Env env;

	public OpenAiClient(HttpClient http, Env env) {
		this.http = http;
		this.env = env;
	}

	/** OPENAI_BASE_URL solo para pruebas o un proxy; por defecto la API oficial. */
	private String baseUrl() {
		String override = env.optional("OPENAI_BASE_URL");
		return override != null ? override.replaceAll("/$", "") : DEFAULT_BASE_URL;
	}

	private Object post(String path, Map<String, Object> body, long timeoutMs) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.timeout(Duration.ofMillis(timeoutMs))
				.header("Authorization", "Bearer " + env.openAiApiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
				.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (HttpTimeoutException e) {
			throw new IllegalStateException("[OPENAI] Timeout tras " + timeoutMs + "ms.", e);
		} catch (IOException e) {
			throw new IllegalStateException("[OPENAI] Error de red: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("[OPENAI] Interrumpido", e);
		}
		Object data = HttpClients.parseBody(response.body());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			Object safeBody = data instanceof String text ? (text.length() > 500 ? text.substring(0, 500) : text) : data;
			throw new OpenAiApiException(response.statusCode(), safeBody);
		}
		return data;
	}

	/** Chat Completions en modo JSON (búsqueda de propiedades para ManyChat). */
	public String jsonCompletion(String model, String systemPrompt, String userPrompt, long timeoutMs) {
		List<Map<String, Object>> messages = new ArrayList<>();
		if (systemPrompt != null) messages.add(Json.obj("role", "system", "content", systemPrompt));
		messages.add(Json.obj("role", "user", "content", userPrompt));
		Object data = post("/chat/completions", Json.obj("model", model, "response_format", Json.obj("type", "json_object"), "messages", messages),
				timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
		List<Map<String, Object>> choices = Json.objects(Json.asMap(data) == null ? null : Json.asMap(data).get("choices"));
		Map<String, Object> message = choices.isEmpty() ? null : Json.asMap(choices.get(0).get("message"));
		String content = Json.str(message, "content");
		if (content == null) throw new IllegalStateException("[OPENAI] Respuesta sin contenido de texto utilizable.");
		return content;
	}

	/**
	 * Responses API (herramientas + salida JSON Schema estricta). store=false:
	 * OpenAI no conserva la conversación; el razonamiento cifrado se reenvía
	 * dentro del mismo turno y nunca se guarda en la base.
	 */
	public ResponsesResult responses(String model, String instructions, List<Map<String, Object>> input, List<Map<String, Object>> tools,
			String toolChoice, String formatName, Object schema, int maxOutputTokens, String reasoningEffort, long timeoutMs) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("store", false);
		body.put("include", List.of("reasoning.encrypted_content"));
		body.put("instructions", instructions);
		body.put("input", input);
		if (tools != null && !tools.isEmpty()) {
			body.put("tools", tools);
			body.put("parallel_tool_calls", true);
			body.put("tool_choice", toolChoice != null ? toolChoice : "auto");
		}
		if (formatName != null) {
			body.put("text", Json.obj("format", Json.obj("type", "json_schema", "name", formatName, "strict", true, "schema", schema)));
		}
		if (reasoningEffort != null) body.put("reasoning", Json.obj("effort", reasoningEffort));
		body.put("max_output_tokens", maxOutputTokens > 0 ? maxOutputTokens : 2000);

		Map<String, Object> data = Json.asMap(post("/responses", body, timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS));
		if (data == null) throw new IllegalStateException("[OPENAI] Respuesta vacía de /responses.");

		List<Map<String, Object>> output = Json.objects(data.get("output"));
		StringBuilder outputText = new StringBuilder();
		for (Map<String, Object> item : output) {
			if (!"message".equals(item.get("type"))) continue;
			for (Map<String, Object> part : Json.objects(item.get("content"))) {
				if ("output_text".equals(part.get("type")) && part.get("text") instanceof String text) outputText.append(text);
			}
		}
		Map<String, Object> usage = Json.asMap(data.get("usage"));
		Map<String, Object> incomplete = Json.asMap(data.get("incomplete_details"));
		return new ResponsesResult(
				String.valueOf(data.getOrDefault("id", "")),
				String.valueOf(data.getOrDefault("status", "")),
				output,
				outputText.isEmpty() ? null : outputText.toString(),
				Json.integer(usage, "input_tokens"),
				Json.integer(usage, "output_tokens"),
				Json.str(incomplete, "reason"));
	}
}

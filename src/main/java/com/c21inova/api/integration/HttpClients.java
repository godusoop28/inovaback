package com.c21inova.api.integration;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.c21inova.api.common.Json;

@Configuration
public class HttpClients {

	/** Cliente compartido para EasyBroker, ManyChat y OpenAI (sigue redirecciones solo en GET seguros). */
	@Bean
	HttpClient httpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
	}

	static String query(Map<String, ?> params) {
		if (params == null || params.isEmpty()) return "";
		StringJoiner joiner = new StringJoiner("&", "?", "");
		for (Map.Entry<String, ?> entry : params.entrySet()) {
			if (entry.getValue() == null) continue;
			joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
		}
		return joiner.toString();
	}

	/** JSON del cuerpo, o el texto tal cual si no es JSON. */
	static Object parseBody(String text) {
		if (text == null || text.isEmpty()) return null;
		try {
			return Json.parse(text);
		} catch (RuntimeException e) {
			return text;
		}
	}
}

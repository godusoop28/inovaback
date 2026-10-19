package com.c21inova.api.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON como Map/List de Java: las columnas JSONB (facts, properties,
 * payload…) tienen formas flexibles heredadas del panel en TypeScript y se
 * leen igual que allá, sin clases rígidas que rompan con datos viejos.
 */
public final class Json {

	private static final JsonMapper MAPPER = JsonMapper.builder().addModule(com.c21inova.api.config.JacksonConfig.instantModule()).build();

	private Json() {
	}

	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	public static Object parse(String text) {
		if (text == null) return null;
		return MAPPER.readValue(text, Object.class);
	}

	public static Map<String, Object> parseObject(String text) {
		if (text == null || text.isBlank()) return null;
		return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
		});
	}

	public static JsonMapper mapper() {
		return MAPPER;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object value) {
		return value instanceof List<?> list ? (List<Object>) list : null;
	}

	public static String str(Map<String, Object> map, String key) {
		if (map == null) return null;
		Object value = map.get(key);
		return value instanceof String s ? s : null;
	}

	public static Integer integer(Map<String, Object> map, String key) {
		if (map == null) return null;
		Object value = map.get(key);
		if (value instanceof Number n) return n.intValue();
		return null;
	}

	public static Double number(Map<String, Object> map, String key) {
		if (map == null) return null;
		Object value = map.get(key);
		if (value instanceof Number n) return n.doubleValue();
		return null;
	}

	public static Boolean bool(Map<String, Object> map, String key) {
		if (map == null) return null;
		Object value = map.get(key);
		return value instanceof Boolean b ? b : null;
	}

	/** Lista de objetos (ignora lo que no sea objeto). */
	public static List<Map<String, Object>> objects(Object value) {
		List<Map<String, Object>> out = new ArrayList<>();
		List<Object> list = asList(value);
		if (list == null) return out;
		for (Object item : list) {
			Map<String, Object> map = asMap(item);
			if (map != null) out.add(map);
		}
		return out;
	}

	/** Map mutable que conserva el orden de inserción (para respuestas JSON legibles). */
	public static Map<String, Object> obj(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}
}

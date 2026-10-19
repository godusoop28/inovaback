package com.c21inova.api.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.c21inova.api.common.Brand;
import com.c21inova.api.common.Json;

/**
 * Contrato entre el backend y el modelo: herramientas (JSON Schema estricto
 * de OpenAI) y la validación de TODO lo que el modelo devuelve. Puro.
 */
public final class AssistantContract {

	private AssistantContract() {
	}

	private static final List<String> FACT_STATUS = List.of("known", "declined", "unknown");

	public static final List<String> HANDOFF_INTENTS = List.of("PROPERTY_INQUIRY", "BUY", "RENT", "SELL", "LEASE_OUT", "INVEST", "AGENT_COLLABORATION");

	public static final List<String> TOOL_NAMES = List.of("search_properties", "get_property", "resolve_link", "check_assignment",
			"request_commercial_handoff", "request_management", "request_human");

	private static final List<String> MANAGEMENT_CATEGORIES = List.of("provider", "management", "agent_collaboration", "complaint", "other");

	public static final Map<String, Object> FINAL_OUTPUT_JSON_SCHEMA = finalOutputSchema();

	private static Map<String, Object> finalOutputSchema() {
		Map<String, Object> factSchema = Json.obj("type", "object", "properties",
				Json.obj("value", Json.obj("type", List.of("string", "null")), "status", Json.obj("type", "string", "enum", FACT_STATUS)), "required",
				List.of("value", "status"), "additionalProperties", false);
		Map<String, Object> factProperties = new LinkedHashMap<>();
		for (String key : Policy.FACT_KEYS) factProperties.put(key, factSchema);
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("reply", Json.obj("type", "string", "description", "Mensaje para el cliente por WhatsApp. Vacío solo si no corresponde responder."));
		properties.put("primary_intent", Json.obj("type", "string", "enum", Policy.CONVERSATION_INTENTS));
		properties.put("secondary_intents", Json.obj("type", "array", "items", Json.obj("type", "string", "enum", Policy.CONVERSATION_INTENTS)));
		properties.put("facts", Json.obj("type", "object", "properties", factProperties, "required", Policy.FACT_KEYS, "additionalProperties", false));
		properties.put("property_ids", Json.obj("type", "array", "items", Json.obj("type", "string"), "description",
				"Códigos EB- de las propiedades por las que el cliente pregunta o que confirmó, SOLO si una herramienta los verificó. No incluyas opciones que solo mostraste."));
		properties.put("summary", Json.obj("type", "string", "description", "Resumen breve y factual de toda la conversación para el asesor (máx. 3 frases)."));
		properties.put("asked_clarification", Json.obj("type", "boolean", "description", "true si la respuesta pide al cliente aclarar o completar algo."));
		properties.put("made_progress", Json.obj("type", "boolean", "description", "true si este turno aportó información nueva útil o resolvió algo."));
		return Json.obj("type", "object", "properties", properties, "required",
				List.of("reply", "primary_intent", "secondary_intents", "facts", "property_ids", "summary", "asked_clarification", "made_progress"),
				"additionalProperties", false);
	}

	private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
		return Json.obj("type", "function", "name", name, "description", description, "strict", true, "parameters",
				Json.obj("type", "object", "properties", properties, "required", required, "additionalProperties", false));
	}

	public static final List<Map<String, Object>> OPENAI_TOOLS = List.of(
			tool("search_properties", "Busca en el inventario publicado de " + Brand.BRAND_NAME
					+ " (EasyBroker) por texto: título, colonia, zona, tipo. Devuelve hasta 5 candidatos. Úsala para identificar una propiedad concreta o mostrar opciones reales. Los resultados son candidatas: no afirmes cuál vio el cliente sin que lo confirme.",
					Json.obj("query", Json.obj("type", "string", "description", "Lo que describe el cliente: nombre, colonia, zona, tipo, características."),
							"operation", Json.obj("type", "string", "enum", List.of("sale", "rental", "any"))),
					List.of("query", "operation")),
			tool("get_property",
					"Consulta el detalle vigente de una propiedad en EasyBroker por su código (EB-XXXX). Obligatorio antes de afirmar precio, disponibilidad o características.",
					Json.obj("public_id", Json.obj("type", "string", "description", "Código EasyBroker, p. ej. EB-AB1234")), List.of("public_id")),
			tool("resolve_link",
					"Identifica la propiedad de un enlace que envió el cliente: EasyBroker, Mercado Libre, Vivanuncios, Inmuebles24, Lamudi, Facebook u otro portal. Primero busca coincidencia exacta (código EB-, enlace de EasyBroker, anuncio publicado reportado por EasyBroker); si no, lee el anuncio cuando el portal lo permite y lo cruza con el inventario. identified=true solo con coincidencia exacta; si no, devuelve candidatas que el cliente debe confirmar o que no está en inventario.",
					Json.obj("url", Json.obj("type", "string")), List.of("url")),
			tool("check_assignment", "Indica si este contacto ya tiene un asesor asignado o una canalización registrada.", Json.obj(), List.of()),
			tool("request_commercial_handoff",
					"Solicita canalizar al cliente inmobiliario con un asesor usando las reglas de asignación de la empresa (el sistema elige al asesor, no tú). Úsala cuando la propiedad está identificada, o cuando ya sabes qué busca/ofrece y tiene sentido que lo atienda un asesor, o si pide hablar con alguien. El resultado dice qué ocurrió realmente.",
					Json.obj("intent", Json.obj("type", "string", "enum", HANDOFF_INTENTS, "description", "Necesidad inmobiliaria principal del cliente."),
							"reason", Json.obj("type", "string", "description", "Motivo breve del contacto para el asesor."),
							"property_public_id", Json.obj("type", List.of("string", "null"), "description", "Código EB- verificado, o null.")),
					List.of("intent", "reason", "property_public_id")),
			tool("request_management",
					"Registra un asunto para gerencia/administración: proveedores, colaboraciones, quejas u otros temas que no son de un cliente inmobiliario. Nunca asigna asesor comercial.",
					Json.obj("category", Json.obj("type", "string", "enum", MANAGEMENT_CATEGORIES), "reason", Json.obj("type", "string")),
					List.of("category", "reason")),
			tool("request_human",
					"El cliente pide hablar con una persona o la conversación no avanza: registra la solicitud para que alguien del equipo atienda y deja de responder la IA.",
					Json.obj("reason", Json.obj("type", "string")), List.of("reason")));

	// --- Validación (equivalente a los esquemas Zod del panel) ------------------------------

	public record FinalOutput(String reply, String primaryIntent, List<String> secondaryIntents, Map<String, Object> facts, List<String> propertyIds,
			String summary, boolean askedClarification, boolean madeProgress) {

		public FinalOutput withReply(String newReply) {
			return new FinalOutput(newReply, primaryIntent, secondaryIntents, facts, propertyIds, summary, askedClarification, madeProgress);
		}
	}

	/** Lanza IllegalArgumentException si la salida no cumple el esquema. */
	public static FinalOutput parseFinalOutput(Object raw) {
		Map<String, Object> map = Json.asMap(raw);
		if (map == null) throw new IllegalArgumentException("no es objeto");
		String reply = requireString(map, "reply", 1500);
		String primary = requireEnum(map.get("primary_intent"), Policy.CONVERSATION_INTENTS);
		List<String> secondary = new ArrayList<>();
		List<Object> secondaryRaw = Json.asList(map.get("secondary_intents"));
		if (secondaryRaw == null || secondaryRaw.size() > 6) throw new IllegalArgumentException("secondary_intents");
		for (Object item : secondaryRaw) secondary.add(requireEnum(item, Policy.CONVERSATION_INTENTS));
		Map<String, Object> facts = Json.asMap(map.get("facts"));
		if (facts == null) throw new IllegalArgumentException("facts");
		Map<String, Object> cleanFacts = new LinkedHashMap<>();
		for (String key : Policy.FACT_KEYS) {
			Map<String, Object> fact = Json.asMap(facts.get(key));
			if (fact == null) throw new IllegalArgumentException("facts." + key);
			Object value = fact.get("value");
			if (value != null && !(value instanceof String s && s.length() <= 500)) throw new IllegalArgumentException("facts." + key + ".value");
			Map<String, Object> clean = new LinkedHashMap<>();
			clean.put("value", value);
			clean.put("status", requireEnum(fact.get("status"), FACT_STATUS));
			cleanFacts.put(key, clean);
		}
		List<String> propertyIds = new ArrayList<>();
		List<Object> idsRaw = Json.asList(map.get("property_ids"));
		if (idsRaw == null || idsRaw.size() > 10) throw new IllegalArgumentException("property_ids");
		for (Object id : idsRaw) {
			if (!(id instanceof String s) || s.length() > 40) throw new IllegalArgumentException("property_ids");
			propertyIds.add(s);
		}
		String summary = requireString(map, "summary", 1200);
		if (!(map.get("asked_clarification") instanceof Boolean asked) || !(map.get("made_progress") instanceof Boolean progress)) {
			throw new IllegalArgumentException("booleans");
		}
		return new FinalOutput(reply, primary, secondary, cleanFacts, propertyIds, summary, asked, progress);
	}

	private static String requireString(Map<String, Object> map, String key, int max) {
		if (!(map.get(key) instanceof String value) || value.length() > max) throw new IllegalArgumentException(key);
		return value;
	}

	private static String requireEnum(Object value, List<String> allowed) {
		if (!(value instanceof String s) || !allowed.contains(s)) throw new IllegalArgumentException("enum");
		return s;
	}

	/** Argumentos de herramienta validados (strings recortados) o null si no cumplen. */
	public static Map<String, Object> parseToolArgs(String name, Object raw) {
		Map<String, Object> args = Json.asMap(raw);
		if (args == null) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		switch (name) {
			case "search_properties" -> {
				String query = trimmed(args.get("query"), 1, 300);
				Object operation = args.get("operation");
				if (query == null || !(operation instanceof String op) || !List.of("sale", "rental", "any").contains(op)) return null;
				out.put("query", query);
				out.put("operation", op);
			}
			case "get_property" -> {
				String id = trimmed(args.get("public_id"), 3, 40);
				if (id == null) return null;
				out.put("public_id", id);
			}
			case "resolve_link" -> {
				String url = trimmed(args.get("url"), 8, 1000);
				if (url == null) return null;
				out.put("url", url);
			}
			case "check_assignment" -> {
				// Sin argumentos.
			}
			case "request_commercial_handoff" -> {
				Object intent = args.get("intent");
				String reason = trimmed(args.get("reason"), 3, 500);
				Object property = args.get("property_public_id");
				if (!(intent instanceof String i) || !HANDOFF_INTENTS.contains(i) || reason == null) return null;
				if (!args.containsKey("property_public_id")) return null;
				String propertyId = null;
				if (property != null) {
					propertyId = trimmed(property, 0, 40);
					if (propertyId == null) return null;
				}
				out.put("intent", i);
				out.put("reason", reason);
				out.put("property_public_id", propertyId);
			}
			case "request_management" -> {
				Object category = args.get("category");
				String reason = trimmed(args.get("reason"), 3, 500);
				if (!(category instanceof String c) || !MANAGEMENT_CATEGORIES.contains(c) || reason == null) return null;
				out.put("category", c);
				out.put("reason", reason);
			}
			case "request_human" -> {
				String reason = trimmed(args.get("reason"), 3, 500);
				if (reason == null) return null;
				out.put("reason", reason);
			}
			default -> {
				return null;
			}
		}
		return out;
	}

	private static String trimmed(Object value, int min, int max) {
		if (!(value instanceof String s)) return null;
		String trimmed = s.trim();
		return trimmed.length() < min || trimmed.length() > max ? null : trimmed;
	}
}

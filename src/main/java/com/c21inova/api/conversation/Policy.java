package com.c21inova.api.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.c21inova.api.common.Json;

/**
 * Reglas de negocio del asistente que NO dependen de la IA: qué intención
 * puede entrar a la asignación comercial y qué datos conviene pedir.
 * La IA propone; estas funciones deciden.
 */
public final class Policy {

	private Policy() {
	}

	public static final List<String> CONVERSATION_INTENTS = List.of("UNKNOWN", "PROPERTY_INQUIRY", "BUY", "RENT", "SELL", "LEASE_OUT", "INVEST",
			"AGENT_COLLABORATION", "PROVIDER", "MANAGEMENT", "HUMAN_REQUEST", "OTHER");

	public static final Map<String, String> INTENT_LABELS = Map.ofEntries(
			Map.entry("UNKNOWN", "Sin clasificar"),
			Map.entry("PROPERTY_INQUIRY", "Propiedad concreta"),
			Map.entry("BUY", "Comprar"),
			Map.entry("RENT", "Rentar"),
			Map.entry("SELL", "Vender inmueble propio"),
			Map.entry("LEASE_OUT", "Poner en renta inmueble propio"),
			Map.entry("INVEST", "Inversión"),
			Map.entry("AGENT_COLLABORATION", "Colaboración con asesor externo"),
			Map.entry("PROVIDER", "Proveedor / servicios"),
			Map.entry("MANAGEMENT", "Gerencia / administración"),
			Map.entry("HUMAN_REQUEST", "Pide atención humana"),
			Map.entry("OTHER", "Otro"));

	private static final Set<String> REAL_ESTATE_INTENTS = Set.of("PROPERTY_INQUIRY", "BUY", "RENT", "SELL", "LEASE_OUT", "INVEST");
	private static final Set<String> NON_COMMERCIAL_INTENTS = Set.of("PROVIDER", "MANAGEMENT");

	public static boolean isRealEstateIntent(String intent) {
		return REAL_ESTATE_INTENTS.contains(intent);
	}

	public static boolean hasRealEstateInterest(String primary, List<String> secondary) {
		return isRealEstateIntent(primary) || secondary.stream().anyMatch(Policy::isRealEstateIntent);
	}

	public static boolean isNonCommercial(String primary) {
		return NON_COMMERCIAL_INTENTS.contains(primary);
	}

	/** allowed → interesCliente (Propiedad/Campaña/Explorar/Timeout) + datosPropiedad; si no, reason. */
	public record HandoffDecision(boolean allowed, String interesCliente, String datosPropiedad, String reason) {
	}

	/**
	 * Traduce la intención a la entrada del motor de asignación existente sin
	 * inventar rutas: propiedad verificada → Propiedad (o Campaña si llegó con
	 * ese código); compra/renta/venta sin propiedad → Explorar; abandono →
	 * Timeout; proveedor/gerencia/desconocido → nunca.
	 */
	public static HandoffDecision decideCommercialHandoff(String primaryIntent, List<String> secondaryIntents, String verifiedPropertyId, String trigger,
			boolean fromCampaign) {
		if (isNonCommercial(primaryIntent) && secondaryIntents.stream().noneMatch(Policy::isRealEstateIntent)) {
			return new HandoffDecision(false, null, null, "Proveedor/gerencia no entra a la asignación comercial.");
		}
		if (verifiedPropertyId != null && (hasRealEstateInterest(primaryIntent, secondaryIntents) || "AGENT_COLLABORATION".equals(primaryIntent)
				|| "human_request".equals(trigger))) {
			return new HandoffDecision(true, fromCampaign ? "Campaña" : "Propiedad", verifiedPropertyId, null);
		}
		if (!hasRealEstateInterest(primaryIntent, secondaryIntents)) {
			return new HandoffDecision(false, null, null, "AGENT_COLLABORATION".equals(primaryIntent)
					? "Colaboración sin propiedad identificada: se canaliza a gerencia."
					: "Todavía no hay interés inmobiliario confirmado.");
		}
		return new HandoffDecision(true, "abandonment".equals(trigger) ? "Timeout" : "Explorar", "", null);
	}

	// --- Datos recopilados ---------------------------------------------------------

	public static final List<String> FACT_KEYS = List.of("name", "operation", "property_type", "zone", "budget_min", "budget_max", "currency",
			"bedrooms", "timeframe", "own_property_location", "financing", "heard_from", "notes");

	public static final Map<String, String> FACT_LABELS = Map.ofEntries(
			Map.entry("name", "Nombre"),
			Map.entry("operation", "Operación"),
			Map.entry("property_type", "Tipo de inmueble"),
			Map.entry("zone", "Zona"),
			Map.entry("budget_min", "Presupuesto mínimo"),
			Map.entry("budget_max", "Presupuesto máximo"),
			Map.entry("currency", "Moneda"),
			Map.entry("bedrooms", "Recámaras"),
			Map.entry("timeframe", "Plazo"),
			Map.entry("own_property_location", "Ubicación de su inmueble"),
			Map.entry("financing", "Forma de pago"),
			Map.entry("heard_from", "Dónde vio la propiedad / cómo nos conoció (dicho por el cliente)"),
			Map.entry("notes", "Notas"));

	/**
	 * Fusiona lo reportado en este turno con lo guardado: "known" con valor
	 * reemplaza, "declined" se respeta y "unknown" nunca borra un dato.
	 * Forma: { clave: { value, status } }.
	 */
	public static Map<String, Object> mergeFacts(Map<String, Object> previous, Map<String, Object> update) {
		Map<String, Object> merged = new LinkedHashMap<>(previous == null ? Map.of() : previous);
		if (update == null) return merged;
		for (String key : FACT_KEYS) {
			Map<String, Object> incoming = Json.asMap(update.get(key));
			if (incoming == null) continue;
			String status = Json.str(incoming, "status");
			if (status == null || "unknown".equals(status)) continue;
			if ("declined".equals(status)) {
				Map<String, Object> declined = new LinkedHashMap<>();
				declined.put("value", null);
				declined.put("status", "declined");
				merged.put(key, declined);
				continue;
			}
			String value = Json.str(incoming, "value");
			if (value != null && !value.trim().isEmpty()) {
				String trimmed = value.trim();
				merged.put(key, Json.obj("value", trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed, "status", "known"));
			}
		}
		return merged;
	}

	public static List<String> usefulFactsFor(String intent) {
		return switch (intent) {
			case "BUY", "RENT", "INVEST" -> List.of("zone", "property_type", "budget_max", "bedrooms");
			case "SELL", "LEASE_OUT" -> List.of("own_property_location", "property_type");
			default -> List.of();
		};
	}

	public static List<String> computeMissingFacts(String intent, Map<String, Object> facts) {
		List<String> missing = new ArrayList<>();
		for (String key : usefulFactsFor(intent)) if (facts == null || facts.get(key) == null) missing.add(key);
		return missing;
	}

	/** Valor de un dato "known", o null. */
	public static String knownFact(Map<String, Object> facts, String key) {
		Map<String, Object> fact = facts == null ? null : Json.asMap(facts.get(key));
		return fact != null && "known".equals(fact.get("status")) ? Json.str(fact, "value") : null;
	}

	// --- Controles sobre el texto de la respuesta -------------------------------------

	private static final Pattern INTERNAL_PROCESS_TERMS = Pattern.compile("\\bruleta\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	public static boolean mentionsInternalProcess(String reply) {
		return INTERNAL_PROCESS_TERMS.matcher(reply).find();
	}

	private static final Pattern ASSIGNMENT_CLAIM = Pattern.compile(
			"\\b(asign(?:é|e|ado|ada|amos)|te canaliz(?:o|é|amos|aré)|(?:ya )?registr(?:é|amos) tu solicitud|te (?:va|van) a contactar|te contactar(?:á|a|án|an)|ya (?:le )?avis(?:é|e|amos)|notifiqu(?:é|e)|ya (?:tienes|tiene) (?:un )?asesor)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/** ¿El texto afirma una asignación/aviso? Se compara con lo que realmente ocurrió. */
	public static boolean claimsAssignment(String reply) {
		return ASSIGNMENT_CLAIM.matcher(reply).find();
	}
}

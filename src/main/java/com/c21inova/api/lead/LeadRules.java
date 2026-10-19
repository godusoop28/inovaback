package com.c21inova.api.lead;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.c21inova.api.common.MexicoTime;
import com.c21inova.api.common.Secrets;

/** Reglas puras de leads: clasificación del interés, código de campaña, etiquetas y huella. */
public final class LeadRules {

	private LeadRules() {
	}

	public record Interest(String interestType, String routeLabel, String assignmentRoute) {
	}

	private static final Interest CAMPAIGN = new Interest("CAMPAIGN", "Campaña propiedad", "CAMPAIGN");
	private static final Interest PROPERTY = new Interest("PROPERTY", "Vi una propiedad", "PROPERTY");
	private static final Interest TIMEOUT = new Interest("TIMEOUT", "Timeout", "TIMEOUT");
	private static final Interest EXPLORE = new Interest("EXPLORE", "Explorar opciones", "EXPLORE");
	private static final Interest FALLBACK = new Interest("OTHER", "Explorar opciones", "EXPLORE");

	/**
	 * Normaliza el texto libre de ManyChat (interes_cliente) a las cuatro rutas.
	 * Por subcadena a propósito: la redacción de ManyChat ha cambiado antes.
	 * Campaña va antes que Propiedad ("Campaña propiedad" contiene ambas).
	 */
	public static Interest classifyInterest(String raw) {
		String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (n.contains("campa")) return CAMPAIGN;
		if (n.contains("propiedad") || n.contains("property")) return PROPERTY;
		if (n.contains("timeout") || n.contains("sin respuesta") || n.contains("sin_respuesta")) return TIMEOUT;
		if (n.contains("explor") || n.contains("asesor")) return EXPLORE;
		return FALLBACK;
	}

	private static final Pattern CAMPAIGN_CODE = Pattern.compile("EB-[A-Za-z0-9-]+");

	/** Código EB- dentro de texto libre ("Me interesa EB-ABC123"). */
	public static String extractCampaignPropertyCode(String text) {
		if (text == null) return null;
		Matcher matcher = CAMPAIGN_CODE.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}

	public static final Map<String, String> LEAD_STATUS_LABELS = Map.of(
			"RECEIVED", "Recibido",
			"PROCESSING", "Procesando",
			"CREATED_IN_EASYBROKER", "Creado en EasyBroker",
			"ASSIGNED", "Asignado",
			"NOTIFIED", "Notificado",
			"COMPLETED", "Completado",
			"FAILED", "Con error");

	public static final String PENDING_STATUS_FILTER = "pending";
	public static final String PENDING_STATUS_FILTER_LABEL = "Pendientes (sin completar)";

	public static String statusLabel(String status) {
		return LEAD_STATUS_LABELS.getOrDefault(status, status);
	}

	/**
	 * Clave de idempotencia cuando ManyChat no manda request id: determinista y
	 * agrupada por día de Ciudad de México.
	 */
	public static String fingerprint(String phone, String interestType, String propertyData, String origen, Instant now) {
		String dayKey = MexicoTime.dateKey(now);
		String raw = String.join("|", phone, interestType, propertyData == null ? "" : propertyData, origen == null ? "" : origen, dayKey);
		return Secrets.sha256Hex(raw);
	}
}

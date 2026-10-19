package com.c21inova.api.advisor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Constantes y reglas de asesores compartidas por el motor de asignación y el panel. */
public final class AdvisorRules {

	/** Rutas por las que un lead puede pedir asesor. */
	public static final List<String> LEAD_ROUTES = List.of("Vi una propiedad", "Explorar opciones", "Campaña propiedad", "Timeout");

	/** Etiqueta en español (UI) → código corto del motor de asignación. */
	public static final Map<String, String> ROUTE_LABEL_TO_CODE = Map.of(
			"Vi una propiedad", "PROPERTY",
			"Explorar opciones", "EXPLORE",
			"Campaña propiedad", "CAMPAIGN",
			"Timeout", "TIMEOUT");

	public static final int DEFAULT_ADVISOR_WEIGHT = 5;

	/** Valor de pausadoHasta que representa "pausado indefinidamente". */
	public static final String PAUSE_INDEFINITE = "INDEFINIDO";

	/**
	 * pausedUntil es un DateTime sin centinela para "indefinido": se usa una
	 * fecha muy lejana (año 2999) que nunca se confunde con una pausa real.
	 */
	private static final int DB_INDEFINITE_PAUSE_YEAR = 2999;

	private AdvisorRules() {
	}

	public record AllowedFlags(boolean allowedProperty, boolean allowedExplore, boolean allowedCampaign, boolean allowedTimeout) {
	}

	/** [] significa "todas las rutas". */
	public static List<String> allowedRoutes(Advisor advisor) {
		List<String> allowed = new ArrayList<>();
		if (advisor.allowedProperty()) allowed.add("Vi una propiedad");
		if (advisor.allowedExplore()) allowed.add("Explorar opciones");
		if (advisor.allowedCampaign()) allowed.add("Campaña propiedad");
		if (advisor.allowedTimeout()) allowed.add("Timeout");
		return allowed.size() == LEAD_ROUTES.size() ? List.of() : allowed;
	}

	public static AllowedFlags routeLabelsToAllowedFlags(List<String> routes) {
		Set<String> set = new HashSet<>(routes == null || routes.isEmpty() ? LEAD_ROUTES : routes);
		return new AllowedFlags(set.contains("Vi una propiedad"), set.contains("Explorar opciones"), set.contains("Campaña propiedad"),
				set.contains("Timeout"));
	}

	public static Instant indefinitePauseDate() {
		return java.time.LocalDate.of(DB_INDEFINITE_PAUSE_YEAR, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	public static boolean isIndefinitePause(Instant pausedUntil) {
		return pausedUntil != null && pausedUntil.atOffset(ZoneOffset.UTC).getYear() >= DB_INDEFINITE_PAUSE_YEAR;
	}

	/** Vista del panel (mismos nombres de campo que AdvisorView del front). */
	public static Map<String, Object> toView(Advisor advisor) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", advisor.id());
		view.put("nombre", advisor.name());
		view.put("whatsapp", advisor.phone());
		view.put("activo", advisor.active());
		view.put("emailEasyBroker", advisor.easyBrokerEmail() == null ? "" : advisor.easyBrokerEmail());
		view.put("manyChatId", advisor.manyChatSubscriberId() == null ? "" : advisor.manyChatSubscriberId());
		view.put("peso", advisor.weight());
		view.put("rutasPermitidas", allowedRoutes(advisor));
		view.put("pausadoHasta", advisor.pausedUntil() == null ? null
				: isIndefinitePause(advisor.pausedUntil()) ? PAUSE_INDEFINITE : com.c21inova.api.common.Texts.iso(advisor.pausedUntil()));
		view.put("limiteDiario", advisor.dailyLimit());
		view.put("observaciones", advisor.notes() == null ? "" : advisor.notes());
		return view;
	}
}

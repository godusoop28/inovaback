package com.c21inova.api.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;
import com.c21inova.api.lead.LeadRules;

/**
 * El único lugar que cuenta leads de un periodo: tablero, reportes y
 * exportaciones lo usan para no discrepar nunca. Todo sale de GROUP BY en
 * Postgres por companyId + createdAt, nunca de una IA.
 */
@Service
public class ReportDataService {

	public static final List<String> CANONICAL_ROUTES = List.of("PROPERTY", "EXPLORE", "CAMPAIGN", "TIMEOUT");
	public static final List<String> ALL_CANONICAL_ROUTES = List.of("PROPERTY", "EXPLORE", "CAMPAIGN", "TIMEOUT", "OTHER");
	public static final Map<String, String> ROUTE_LABELS = Map.of("PROPERTY", "Propiedad", "EXPLORE", "Explorar", "CAMPAIGN", "Campaña", "TIMEOUT",
			"Sin respuesta", "OTHER", "Otro");

	public record Filters(List<String> interestTypes, String origin, String advisorId) {
		public static final Filters NONE = new Filters(null, null, null);
	}

	private final JdbcClient jdbc;

	public ReportDataService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** NO_RESPONSE es un valor reservado: se pliega en TIMEOUT ("Sin respuesta"). */
	public static String toCanonicalRoute(String interestType) {
		if ("NO_RESPONSE".equals(interestType)) return "TIMEOUT";
		if ("OTHER".equals(interestType)) return "OTHER";
		return interestType;
	}

	public static List<String> canonicalRouteToInterestTypes(String route) {
		if ("TIMEOUT".equals(route)) return List.of("TIMEOUT", "NO_RESPONSE");
		return List.of(route);
	}

	public static double percent(int count, int total) {
		if (total <= 0) return 0;
		return Math.round((count / (double) total) * 1000) / 10.0;
	}

	private static Map<String, Integer> emptyRouteCounts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String route : ALL_CANONICAL_ROUTES) counts.put(route, 0);
		return counts;
	}

	private record Where(String sql, Map<String, Object> params) {
	}

	private static Where where(String companyId, Instant start, Instant end, Filters filters, String alias) {
		String a = alias == null ? "" : alias + ".";
		StringBuilder sql = new StringBuilder(a + "\"companyId\" = :companyId AND " + a + "\"createdAt\" >= :start AND " + a + "\"createdAt\" <= :end");
		Map<String, Object> params = new HashMap<>();
		params.put("companyId", companyId);
		params.put("start", Db.ts(start));
		params.put("end", Db.ts(end));
		if (filters != null && filters.interestTypes() != null && !filters.interestTypes().isEmpty()) {
			sql.append(" AND ").append(a).append("\"interestType\"::text IN (:interestTypes)");
			params.put("interestTypes", filters.interestTypes());
		}
		if (filters != null && filters.origin() != null && !filters.origin().isEmpty()) {
			sql.append(" AND ").append(a).append("origin ILIKE :origin");
			params.put("origin", "%" + filters.origin().replace("%", "\\%").replace("_", "\\_") + "%");
		}
		if (filters != null && filters.advisorId() != null && !filters.advisorId().isEmpty()) {
			sql.append(" AND ").append(a).append("\"assignedAdvisorId\" = :advisorId");
			params.put("advisorId", filters.advisorId());
		}
		return new Where(sql.toString(), params);
	}

	private List<Map<String, Object>> rows(String sql, Map<String, Object> params) {
		JdbcClient.StatementSpec spec = jdbc.sql(sql);
		for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), entry.getValue());
		return spec.query().listOfRows();
	}

	private static int n(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	public Map<String, Object> reportData(String companyId, Instant start, Instant end, Filters filters) {
		Where w = where(companyId, start, end, filters, null);
		int total = n(rows("SELECT count(*)::int AS n FROM leads WHERE " + w.sql(), w.params()).get(0).get("n"));
		List<Map<String, Object>> routeGroups = rows("SELECT \"interestType\"::text AS k, count(*)::int AS n FROM leads WHERE " + w.sql() + " GROUP BY 1", w.params());
		List<Map<String, Object>> originGroups = rows("SELECT origin AS k, count(*)::int AS n FROM leads WHERE " + w.sql() + " GROUP BY 1", w.params());
		List<Map<String, Object>> statusGroups = rows("SELECT status::text AS k, count(*)::int AS n FROM leads WHERE " + w.sql() + " GROUP BY 1", w.params());
		List<Map<String, Object>> advisorRouteGroups = rows("SELECT \"assignedAdvisorId\" AS a, \"interestType\"::text AS k, count(*)::int AS n FROM leads WHERE "
				+ w.sql() + " GROUP BY 1, 2", w.params());
		Map<String, String> advisorNames = new HashMap<>();
		jdbc.sql("SELECT id, name FROM advisors WHERE \"companyId\" = :c").param("c", companyId)
				.query((rs, i) -> advisorNames.put(rs.getString("id"), rs.getString("name"))).list();
		Where lw = where(companyId, start, end, filters, "l");
		List<Map<String, Object>> methodGroups = rows("SELECT la.\"advisorId\" AS a, la.method::text AS k, count(*)::int AS n FROM lead_assignments la "
				+ "JOIN leads l ON l.id = la.\"leadId\" WHERE " + lw.sql() + " GROUP BY 1, 2", lw.params());
		int uniquePeople = n(rows("SELECT count(DISTINCT phone)::int AS n FROM leads WHERE " + w.sql() + " AND phone IS NOT NULL AND phone <> ''", w.params())
				.get(0).get("n"));

		Map<String, Object> aggregated = aggregate(total, routeGroups, originGroups, statusGroups, advisorRouteGroups, advisorNames, methodGroups,
				uniquePeople);
		Map<String, Object> out = Json.obj("companyId", companyId, "startDate", start, "endDate", end);
		out.putAll(aggregated);
		return out;
	}

	/**
	 * Reducción pura de los GROUP BY (k = valor agrupado, a = asesor, n = conteo):
	 * misma lógica que report-aggregation.ts del panel.
	 */
	public static Map<String, Object> aggregate(int total, List<Map<String, Object>> routeGroups, List<Map<String, Object>> originGroups,
			List<Map<String, Object>> statusGroups, List<Map<String, Object>> advisorRouteGroups, Map<String, String> advisorNames,
			List<Map<String, Object>> methodGroups, int uniquePeople) {
		// --- por ruta ---
		Map<String, Integer> routeCounts = emptyRouteCounts();
		for (Map<String, Object> g : routeGroups) routeCounts.merge(toCanonicalRoute((String) g.get("k")), n(g.get("n")), Integer::sum);
		List<Map<String, Object>> byRoute = new ArrayList<>();
		for (String route : ALL_CANONICAL_ROUTES) {
			if (routeCounts.get(route) > 0) {
				byRoute.add(Json.obj("route", route, "label", ROUTE_LABELS.get(route), "count", routeCounts.get(route), "percent",
						percent(routeCounts.get(route), total)));
			}
		}
		// --- por origen (NULL y "  " se juntan en "Sin especificar") ---
		Map<String, Integer> originCounts = new LinkedHashMap<>();
		for (Map<String, Object> g : originGroups) {
			String origin = g.get("k") == null ? "" : ((String) g.get("k")).trim();
			originCounts.merge(origin.isEmpty() ? "Sin especificar" : origin, n(g.get("n")), Integer::sum);
		}
		List<Map<String, Object>> byOrigin = new ArrayList<>();
		originCounts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
				.forEach(e -> byOrigin.add(Json.obj("origin", e.getKey(), "count", e.getValue(), "percent", percent(e.getValue(), total))));
		// --- por estado ---
		List<Map<String, Object>> byStatus = new ArrayList<>();
		int pendingLeads = 0;
		int integrationErrors = 0;
		for (Map<String, Object> g : statusGroups) {
			String status = (String) g.get("k");
			int count = n(g.get("n"));
			byStatus.add(Json.obj("status", status, "label", LeadRules.statusLabel(status), "count", count, "percent", percent(count, total)));
			if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) pendingLeads += count;
			if ("FAILED".equals(status)) integrationErrors = count;
		}
		byStatus.sort((x, y) -> n(y.get("count")) - n(x.get("count")));
		// --- por asesor ---
		Map<String, Map<String, Object>> advisorRows = new LinkedHashMap<>();
		int assignedLeads = 0;
		for (Map<String, Object> g : advisorRouteGroups) {
			String advisorId = (String) g.get("a");
			String key = advisorId != null ? advisorId : "__unassigned__";
			Map<String, Object> row = advisorRows.computeIfAbsent(key, k -> Json.obj("advisorId", advisorId, "advisorName",
					advisorId != null ? advisorNames.getOrDefault(advisorId, "Asesor eliminado") : "Sin asignar", "total", 0, "percent", 0.0, "byRoute",
					emptyRouteCounts(), "automatic", 0, "exclusive", 0, "manual", 0));
			@SuppressWarnings("unchecked")
			Map<String, Integer> byRouteCounts = (Map<String, Integer>) row.get("byRoute");
			byRouteCounts.merge(toCanonicalRoute((String) g.get("k")), n(g.get("n")), Integer::sum);
			row.put("total", n(row.get("total")) + n(g.get("n")));
			if (advisorId != null) assignedLeads += n(g.get("n"));
		}
		for (Map<String, Object> g : methodGroups) {
			Map<String, Object> row = advisorRows.get((String) g.get("a"));
			if (row == null) continue;
			String method = (String) g.get("k");
			String column = "DIRECT_PROPERTY_ADVISOR".equals(method) || "CAMPAIGN_DIRECT".equals(method) ? "exclusive" : "MANUAL".equals(method) ? "manual" : "automatic";
			row.put(column, n(row.get(column)) + n(g.get("n")));
		}
		List<Map<String, Object>> byAdvisor = new ArrayList<>(advisorRows.values());
		for (Map<String, Object> row : byAdvisor) row.put("percent", percent(n(row.get("total")), total));
		byAdvisor.sort((x, y) -> n(y.get("total")) - n(x.get("total")));
		int advisorsWithLeads = (int) byAdvisor.stream().filter(r -> r.get("advisorId") != null).count();

		Map<String, Object> summary = Json.obj("total", total, "uniquePeople", uniquePeople, "byRoute", routeCounts, "advisorsWithLeads", advisorsWithLeads,
				"pendingLeads", pendingLeads, "integrationErrors", integrationErrors, "assignedLeads", assignedLeads);
		return Json.obj("summary", summary, "byRoute", byRoute, "byOrigin", byOrigin, "byAdvisor", byAdvisor, "byStatus", byStatus);
	}
}

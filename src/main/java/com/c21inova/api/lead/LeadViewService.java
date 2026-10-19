package com.c21inova.api.lead;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.assignment.LeadAssignment;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;
import com.c21inova.api.integration.IntegrationJobRepository;
import com.c21inova.api.integration.IntegrationNotice;
import com.c21inova.api.integration.IntegrationNotice.AuditRow;
import com.c21inova.api.integration.IntegrationNotice.NoticeJob;

/** Lecturas de leads para el panel (lista, detalle, conteos, filtros). */
@Service
public class LeadViewService {

	public record Filters(String companyId, String status, String advisorId, List<String> interestTypes, String origin, Instant from, Instant to,
			String search) {
	}

	private final JdbcClient jdbc;
	private final IntegrationJobRepository jobs;

	public LeadViewService(JdbcClient jdbc, IntegrationJobRepository jobs) {
		this.jdbc = jdbc;
		this.jobs = jobs;
	}

	private record Where(String sql, Map<String, Object> params) {
	}

	/** Mismas reglas que el panel: "pending" = distinto de COMPLETED y FAILED; búsqueda por nombre o teléfono. */
	static Where where(Filters filters, String alias) {
		String a = alias == null ? "" : alias + ".";
		StringBuilder sql = new StringBuilder(a + "\"companyId\" = :companyId");
		Map<String, Object> params = new HashMap<>();
		params.put("companyId", filters.companyId());
		if (LeadRules.PENDING_STATUS_FILTER.equals(filters.status())) {
			sql.append(" AND ").append(a).append("status NOT IN ('COMPLETED', 'FAILED')");
		} else if (filters.status() != null && !filters.status().isEmpty()) {
			sql.append(" AND ").append(a).append("status::text = :status");
			params.put("status", filters.status());
		}
		if (filters.advisorId() != null && !filters.advisorId().isEmpty()) {
			sql.append(" AND ").append(a).append("\"assignedAdvisorId\" = :advisorId");
			params.put("advisorId", filters.advisorId());
		}
		if (filters.interestTypes() != null && !filters.interestTypes().isEmpty()) {
			sql.append(" AND ").append(a).append("\"interestType\"::text IN (:interestTypes)");
			params.put("interestTypes", filters.interestTypes());
		}
		if (filters.origin() != null && !filters.origin().isEmpty()) {
			sql.append(" AND ").append(a).append("origin ILIKE :origin");
			params.put("origin", "%" + likeEscape(filters.origin()) + "%");
		}
		if (filters.from() != null) {
			sql.append(" AND ").append(a).append("\"createdAt\" >= :from");
			params.put("from", Db.ts(filters.from()));
		}
		if (filters.to() != null) {
			sql.append(" AND ").append(a).append("\"createdAt\" <= :to");
			params.put("to", Db.ts(filters.to()));
		}
		if (filters.search() != null && !filters.search().isEmpty()) {
			sql.append(" AND (").append(a).append("name ILIKE :search OR ").append(a).append("phone ILIKE :search)");
			params.put("search", "%" + likeEscape(filters.search()) + "%");
		}
		return new Where(sql.toString(), params);
	}

	static String likeEscape(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
		for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), entry.getValue());
		return spec;
	}

	public record Page(List<Map<String, Object>> leads, int total, int page, int limit) {
	}

	/** Lista paginada con la misma forma que LeadView del panel. */
	public Page list(Filters filters, int page, int limit) {
		Where where = where(filters, null);
		int total = bind(jdbc.sql("SELECT count(*)::int FROM leads WHERE " + where.sql()), where.params()).query(Integer.class).single();
		List<Lead> rows = bind(jdbc.sql("SELECT " + Lead.COLUMNS + " FROM leads WHERE " + where.sql()
				+ " ORDER BY \"createdAt\" DESC OFFSET :offset LIMIT :limit"), where.params())
				.param("offset", (page - 1) * limit).param("limit", limit).query(Lead::map).list();
		if (rows.isEmpty()) return new Page(List.of(), total, page, limit);

		List<String> ids = rows.stream().map(Lead::id).toList();
		Set<String> advisorIds = new LinkedHashSet<>();
		for (Lead lead : rows) if (lead.assignedAdvisorId() != null) advisorIds.add(lead.assignedAdvisorId());
		Map<String, Map<String, Object>> advisors = new HashMap<>();
		if (!advisorIds.isEmpty()) {
			jdbc.sql("SELECT id, name, phone, \"manyChatSubscriberId\" FROM advisors WHERE id IN (:ids)").param("ids", advisorIds).query((rs, i) -> advisors
					.put(rs.getString("id"), Json.obj("name", rs.getString("name"), "phone", rs.getString("phone"), "subscriber", rs.getString("manyChatSubscriberId"))))
					.list();
		}
		Map<String, LeadAssignment> latest = new HashMap<>();
		jdbc.sql("SELECT DISTINCT ON (\"leadId\") " + LeadAssignment.COLUMNS
				+ " FROM lead_assignments WHERE \"leadId\" IN (:ids) ORDER BY \"leadId\", \"assignedAt\" DESC").param("ids", ids)
				.query(LeadAssignment::map).list().forEach(a -> latest.put(a.leadId(), a));
		Map<String, List<AuditRow>> errors = new HashMap<>();
		jdbc.sql("""
				SELECT "leadId", "eventType", message, "createdAt" FROM (
				  SELECT "leadId", "eventType", message, "createdAt", row_number() OVER (PARTITION BY "leadId" ORDER BY "createdAt" DESC) AS rn
				  FROM audit_logs WHERE "leadId" IN (:ids) AND status = 'error'
				) t WHERE rn <= 5 ORDER BY "createdAt" DESC
				""").param("ids", ids).query((rs, i) -> errors.computeIfAbsent(rs.getString("leadId"), k -> new ArrayList<>())
				.add(new AuditRow(rs.getString("eventType"), rs.getString("message"), Db.instant(rs, "createdAt")))).list();
		Map<String, List<NoticeJob>> jobsByLead = new HashMap<>();
		jdbc.sql("SELECT * FROM (SELECT " + IntegrationJobRepository.COLUMNS
				+ ", row_number() OVER (PARTITION BY \"leadId\" ORDER BY \"createdAt\" DESC) AS rn FROM integration_jobs WHERE \"leadId\" IN (:ids)) t"
				+ " WHERE rn <= 10 ORDER BY \"createdAt\" DESC").param("ids", ids).query(IntegrationJobRepository::map).list()
				.forEach(job -> jobsByLead.computeIfAbsent(job.leadId(), k -> new ArrayList<>()).add(job.toNotice()));

		List<Map<String, Object>> views = new ArrayList<>();
		for (Lead lead : rows) {
			views.add(toView(lead, advisors.get(lead.assignedAdvisorId()), latest.get(lead.id()), errors.getOrDefault(lead.id(), List.of()),
					jobsByLead.getOrDefault(lead.id(), List.of())));
		}
		return new Page(views, total, page, limit);
	}

	private static Map<String, Object> toView(Lead lead, Map<String, Object> advisor, LeadAssignment latest, List<AuditRow> errorLogs, List<NoticeJob> jobs) {
		String digits = lead.phone().replaceAll("\\D", "");
		boolean notified = latest != null && latest.manyChatNotified();
		String advisorName = advisor == null ? null : (String) advisor.get("name");
		String subscriber = advisor == null ? null : (String) advisor.get("subscriber");
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", lead.id());
		view.put("fechaHora", com.c21inova.api.common.Texts.iso(lead.createdAt()));
		view.put("nombre", lead.name());
		view.put("telefono", lead.phone());
		view.put("tipoInteres", lead.route() != null && !lead.route().isEmpty() ? lead.route() : lead.interestType());
		view.put("interestType", lead.interestType());
		view.put("datoEnviado", lead.propertyData() == null ? "" : lead.propertyData());
		view.put("origen", lead.origin() == null ? "" : lead.origin());
		view.put("ruta", lead.route() == null ? "" : lead.route());
		view.put("estado", LeadRules.statusLabel(lead.status()));
		view.put("status", lead.status());
		view.put("linkWhatsappCliente", digits.isEmpty() ? "" : "https://wa.me/" + digits);
		view.put("asesorAsignado", advisorName == null ? "" : advisorName);
		view.put("asesorTelefono", advisor == null ? "" : advisor.get("phone"));
		view.put("metodoAsignacion", latest == null ? "" : ("DIRECT_PROPERTY_ADVISOR".equals(latest.method()) || "CAMPAIGN_DIRECT".equals(latest.method()) ? "Directa" : "Ruleta"));
		view.put("manyChatNotificado", notified);
		view.put("easyBrokerConfirmado", latest != null && latest.easyBrokerConfirmed());
		view.put("error", IntegrationNotice.uncoveredAuditError(errorLogs, jobs, notified));
		view.put("aviso", notice(jobs, advisorName, subscriber, notified));
		view.put("assignmentStatus", lead.assignmentStatus());
		view.put("propiedadId", lead.easyBrokerPropertyId() == null ? "" : lead.easyBrokerPropertyId());
		view.put("tieneAsignacion", latest != null);
		return view;
	}

	static Map<String, Object> notice(List<NoticeJob> jobs, String advisorName, String advisorSubscriber, boolean assignmentNotified) {
		return IntegrationNotice.current(jobs, job -> IntegrationNotice.target(job.type(), job.payload(), advisorName, advisorSubscriber),
				assignmentNotified ? Set.of("MANYCHAT_FLOW") : Set.of());
	}

	/** Conteos para las tarjetas de Leads (misma definición de "pendiente" que los reportes). */
	public Map<String, Object> statusCounts(String companyId) {
		Map<String, Integer> groups = Db.countsByKey(jdbc.sql("SELECT status::text AS status, count(*)::int AS n FROM leads WHERE \"companyId\" = :c GROUP BY status")
				.param("c", companyId).query().listOfRows(), "status");
		int assigned = jdbc.sql("SELECT count(*)::int FROM leads WHERE \"companyId\" = :c AND \"assignedAdvisorId\" IS NOT NULL").param("c", companyId)
				.query(Integer.class).single();
		int total = groups.values().stream().mapToInt(Integer::intValue).sum();
		int failed = groups.getOrDefault("FAILED", 0);
		int pending = groups.entrySet().stream().filter(e -> !"COMPLETED".equals(e.getKey()) && !"FAILED".equals(e.getKey())).mapToInt(Map.Entry::getValue).sum();
		return Json.obj("total", total, "pending", pending, "assigned", assigned, "failed", failed);
	}

	public Map<String, Object> filterOptions(String companyId) {
		List<String> origins = jdbc.sql("""
				SELECT trim(origin) AS origin FROM leads WHERE "companyId" = :c AND origin IS NOT NULL AND trim(origin) <> ''
				GROUP BY origin ORDER BY count(*) DESC
				""").param("c", companyId).query(String.class).list();
		List<Map<String, Object>> advisors = jdbc.sql("SELECT id, name, active FROM advisors WHERE \"companyId\" = :c ORDER BY name ASC").param("c", companyId)
				.query((rs, i) -> Json.obj("id", rs.getString("id"), "name", rs.getString("name"), "active", rs.getBoolean("active"))).list();
		return Json.obj("origins", new ArrayList<>(new LinkedHashSet<>(origins)), "advisors", advisors);
	}

	/** Títulos del índice local para mostrar el nombre de la propiedad junto a su código. */
	public Map<String, String> propertyTitles(String companyId, List<String> publicIds) {
		Map<String, String> out = new HashMap<>();
		List<String> ids = publicIds.stream().filter(id -> id != null && !id.isEmpty()).distinct().toList();
		if (ids.isEmpty()) return out;
		jdbc.sql("SELECT \"publicId\", title FROM property_cache WHERE \"companyId\" = :c AND \"publicId\" IN (:ids)").param("c", companyId).param("ids", ids)
				.query((rs, i) -> out.put(rs.getString("publicId"), rs.getString("title"))).list();
		return out;
	}

	/** Detalle del lead: asignaciones, bitácora, aviso vigente e historial de integraciones (sin payload). */
	public Map<String, Object> detail(String companyId, String id) {
		Lead lead = jdbc.sql("SELECT " + Lead.COLUMNS + " FROM leads WHERE id = :id AND \"companyId\" = :c").param("id", id).param("c", companyId)
				.query(Lead::map).optional().orElse(null);
		if (lead == null) return null;
		Map<String, Object> advisor = lead.assignedAdvisorId() == null ? null
				: jdbc.sql("SELECT id, name, phone, \"easyBrokerEmail\", \"manyChatSubscriberId\" FROM advisors WHERE id = :id").param("id", lead.assignedAdvisorId())
						.query((rs, i) -> Json.obj("id", rs.getString("id"), "name", rs.getString("name"), "phone", rs.getString("phone"), "easyBrokerEmail",
								rs.getString("easyBrokerEmail"), "manyChatSubscriberId", rs.getString("manyChatSubscriberId")))
						.optional().orElse(null);
		List<Map<String, Object>> assignmentRows = jdbc.sql("""
				SELECT la.id, la."leadId", la."advisorId", la.method::text AS method, la."weightAtAssignment", la.reason, la.status::text AS status,
				  la."easyBrokerConfirmed", la."manyChatNotified", la."assignedAt", la."createdAt", la."updatedAt", a.name AS "advisorName" FROM lead_assignments la JOIN advisors a ON a.id = la."advisorId"
				WHERE la."leadId" = :id ORDER BY la."assignedAt" DESC
				""").param("id", id).query((rs, i) -> {
			LeadAssignment assignment = LeadAssignment.map(rs, i);
			Map<String, Object> row = Json.obj("id", assignment.id(), "leadId", assignment.leadId(), "advisorId", assignment.advisorId(), "method",
					assignment.method(), "weightAtAssignment", assignment.weightAtAssignment(), "reason", assignment.reason(), "status", assignment.status(),
					"easyBrokerConfirmed", assignment.easyBrokerConfirmed(), "manyChatNotified", assignment.manyChatNotified(), "assignedAt",
					assignment.assignedAt(), "createdAt", assignment.createdAt(), "updatedAt", assignment.updatedAt());
			row.put("advisor", Json.obj("id", assignment.advisorId(), "name", rs.getString("advisorName")));
			return row;
		}).list();
		List<Map<String, Object>> auditLogs = jdbc.sql("""
				SELECT id, "eventType", status, message, metadata::text AS metadata, "advisorId", "createdAt" FROM audit_logs
				WHERE "leadId" = :id ORDER BY "createdAt" DESC
				""").param("id", id).query((rs, i) -> Json.obj("id", rs.getString("id"), "eventType", rs.getString("eventType"), "status",
				rs.getString("status"), "message", rs.getString("message"), "metadata", Db.json(rs, "metadata"), "advisorId", rs.getString("advisorId"),
				"createdAt", Db.instant(rs, "createdAt"))).list();
		List<NoticeJob> jobRows = jobs.findByLead(id, null).stream().map(IntegrationJobRepository.Job::toNotice).toList();
		boolean notified = !assignmentRows.isEmpty() && Boolean.TRUE.equals(assignmentRows.get(0).get("manyChatNotified"));
		String advisorName = advisor == null ? null : (String) advisor.get("name");
		String subscriber = advisor == null ? null : (String) advisor.get("manyChatSubscriberId");
		List<Map<String, Object>> jobViews = jobRows.stream().map(job -> Json.obj("id", job.id(), "type", job.type(), "status", job.status(), "attempts",
				job.attempts(), "lastError", job.lastError(), "errorKind", job.errorKind(), "errorCode", job.errorCode(), "lastAttemptAt", job.lastAttemptAt(),
				"nextRetryAt", job.nextRetryAt(), "createdAt", job.createdAt(), "target",
				IntegrationNotice.target(job.type(), job.payload(), advisorName, subscriber))).toList();

		Map<String, Object> leadView = Json.obj("id", lead.id(), "companyId", lead.companyId(), "name", lead.name(), "phone", lead.phone(), "interestType",
				lead.interestType(), "propertyData", lead.propertyData(), "origin", lead.origin(), "route", lead.route(), "status", lead.status(),
				"statusLabel", LeadRules.statusLabel(lead.status()), "source", lead.source(), "easyBrokerContactRequestId", lead.easyBrokerContactRequestId(),
				"easyBrokerContactId", lead.easyBrokerContactId(), "easyBrokerPropertyId", lead.easyBrokerPropertyId(), "manyChatSubscriberId",
				lead.manyChatSubscriberId(), "assignedAdvisorId", lead.assignedAdvisorId(), "assignmentStatus", lead.assignmentStatus(), "requestId",
				lead.requestId(), "rawPayload", lead.rawPayload(), "createdAt", lead.createdAt(), "updatedAt", lead.updatedAt(), "assignedAdvisor",
				advisor == null ? null : Json.obj("id", advisor.get("id"), "name", advisor.get("name"), "phone", advisor.get("phone"), "easyBrokerEmail",
						advisor.get("easyBrokerEmail")));
		return Json.obj("lead", leadView, "assignments", assignmentRows, "auditLogs", auditLogs, "notice", notice(jobRows, advisorName, subscriber, notified),
				"jobs", jobViews);
	}
}

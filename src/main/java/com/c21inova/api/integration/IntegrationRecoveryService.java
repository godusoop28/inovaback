package com.c21inova.api.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;

/**
 * Recuperación selectiva de avisos MANYCHAT_FLOW abiertos: reporte en seco
 * (no cambia nada) y ejecución solo de los trabajos indicados, tras
 * recalcular su categoría. Nunca hay reenvío masivo.
 */
@Service
public class IntegrationRecoveryService {

	private final JdbcClient jdbc;
	private final IntegrationJobRepository jobs;
	private final AuditService audit;

	public IntegrationRecoveryService(JdbcClient jdbc, IntegrationJobRepository jobs, AuditService audit) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.audit = audit;
	}

	public Map<String, Object> report(String companyId) {
		List<IntegrationJobRepository.Job> open = jdbc.sql("SELECT " + IntegrationJobRepository.COLUMNS + """
				 FROM integration_jobs WHERE "companyId" = :c AND type = 'MANYCHAT_FLOW' AND status IN ('PENDING', 'RETRYING', 'FAILED', 'UNCERTAIN')
				 ORDER BY "createdAt" ASC
				""").param("c", companyId).query(IntegrationJobRepository::map).list();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String category : List.of("ASSIGNED_NOTICE_PENDING", "UNASSIGNED", "PERMANENT_FAILURE", "UNCERTAIN", "RECOVERED_STALE_NOTICE", "SUPERSEDED")) {
			counts.put(category, 0);
		}
		List<Map<String, Object>> items = new ArrayList<>();
		for (IntegrationJobRepository.Job job : open) {
			Map<String, Object> lead = job.leadId() == null ? null : jdbc.sql("""
					SELECT l.id, l.name, l."assignedAdvisorId", a.name AS "advisorName", a."manyChatSubscriberId" AS subscriber,
					  la."advisorId" AS "latestAdvisorId", la."manyChatNotified" AS "latestNotified",
					  EXISTS (SELECT 1 FROM integration_jobs j WHERE j."leadId" = l.id AND j.type = 'MANYCHAT_FLOW' AND j.status = 'SUCCESS'
					    AND j."createdAt" > :jobCreated) AS "laterSuccess"
					FROM leads l
					LEFT JOIN advisors a ON a.id = l."assignedAdvisorId"
					LEFT JOIN LATERAL (SELECT "advisorId", "manyChatNotified" FROM lead_assignments WHERE "leadId" = l.id ORDER BY "assignedAt" DESC LIMIT 1) la ON true
					WHERE l.id = :id
					""").param("id", job.leadId()).param("jobCreated", Db.ts(job.createdAt())).query((rs, i) -> {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("name", rs.getString("name"));
				row.put("assignedAdvisorId", rs.getString("assignedAdvisorId"));
				row.put("advisorName", rs.getString("advisorName"));
				row.put("subscriber", rs.getString("subscriber"));
				row.put("latestAdvisorId", rs.getString("latestAdvisorId"));
				row.put("latestNotified", rs.getBoolean("latestNotified"));
				row.put("laterSuccess", rs.getBoolean("laterSuccess"));
				return row;
			}).optional().orElse(null);
			boolean noticeConfirmed = lead != null && ((lead.get("latestAdvisorId") != null && lead.get("latestAdvisorId").equals(lead.get("assignedAdvisorId"))
					&& Boolean.TRUE.equals(lead.get("latestNotified"))) || Boolean.TRUE.equals(lead.get("laterSuccess")));
			IntegrationNotice.Recovery recovery = IntegrationNotice.categorizeRecovery(job.status(), Json.str(Json.asMap(job.payload()), "subscriberId"),
					lead != null, lead == null ? null : (String) lead.get("assignedAdvisorId"), lead == null ? null : (String) lead.get("subscriber"),
					noticeConfirmed);
			counts.merge(recovery.category(), 1, Integer::sum);
			items.add(Json.obj("jobId", job.id(), "leadId", job.leadId(), "leadName", lead == null ? null : lead.get("name"), "advisorName",
					lead == null ? null : lead.get("advisorName"), "status", job.status(), "attempts", job.attempts(), "errorKind", job.errorKind(), "errorCode",
					job.errorCode(), "reason", IntegrationNotice.reason(job.status(), job.errorKind(), job.lastError()), "lastAttemptAt",
					com.c21inova.api.common.Texts.iso(job.lastAttemptAt()), "createdAt", com.c21inova.api.common.Texts.iso(job.createdAt()), "category",
					recovery.category(), "action", recovery.action()));
		}
		return Json.obj("items", items, "counts", counts);
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> execute(String companyId, List<String> requeue, List<String> close, String actor) {
		Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
		for (Map<String, Object> item : (List<Map<String, Object>>) report(companyId).get("items")) byId.put((String) item.get("jobId"), item);
		List<String> requeued = new ArrayList<>();
		List<String> closed = new ArrayList<>();
		List<Map<String, Object>> rejected = new ArrayList<>();

		for (String jobId : new LinkedHashSet<>(requeue == null ? List.of() : requeue)) {
			Map<String, Object> item = byId.get(jobId);
			if (item == null || !IntegrationNotice.REQUEUEABLE.contains(item.get("category"))) {
				rejected.add(Json.obj("jobId", jobId, "reason", item != null ? "Categoría " + item.get("category") + ": no se reenvía."
						: "No es un aviso abierto de esta empresa."));
				continue;
			}
			if (jobs.requeue(jobId)) {
				requeued.add(jobId);
				audit.log(companyId, (String) item.get("leadId"), null, "LEAD_RETRY_REQUESTED", "ok",
						"Recuperación manual del aviso al asesor solicitada por " + actor + ".", Json.obj("jobId", jobId));
			} else {
				rejected.add(Json.obj("jobId", jobId, "reason", "El trabajo cambió de estado; vuelve a generar el reporte."));
			}
		}
		for (String jobId : new LinkedHashSet<>(close == null ? List.of() : close)) {
			Map<String, Object> item = byId.get(jobId);
			if (item == null || !IntegrationNotice.CLOSABLE.contains(item.get("category"))) {
				rejected.add(Json.obj("jobId", jobId, "reason", item != null ? "Categoría " + item.get("category") + ": no se cierra."
						: "No es un aviso abierto de esta empresa."));
				continue;
			}
			String reason = "SUPERSEDED".equals(item.get("category")) ? "Cerrado manualmente: el lead tiene otro asesor."
					: "Cerrado manualmente: el aviso ya estaba confirmado.";
			jobs.markCancelled(jobId, reason);
			closed.add(jobId);
			audit.log(companyId, (String) item.get("leadId"), null, "INTEGRATION_JOB_RETRIED", "skipped", reason + " (" + actor + ")", Json.obj("jobId", jobId));
		}
		return Json.obj("requeued", requeued, "closed", closed, "rejected", rejected, "at", Instant.now());
	}
}

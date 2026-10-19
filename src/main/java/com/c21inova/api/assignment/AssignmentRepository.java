package com.c21inova.api.assignment;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;

@Repository
public class AssignmentRepository {

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;

	public AssignmentRepository(JdbcClient jdbc, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tx = tx;
	}

	public LeadAssignment create(String leadId, String advisorId, String method, int weight, String status, String reason) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO lead_assignments (id, "leadId", "advisorId", method, "weightAtAssignment", reason, status,
				  "easyBrokerConfirmed", "manyChatNotified", "assignedAt", "createdAt", "updatedAt")
				VALUES (:id, :leadId, :advisorId, CAST(:method AS "AssignmentMethod"), :weight, :reason, CAST(:status AS "AssignmentStatus"),
				  false, false, :now, :now, :now)
				""")
				.param("id", id).param("leadId", leadId).param("advisorId", advisorId).param("method", method).param("weight", weight)
				.param("reason", reason).param("status", status).param("now", Db.ts(now)).update();
		return findById(id).orElseThrow();
	}

	public Optional<LeadAssignment> findById(String id) {
		return jdbc.sql("SELECT " + LeadAssignment.COLUMNS + " FROM lead_assignments WHERE id = :id").param("id", id).query(LeadAssignment::map)
				.optional();
	}

	public List<LeadAssignment> findByLeadId(String leadId) {
		return jdbc.sql("SELECT " + LeadAssignment.COLUMNS + " FROM lead_assignments WHERE \"leadId\" = :leadId ORDER BY \"assignedAt\" DESC")
				.param("leadId", leadId).query(LeadAssignment::map).list();
	}

	/** Conteo autoritativo del día desde lead_assignments (no la caché leadsToday). */
	public Map<String, Integer> countByAdvisor(List<String> advisorIds, Instant dayStart, Instant dayEnd) {
		Map<String, Integer> out = new HashMap<>();
		if (advisorIds.isEmpty()) return out;
		jdbc.sql("""
				SELECT "advisorId", count(*)::int AS n FROM lead_assignments
				WHERE "advisorId" IN (:ids) AND "assignedAt" >= :start AND "assignedAt" <= :end GROUP BY "advisorId"
				""").param("ids", advisorIds).param("start", Db.ts(dayStart)).param("end", Db.ts(dayEnd))
				.query((rs, i) -> out.put(rs.getString("advisorId"), rs.getInt("n"))).list();
		return out;
	}

	public void updateStatus(String id, String status, Boolean easyBrokerConfirmed, Boolean manyChatNotified) {
		StringBuilder set = new StringBuilder("\"updatedAt\" = :now");
		if (status != null) set.append(", status = CAST(:status AS \"AssignmentStatus\")");
		if (easyBrokerConfirmed != null) set.append(", \"easyBrokerConfirmed\" = :eb");
		if (manyChatNotified != null) set.append(", \"manyChatNotified\" = :mc");
		JdbcClient.StatementSpec spec = jdbc.sql("UPDATE lead_assignments SET " + set + " WHERE id = :id").param("id", id).param("now",
				Db.ts(Instant.now()));
		if (status != null) spec = spec.param("status", status);
		if (easyBrokerConfirmed != null) spec = spec.param("eb", easyBrokerConfirmed);
		if (manyChatNotified != null) spec = spec.param("mc", manyChatNotified);
		spec.update();
	}

	public record LeadNoticeState(String leadId, String leadStatus, String assignedAdvisorId, String assignedAdvisorName,
			String assignedAdvisorSubscriberId, String assignmentId, String assignmentAdvisorId, boolean assignmentNotified) {
	}

	/** Estado actual de asignación y aviso de un lead, para decidir si un aviso pendiente todavía aplica. */
	public Optional<LeadNoticeState> findLeadNoticeState(String leadId) {
		return jdbc.sql("""
				SELECT l.id, l.status::text AS status, l."assignedAdvisorId", a.name AS "advisorName", a."manyChatSubscriberId" AS "advisorSubscriber",
				  la.id AS "assignmentId", la."advisorId" AS "assignmentAdvisorId", la."manyChatNotified"
				FROM leads l
				LEFT JOIN advisors a ON a.id = l."assignedAdvisorId"
				LEFT JOIN LATERAL (
				  SELECT id, "advisorId", "manyChatNotified" FROM lead_assignments WHERE "leadId" = l.id ORDER BY "assignedAt" DESC LIMIT 1
				) la ON true
				WHERE l.id = :leadId
				""").param("leadId", leadId).query((rs, i) -> new LeadNoticeState(
				rs.getString("id"), rs.getString("status"), rs.getString("assignedAdvisorId"), rs.getString("advisorName"),
				rs.getString("advisorSubscriber"), rs.getString("assignmentId"), rs.getString("assignmentAdvisorId"),
				rs.getBoolean("manyChatNotified"))).optional();
	}

	/**
	 * Aviso recuperado: marca SOLO el paso pendiente. El lead pasa de
	 * "Notificado" a "Completado" únicamente si sigue en ese estado.
	 */
	public void markNotified(String leadId, String assignmentId) {
		tx.executeWithoutResult(status -> {
			Instant now = Instant.now();
			jdbc.sql("UPDATE lead_assignments SET \"manyChatNotified\" = true, \"updatedAt\" = :now WHERE id = :id").param("id", assignmentId)
					.param("now", Db.ts(now)).update();
			jdbc.sql("UPDATE leads SET status = 'COMPLETED', \"updatedAt\" = :now WHERE id = :id AND status = 'NOTIFIED'").param("id", leadId)
					.param("now", Db.ts(now)).update();
		});
	}
}

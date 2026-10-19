package com.c21inova.api.lead;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;

@Repository
public class LeadRepository {

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;

	public LeadRepository(JdbcClient jdbc, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tx = tx;
	}

	public record NewLead(String companyId, String name, String phone, String interestType, String propertyData, String origin, String route,
			String status, String source, String manyChatSubscriberId, String assignmentStatus, String requestId, Object rawPayload) {
	}

	public Optional<Lead> findById(String id) {
		return jdbc.sql("SELECT " + Lead.COLUMNS + " FROM leads WHERE id = :id").param("id", id).query(Lead::map).optional();
	}

	public Optional<Lead> findByRequestId(String requestId) {
		return jdbc.sql("SELECT " + Lead.COLUMNS + " FROM leads WHERE \"requestId\" = :requestId").param("requestId", requestId).query(Lead::map)
				.optional();
	}

	public Lead create(NewLead data) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO leads (id, "companyId", name, phone, "interestType", "propertyData", origin, route, status, source,
				  "manyChatSubscriberId", "assignmentStatus", "requestId", "rawPayload", "createdAt", "updatedAt")
				VALUES (:id, :companyId, :name, :phone, CAST(:interestType AS "LeadInterestType"), :propertyData, :origin, :route,
				  CAST(:status AS "LeadStatus"), :source, :subscriberId, CAST(:assignmentStatus AS "AssignmentStatus"), :requestId,
				  CAST(:rawPayload AS jsonb), :now, :now)
				""")
				.param("id", id)
				.param("companyId", data.companyId())
				.param("name", data.name())
				.param("phone", data.phone())
				.param("interestType", data.interestType())
				.param("propertyData", data.propertyData())
				.param("origin", data.origin())
				.param("route", data.route())
				.param("status", data.status())
				.param("source", data.source())
				.param("subscriberId", data.manyChatSubscriberId())
				.param("assignmentStatus", data.assignmentStatus())
				.param("requestId", data.requestId())
				.param("rawPayload", Db.jsonParam(data.rawPayload()))
				.param("now", Db.ts(now))
				.update();
		return findById(id).orElseThrow();
	}

	public sealed interface Claim permits Created, Existing {
	}

	public record Created(Lead lead) implements Claim {
	}

	public record Existing(Lead existing) implements Claim {
	}

	/**
	 * Crea el lead solo si el teléfono no tiene otro (fuera del simulador) desde
	 * `since`. El candado consultivo por teléfono serializa entregas simultáneas
	 * de ManyChat para que solo la primera cree el lead.
	 */
	public Claim createUnlessRecentForPhone(NewLead data, Instant since) {
		return tx.execute(status -> {
			jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "lead-phone:" + data.companyId() + ":" + data.phone())
					.query((rs, i) -> 1).list();
			Optional<Lead> existing = jdbc.sql("SELECT " + Lead.COLUMNS + """
					 FROM leads WHERE "companyId" = :companyId AND phone = :phone AND "createdAt" >= :since
					 AND (source IS NULL OR source <> 'testing_ui') ORDER BY "createdAt" DESC LIMIT 1
					""").param("companyId", data.companyId()).param("phone", data.phone()).param("since", Db.ts(since)).query(Lead::map).optional();
			if (existing.isPresent()) return new Existing(existing.get());
			return new Created(create(data));
		});
	}

	/** Actualiza solo los campos indicados (status/assignmentStatus como enums). */
	public void update(String id, Map<String, Object> fields) {
		if (fields.isEmpty()) return;
		StringBuilder set = new StringBuilder();
		Map<String, Object> params = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : fields.entrySet()) {
			String column = entry.getKey();
			String param = "p_" + column;
			String value = switch (column) {
				case "status" -> "CAST(:" + param + " AS \"LeadStatus\")";
				case "assignmentStatus" -> "CAST(:" + param + " AS \"AssignmentStatus\")";
				default -> ":" + param;
			};
			if (!set.isEmpty()) set.append(", ");
			set.append('"').append(column).append("\" = ").append(value);
			params.put(param, entry.getValue());
		}
		set.append(", \"updatedAt\" = :now");
		JdbcClient.StatementSpec spec = jdbc.sql("UPDATE leads SET " + set + " WHERE id = :id").param("id", id).param("now", Db.ts(Instant.now()));
		for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), entry.getValue());
		spec.update();
	}

	public void update(String id, String column, Object value) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put(column, value);
		update(id, fields);
	}

	/** Lead reciente del mismo teléfono (cualquier origen real), con el nombre de su asesor. */
	public Optional<Map<String, Object>> findRecentForPhone(String companyId, String phone, Instant since) {
		return jdbc.sql("""
				SELECT l.id, l.status::text AS status, l."assignedAdvisorId", a.name AS "advisorName" FROM leads l
				LEFT JOIN advisors a ON a.id = l."assignedAdvisorId"
				WHERE l."companyId" = :companyId AND l.phone = :phone AND l."createdAt" >= :since
				  AND (l.source IS NULL OR l.source <> 'testing_ui')
				ORDER BY l."createdAt" DESC LIMIT 1
				""").param("companyId", companyId).param("phone", phone).param("since", Db.ts(since)).query((rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", rs.getString("id"));
			row.put("status", rs.getString("status"));
			row.put("assignedAdvisorId", rs.getString("assignedAdvisorId"));
			row.put("advisorName", rs.getString("advisorName"));
			return row;
		}).optional();
	}
}

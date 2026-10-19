package com.c21inova.api.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;

@Repository
public class IntegrationJobRepository {

	public record Job(String id, String companyId, String leadId, String type, String status, Object payload, int attempts, String lastError,
			Instant nextRetryAt, String errorKind, String errorCode, Instant lastAttemptAt, Instant resolvedAt, Instant createdAt, Instant updatedAt) {

		public IntegrationNotice.NoticeJob toNotice() {
			return new IntegrationNotice.NoticeJob(id, type, status, attempts, lastError, errorKind, errorCode, lastAttemptAt, nextRetryAt, createdAt,
					payload);
		}
	}

	public static final String COLUMNS = """
			id, "companyId", "leadId", type::text AS type, status::text AS status, payload::text AS payload, attempts, "lastError",
			"nextRetryAt", "errorKind", "errorCode", "lastAttemptAt", "resolvedAt", "createdAt", "updatedAt"
			""";

	public static Job map(ResultSet rs, int row) throws SQLException {
		return new Job(rs.getString("id"), rs.getString("companyId"), rs.getString("leadId"), rs.getString("type"), rs.getString("status"),
				Db.json(rs, "payload"), rs.getInt("attempts"), rs.getString("lastError"), Db.instant(rs, "nextRetryAt"), rs.getString("errorKind"),
				rs.getString("errorCode"), Db.instant(rs, "lastAttemptAt"), Db.instant(rs, "resolvedAt"), Db.instant(rs, "createdAt"),
				Db.instant(rs, "updatedAt"));
	}

	private final JdbcClient jdbc;

	public IntegrationJobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public record NewJob(String companyId, String leadId, String type, Object payload, Instant nextRetryAt, String status, Integer attempts,
			String lastError, String errorKind, String errorCode, Instant lastAttemptAt) {

		public static NewJob pending(String companyId, String leadId, String type, Object payload) {
			return new NewJob(companyId, leadId, type, payload, null, null, null, null, null, null, null);
		}
	}

	public Job create(NewJob data) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		String lastError = data.lastError() == null ? null : data.lastError().length() > 2000 ? data.lastError().substring(0, 2000) : data.lastError();
		jdbc.sql("""
				INSERT INTO integration_jobs (id, "companyId", "leadId", type, status, payload, attempts, "lastError", "nextRetryAt",
				  "errorKind", "errorCode", "lastAttemptAt", "createdAt", "updatedAt")
				VALUES (:id, :companyId, :leadId, CAST(:type AS "IntegrationJobType"), CAST(:status AS "IntegrationJobStatus"), CAST(:payload AS jsonb),
				  :attempts, :lastError, :nextRetryAt, :errorKind, :errorCode, :lastAttemptAt, :now, :now)
				""")
				.param("id", id).param("companyId", data.companyId()).param("leadId", data.leadId()).param("type", data.type())
				.param("status", data.status() != null ? data.status() : "PENDING").param("payload", Db.jsonParam(data.payload()))
				.param("attempts", data.attempts() != null ? data.attempts() : 0).param("lastError", lastError)
				.param("nextRetryAt", Db.ts(data.nextRetryAt() != null ? data.nextRetryAt() : now)).param("errorKind", data.errorKind())
				.param("errorCode", data.errorCode()).param("lastAttemptAt", Db.ts(data.lastAttemptAt())).param("now", Db.ts(now)).update();
		return findById(id).orElseThrow();
	}

	public Optional<Job> findById(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM integration_jobs WHERE id = :id").param("id", id).query(IntegrationJobRepository::map).optional();
	}

	public List<Job> findDue(Instant now, int limit) {
		return jdbc.sql("SELECT " + COLUMNS
				+ " FROM integration_jobs WHERE status IN ('PENDING', 'RETRYING') AND \"nextRetryAt\" <= :now ORDER BY \"nextRetryAt\" ASC LIMIT :limit")
				.param("now", Db.ts(now)).param("limit", limit).query(IntegrationJobRepository::map).list();
	}

	public List<Job> findByLead(String leadId, Integer limit) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM integration_jobs WHERE \"leadId\" = :leadId ORDER BY \"createdAt\" DESC"
				+ (limit != null ? " LIMIT " + limit : "")).param("leadId", leadId).query(IntegrationJobRepository::map).list();
	}

	/**
	 * Toma el trabajo para esta ejecución empujando nextRetryAt, solo si nadie
	 * lo tocó desde que se leyó: dos ejecuciones simultáneas nunca lo corren dos veces.
	 */
	public boolean claim(Job job, Instant leaseUntil) {
		int count = jdbc.sql("""
				UPDATE integration_jobs SET "nextRetryAt" = :lease, "lastAttemptAt" = :now, "updatedAt" = :now
				WHERE id = :id AND status = CAST(:status AS "IntegrationJobStatus") AND attempts = :attempts AND "nextRetryAt" = :nextRetryAt
				""").param("lease", Db.ts(leaseUntil)).param("now", Db.ts(Instant.now())).param("id", job.id()).param("status", job.status())
				.param("attempts", job.attempts()).param("nextRetryAt", Db.ts(job.nextRetryAt())).update();
		return count == 1;
	}

	public void markSucceeded(String id, int attempts) {
		Instant now = Instant.now();
		jdbc.sql("""
				UPDATE integration_jobs SET status = 'SUCCESS', attempts = :attempts, "resolvedAt" = :now, "lastAttemptAt" = :now, "updatedAt" = :now
				WHERE id = :id
				""").param("attempts", attempts).param("now", Db.ts(now)).param("id", id).update();
	}

	public void markCancelled(String id, String reason) {
		Instant now = Instant.now();
		jdbc.sql("""
				UPDATE integration_jobs SET status = 'CANCELLED', "resolvedAt" = :now, "errorKind" = 'SUPERSEDED', "errorCode" = NULL,
				  "lastError" = :reason, "updatedAt" = :now WHERE id = :id
				""").param("now", Db.ts(now)).param("reason", reason.length() > 2000 ? reason.substring(0, 2000) : reason).param("id", id).update();
	}

	public void markFailed(String id, int attempts, String status, String lastError, String errorKind, String errorCode, Instant nextRetryAt) {
		jdbc.sql("""
				UPDATE integration_jobs SET attempts = :attempts, "lastError" = :lastError, status = CAST(:status AS "IntegrationJobStatus"),
				  "errorKind" = :errorKind, "errorCode" = :errorCode, "nextRetryAt" = COALESCE(:nextRetryAt, "nextRetryAt"), "updatedAt" = :now
				WHERE id = :id
				""").param("attempts", attempts).param("lastError", lastError.length() > 2000 ? lastError.substring(0, 2000) : lastError)
				.param("status", status).param("errorKind", errorKind).param("errorCode", errorCode).param("nextRetryAt", Db.ts(nextRetryAt))
				.param("now", Db.ts(Instant.now())).param("id", id).update();
	}

	/** Recuperación manual: solo un trabajo FAILED/UNCERTAIN vuelve a la cola, una vez (conserva attempts). */
	public boolean requeue(String id) {
		Instant now = Instant.now();
		return jdbc.sql("""
				UPDATE integration_jobs SET status = 'PENDING', "nextRetryAt" = :now, "updatedAt" = :now
				WHERE id = :id AND status IN ('FAILED', 'UNCERTAIN')
				""").param("now", Db.ts(now)).param("id", id).update() == 1;
	}
}

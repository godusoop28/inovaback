package com.c21inova.api.advisor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;

@Service
public class AdvisorRoundService {

	private static final Logger log = LoggerFactory.getLogger(AdvisorRoundService.class);

	private final JdbcClient jdbc;

	public AdvisorRoundService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Rondas activas para el motor de asignación. Nunca lanza: si falla, la ruleta funciona como siempre. */
	public List<AdvisorRounds.Schedule> loadSchedules(String companyId) {
		try {
			return jdbc.sql("""
					SELECT r."advisorId", r.weekdays, r."startMinute", r."endMinute", r.active FROM advisor_rounds r
					JOIN advisors a ON a.id = r."advisorId"
					WHERE r."companyId" = :companyId AND r.active = true AND a.active = true
					""").param("companyId", companyId).query((rs, i) -> new AdvisorRounds.Schedule(rs.getString("advisorId"),
					Db.intArray(rs, "weekdays"), rs.getInt("startMinute"), rs.getInt("endMinute"), rs.getBoolean("active"))).list();
		} catch (RuntimeException error) {
			log.error("[advisor-rounds] No se pudieron leer las rondas; se usa la ruleta normal: {}", error.getMessage());
			return List.of();
		}
	}

	public List<Map<String, Object>> list(String companyId) {
		return jdbc.sql("""
				SELECT r.id, r."advisorId", r.weekdays, r."startMinute", r."endMinute", r.active, r.note, r."createdBy", r."createdAt",
				  a.name AS "advisorName", a.active AS "advisorActive", a."pausedUntil" AS "advisorPausedUntil"
				FROM advisor_rounds r JOIN advisors a ON a.id = r."advisorId"
				WHERE r."companyId" = :companyId ORDER BY r."startMinute" ASC, r."createdAt" ASC
				""").param("companyId", companyId).query((rs, i) -> {
			List<Integer> weekdays = Db.intArray(rs, "weekdays");
			int start = rs.getInt("startMinute");
			int end = rs.getInt("endMinute");
			return Json.obj(
					"id", rs.getString("id"),
					"advisorId", rs.getString("advisorId"),
					"weekdays", weekdays,
					"startMinute", start,
					"endMinute", end,
					"start", AdvisorRounds.formatMinute(start),
					"end", AdvisorRounds.formatMinute(end),
					"overnight", end <= start,
					"active", rs.getBoolean("active"),
					"note", rs.getString("note"),
					"createdBy", rs.getString("createdBy"),
					"createdAt", Db.instant(rs, "createdAt"),
					"advisor", Json.obj("id", rs.getString("advisorId"), "name", rs.getString("advisorName"), "active", rs.getBoolean("advisorActive"),
							"pausedUntil", Db.instant(rs, "advisorPausedUntil")));
		}).list();
	}

	public void create(String companyId, String advisorId, List<Integer> weekdays, int startMinute, int endMinute, String note, String by) {
		boolean exists = jdbc.sql("SELECT count(*) FROM advisors WHERE id = :id AND \"companyId\" = :companyId").param("id", advisorId)
				.param("companyId", companyId).query(Integer.class).single() > 0;
		if (!exists) throw new IllegalArgumentException("Asesor no encontrado.");
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO advisor_rounds (id, "companyId", "advisorId", weekdays, "startMinute", "endMinute", active, note, "createdBy", "createdAt", "updatedAt")
				VALUES (:id, :companyId, :advisorId, CAST(:weekdays AS integer[]), :start, :end, true, :note, :by, :now, :now)
				""")
				.param("id", Ids.cuid()).param("companyId", companyId).param("advisorId", advisorId)
				.param("weekdays", Db.arrayLiteral(AdvisorRounds.normalizeWeekdays(weekdays))).param("start", startMinute).param("end", endMinute)
				.param("note", note == null || note.trim().isEmpty() ? null : note.trim()).param("by", by).param("now", Db.ts(now)).update();
	}

	public void setActive(String companyId, String roundId, boolean active) {
		int updated = jdbc.sql("UPDATE advisor_rounds SET active = :active, \"updatedAt\" = :now WHERE id = :id AND \"companyId\" = :companyId")
				.param("active", active).param("now", Db.ts(Instant.now())).param("id", roundId).param("companyId", companyId).update();
		if (updated == 0) throw new IllegalArgumentException("Ronda no encontrada.");
	}

	public void delete(String companyId, String roundId) {
		int deleted = jdbc.sql("DELETE FROM advisor_rounds WHERE id = :id AND \"companyId\" = :companyId").param("id", roundId)
				.param("companyId", companyId).update();
		if (deleted == 0) throw new IllegalArgumentException("Ronda no encontrada.");
	}
}

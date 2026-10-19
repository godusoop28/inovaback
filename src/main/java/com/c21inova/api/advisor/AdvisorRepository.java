package com.c21inova.api.advisor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;

@Repository
public class AdvisorRepository {

	/** Columnas booleanas por ruta (lista cerrada: nunca se interpola un dato del usuario). */
	public static String routeColumn(String route) {
		return switch (route) {
			case "PROPERTY" -> "\"allowedProperty\"";
			case "EXPLORE" -> "\"allowedExplore\"";
			case "CAMPAIGN" -> "\"allowedCampaign\"";
			case "TIMEOUT" -> "\"allowedTimeout\"";
			default -> throw new IllegalArgumentException("Ruta desconocida: " + route);
		};
	}

	public record Upsert(String name, String phone, String easyBrokerEmail, String manyChatSubscriberId, boolean active, int weight,
			Integer dailyLimit, String notes, boolean allowedProperty, boolean allowedExplore, boolean allowedCampaign, boolean allowedTimeout) {
	}

	private final JdbcClient jdbc;

	public AdvisorRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Advisor> findAll(String companyId) {
		return jdbc.sql("SELECT * FROM advisors WHERE \"companyId\" = :companyId ORDER BY name ASC").param("companyId", companyId).query(Advisor::map)
				.list();
	}

	public Optional<Advisor> findById(String id) {
		return jdbc.sql("SELECT * FROM advisors WHERE id = :id").param("id", id).query(Advisor::map).optional();
	}

	public Optional<Advisor> findByEasyBrokerEmail(String companyId, String email) {
		return jdbc.sql("SELECT * FROM advisors WHERE \"companyId\" = :companyId AND lower(\"easyBrokerEmail\") = lower(:email) LIMIT 1")
				.param("companyId", companyId).param("email", email).query(Advisor::map).optional();
	}

	public Advisor create(String companyId, Upsert data) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO advisors (id, "companyId", name, phone, "easyBrokerEmail", "manyChatSubscriberId", active, weight, "dailyLimit", notes,
				  "allowedProperty", "allowedExplore", "allowedCampaign", "allowedTimeout", "leadsToday", "createdAt", "updatedAt")
				VALUES (:id, :companyId, :name, :phone, :ebEmail, :subscriber, :active, :weight, :dailyLimit, :notes,
				  :p, :e, :c, :t, 0, :now, :now)
				""")
				.param("id", id).param("companyId", companyId).param("name", data.name()).param("phone", data.phone())
				.param("ebEmail", data.easyBrokerEmail()).param("subscriber", data.manyChatSubscriberId()).param("active", data.active())
				.param("weight", data.weight()).param("dailyLimit", data.dailyLimit()).param("notes", data.notes())
				.param("p", data.allowedProperty()).param("e", data.allowedExplore()).param("c", data.allowedCampaign()).param("t", data.allowedTimeout())
				.param("now", Db.ts(now)).update();
		return findById(id).orElseThrow();
	}

	public Advisor update(String id, Upsert data) {
		jdbc.sql("""
				UPDATE advisors SET name = :name, phone = :phone, "easyBrokerEmail" = :ebEmail, "manyChatSubscriberId" = :subscriber,
				  active = :active, weight = :weight, "dailyLimit" = :dailyLimit, notes = :notes, "allowedProperty" = :p, "allowedExplore" = :e,
				  "allowedCampaign" = :c, "allowedTimeout" = :t, "updatedAt" = :now
				WHERE id = :id
				""")
				.param("id", id).param("name", data.name()).param("phone", data.phone())
				.param("ebEmail", data.easyBrokerEmail()).param("subscriber", data.manyChatSubscriberId()).param("active", data.active())
				.param("weight", data.weight()).param("dailyLimit", data.dailyLimit()).param("notes", data.notes())
				.param("p", data.allowedProperty()).param("e", data.allowedExplore()).param("c", data.allowedCampaign()).param("t", data.allowedTimeout())
				.param("now", Db.ts(Instant.now())).update();
		return findById(id).orElseThrow();
	}

	public Advisor setActive(String id, boolean active) {
		jdbc.sql("UPDATE advisors SET active = :active, \"updatedAt\" = :now WHERE id = :id").param("id", id).param("active", active)
				.param("now", Db.ts(Instant.now())).update();
		return findById(id).orElseThrow();
	}

	public Advisor setPausedUntil(String id, Instant pausedUntil) {
		jdbc.sql("UPDATE advisors SET \"pausedUntil\" = :until, \"updatedAt\" = :now WHERE id = :id").param("id", id)
				.param("until", Db.ts(pausedUntil)).param("now", Db.ts(Instant.now())).update();
		return findById(id).orElseThrow();
	}

	/** Misma regla que lockEligibleForUpdate, sin bloquear filas: para simular o mostrar. */
	public List<Advisor> findEligibleForRoute(String companyId, String route, Instant now) {
		return jdbc.sql("SELECT * FROM advisors WHERE \"companyId\" = :companyId AND active = true AND weight > 0 AND " + routeColumn(route)
				+ " = true AND (\"pausedUntil\" IS NULL OR \"pausedUntil\" <= :now) ORDER BY id ASC")
				.param("companyId", companyId).param("now", Db.ts(now)).query(Advisor::map).list();
	}

	/**
	 * Bloquea (FOR UPDATE) a todos los asesores elegibles para que dos leads
	 * simultáneos no lean "0 leads hoy" del mismo asesor. Debe correr dentro de
	 * una transacción; ORDER BY id evita interbloqueos.
	 */
	public List<Advisor> lockEligibleForUpdate(String companyId, String route, Instant now) {
		return jdbc.sql("SELECT * FROM advisors WHERE \"companyId\" = :companyId AND active = true AND weight > 0 AND " + routeColumn(route)
				+ " = true AND (\"pausedUntil\" IS NULL OR \"pausedUntil\" <= :now) ORDER BY id FOR UPDATE")
				.param("companyId", companyId).param("now", Db.ts(now)).query(Advisor::map).list();
	}

	public void incrementLeadsTodayCache(String advisorId) {
		jdbc.sql("UPDATE advisors SET \"leadsToday\" = \"leadsToday\" + 1, \"updatedAt\" = :now WHERE id = :id").param("id", advisorId)
				.param("now", Db.ts(Instant.now())).update();
	}
}

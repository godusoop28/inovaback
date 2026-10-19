package com.c21inova.api.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;

/** Fila de "users". El hash de la contraseña nunca sale en una respuesta. */
public record User(String id, String companyId, String name, String email, String passwordHash, String role, boolean active, Instant createdAt,
		Instant updatedAt) {

	public static final String COLUMNS = "id, \"companyId\", name, email, \"passwordHash\", role::text AS role, active, \"createdAt\", \"updatedAt\"";

	public static User map(ResultSet rs, int row) throws SQLException {
		return new User(rs.getString("id"), rs.getString("companyId"), rs.getString("name"), rs.getString("email"), rs.getString("passwordHash"),
				rs.getString("role"), rs.getBoolean("active"), Db.instant(rs, "createdAt"), Db.instant(rs, "updatedAt"));
	}

	public Map<String, Object> toView() {
		return Json.obj("id", id, "name", name, "email", email, "role", role, "active", active, "createdAt", createdAt, "updatedAt", updatedAt);
	}
}

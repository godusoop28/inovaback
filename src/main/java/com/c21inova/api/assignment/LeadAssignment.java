package com.c21inova.api.assignment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import com.c21inova.api.common.Db;

/** Fila de "lead_assignments". */
public record LeadAssignment(
		String id,
		String leadId,
		String advisorId,
		String method,
		int weightAtAssignment,
		String reason,
		String status,
		boolean easyBrokerConfirmed,
		boolean manyChatNotified,
		Instant assignedAt,
		Instant createdAt,
		Instant updatedAt) {

	public static final String COLUMNS = """
			id, "leadId", "advisorId", method::text AS method, "weightAtAssignment", reason, status::text AS status,
			"easyBrokerConfirmed", "manyChatNotified", "assignedAt", "createdAt", "updatedAt"
			""";

	public static LeadAssignment map(ResultSet rs, int row) throws SQLException {
		return new LeadAssignment(
				rs.getString("id"),
				rs.getString("leadId"),
				rs.getString("advisorId"),
				rs.getString("method"),
				rs.getInt("weightAtAssignment"),
				rs.getString("reason"),
				rs.getString("status"),
				rs.getBoolean("easyBrokerConfirmed"),
				rs.getBoolean("manyChatNotified"),
				Db.instant(rs, "assignedAt"),
				Db.instant(rs, "createdAt"),
				Db.instant(rs, "updatedAt"));
	}
}

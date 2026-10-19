package com.c21inova.api.advisor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import com.c21inova.api.common.Db;

/** Fila de "advisors". */
public record Advisor(
		String id,
		String companyId,
		String name,
		String email,
		String phone,
		String easyBrokerEmail,
		String easyBrokerAgentId,
		String manyChatSubscriberId,
		boolean active,
		int weight,
		Integer dailyLimit,
		Instant pausedUntil,
		int leadsToday,
		boolean allowedProperty,
		boolean allowedExplore,
		boolean allowedCampaign,
		boolean allowedTimeout,
		Integer priority,
		String notes,
		Instant createdAt,
		Instant updatedAt) {

	public static Advisor map(ResultSet rs, int row) throws SQLException {
		return new Advisor(
				rs.getString("id"),
				rs.getString("companyId"),
				rs.getString("name"),
				rs.getString("email"),
				rs.getString("phone"),
				rs.getString("easyBrokerEmail"),
				rs.getString("easyBrokerAgentId"),
				rs.getString("manyChatSubscriberId"),
				rs.getBoolean("active"),
				rs.getInt("weight"),
				Db.integer(rs, "dailyLimit"),
				Db.instant(rs, "pausedUntil"),
				rs.getInt("leadsToday"),
				rs.getBoolean("allowedProperty"),
				rs.getBoolean("allowedExplore"),
				rs.getBoolean("allowedCampaign"),
				rs.getBoolean("allowedTimeout"),
				Db.integer(rs, "priority"),
				rs.getString("notes"),
				Db.instant(rs, "createdAt"),
				Db.instant(rs, "updatedAt"));
	}
}

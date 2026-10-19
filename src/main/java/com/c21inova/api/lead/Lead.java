package com.c21inova.api.lead;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import com.c21inova.api.common.Db;

/** Fila de "leads". */
public record Lead(
		String id,
		String companyId,
		String name,
		String phone,
		String interestType,
		String propertyData,
		String origin,
		String route,
		String status,
		String source,
		String easyBrokerContactRequestId,
		String easyBrokerContactId,
		String easyBrokerPropertyId,
		String manyChatSubscriberId,
		String assignedAdvisorId,
		String assignmentStatus,
		String requestId,
		Object rawPayload,
		Instant createdAt,
		Instant updatedAt) {

	public static final String COLUMNS = """
			id, "companyId", name, phone, "interestType"::text AS "interestType", "propertyData", origin, route, status::text AS status, source,
			"easyBrokerContactRequestId", "easyBrokerContactId", "easyBrokerPropertyId", "manyChatSubscriberId", "assignedAdvisorId",
			"assignmentStatus"::text AS "assignmentStatus", "requestId", "rawPayload"::text AS "rawPayload", "createdAt", "updatedAt"
			""";

	public static Lead map(ResultSet rs, int row) throws SQLException {
		return new Lead(
				rs.getString("id"),
				rs.getString("companyId"),
				rs.getString("name"),
				rs.getString("phone"),
				rs.getString("interestType"),
				rs.getString("propertyData"),
				rs.getString("origin"),
				rs.getString("route"),
				rs.getString("status"),
				rs.getString("source"),
				rs.getString("easyBrokerContactRequestId"),
				rs.getString("easyBrokerContactId"),
				rs.getString("easyBrokerPropertyId"),
				rs.getString("manyChatSubscriberId"),
				rs.getString("assignedAdvisorId"),
				rs.getString("assignmentStatus"),
				rs.getString("requestId"),
				Db.json(rs, "rawPayload"),
				Db.instant(rs, "createdAt"),
				Db.instant(rs, "updatedAt"));
	}
}

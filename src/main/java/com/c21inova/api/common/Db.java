package com.c21inova.api.common;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Lectura/escritura de los tipos que crea Prisma en Postgres: TIMESTAMP(3)
 * en UTC, JSONB, enums nativos y arreglos. Los SQL de los repositorios usan
 * CAST(:x AS jsonb) / CAST(:x AS "Enum") / CAST(:x AS "Enum"[]) con estos valores.
 */
public final class Db {

	private Db() {
	}

	public static Timestamp ts(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	public static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	public static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	public static Double dbl(ResultSet rs, String column) throws SQLException {
		double value = rs.getDouble(column);
		return rs.wasNull() ? null : value;
	}

	public static Object json(ResultSet rs, String column) throws SQLException {
		return Json.parse(rs.getString(column));
	}

	public static String jsonParam(Object value) {
		return value == null ? null : Json.write(value);
	}

	public static List<String> stringArray(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		List<String> out = new ArrayList<>();
		if (array == null) return out;
		Object raw = array.getArray();
		if (raw instanceof Object[] items) {
			for (Object item : items) if (item != null) out.add(item.toString());
		}
		return out;
	}

	public static List<Integer> intArray(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		List<Integer> out = new ArrayList<>();
		if (array == null) return out;
		Object raw = array.getArray();
		if (raw instanceof Object[] items) {
			for (Object item : items) if (item instanceof Number n) out.add(n.intValue());
		}
		return out;
	}

	/** Literal de arreglo de Postgres ('{"a","b"}') para usar con CAST(:x AS tipo[]). */
	public static String arrayLiteral(Collection<?> values) {
		StringBuilder out = new StringBuilder("{");
		boolean first = true;
		for (Object value : values) {
			if (!first) out.append(',');
			first = false;
			if (value instanceof Number) {
				out.append(value);
			} else {
				out.append('"').append(value.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
			}
		}
		return out.append('}').toString();
	}

	/** Conteos de un GROUP BY con una columna llave y una columna "n". */
	public static Map<String, Integer> countsByKey(List<Map<String, Object>> rows, String keyColumn) {
		Map<String, Integer> out = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			Object key = row.get(keyColumn);
			Object n = row.get("n");
			out.put(key == null ? null : key.toString(), n instanceof Number num ? num.intValue() : 0);
		}
		return out;
	}
}

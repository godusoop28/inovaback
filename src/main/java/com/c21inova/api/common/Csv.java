package com.c21inova.api.common;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** CSV para Excel, con neutralización de fórmulas (los nombres vienen de WhatsApp). */
public final class Csv {

	public static final String UTF8_BOM = "﻿";
	private static final Pattern FORMULA_TRIGGER = Pattern.compile("^[=+\\-@\\t\\r]");
	private static final Pattern PLAIN_NUMBER = Pattern.compile("^[+-]?[\\d\\s().-]+$");

	private Csv() {
	}

	private static String neutralizeFormula(String value) {
		if (FORMULA_TRIGGER.matcher(value).find() && !PLAIN_NUMBER.matcher(value).matches()) return "'" + value;
		return value;
	}

	private static String escape(String raw) {
		String value = neutralizeFormula(raw == null ? "" : raw);
		if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}

	public static String toCsv(List<List<String>> rows) {
		return rows.stream().map(row -> row.stream().map(Csv::escape).collect(Collectors.joining(","))).collect(Collectors.joining("\r\n"));
	}
}

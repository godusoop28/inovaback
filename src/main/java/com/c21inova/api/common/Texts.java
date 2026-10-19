package com.c21inova.api.common;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto equivalentes a las del panel (normalize("NFD"), slice, trim). */
public final class Texts {

	private Texts() {
	}

	/** Quita acentos y pasa a minúsculas (sin tocar otros caracteres). */
	public static String stripAccentsLower(String text) {
		if (text == null) return "";
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "").toLowerCase(Locale.ROOT);
	}

	/** String.prototype.slice(0, max) seguro ante null. */
	public static String slice(String text, int max) {
		if (text == null) return null;
		return text.length() <= max ? text : text.substring(0, max);
	}

	public static boolean isBlank(String text) {
		return text == null || text.trim().isEmpty();
	}

	/** "valor?.trim() || null". */
	public static String trimToNull(String text) {
		if (text == null) return null;
		String trimmed = text.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public static String firstNonBlank(String... values) {
		for (String value : values) if (!isBlank(value)) return value;
		return null;
	}

	public static String lower(String text) {
		return text == null ? null : text.toLowerCase(Locale.ROOT);
	}

	public static String upper(String text) {
		return text == null ? null : text.toUpperCase(Locale.ROOT);
	}

	/** Colapsa espacios en blanco a uno solo y recorta. */
	public static String oneLine(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").trim();
	}

	private static final java.time.format.DateTimeFormatter ISO = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(java.time.ZoneOffset.UTC);

	/** Igual que Date.prototype.toISOString(): siempre con milisegundos. */
	public static String iso(java.time.Instant instant) {
		return instant == null ? null : ISO.format(instant);
	}

	public static String digits(String text) {
		return text == null ? "" : text.replaceAll("\\D", "");
	}
}

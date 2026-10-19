package com.c21inova.api.common;

import java.util.Map;

/**
 * El External Request de ManyChat interpola campos como {Last Text Input}
 * dentro del JSON tal cual: un mensaje con salto de línea vuelve inválido el
 * cuerpo. Se escapan los caracteres de control solo dentro de cadenas.
 */
public final class LenientJson {

	private static final Map<Character, String> ESCAPES = Map.of('\n', "\\n", '\r', "\\r", '\t', "\\t", '\b', "\\b", '\f', "\\f");

	private LenientJson() {
	}

	public static String escapeControlCharsInStrings(String text) {
		StringBuilder out = new StringBuilder();
		boolean inString = false;
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (ch == '\\') {
					escaped = true;
				} else if (ch == '"') {
					inString = false;
				} else if (ch < ' ') {
					String escape = ESCAPES.get(ch);
					out.append(escape != null ? escape : String.format("\\u%04x", (int) ch));
					continue;
				}
			} else if (ch == '"') {
				inString = true;
			}
			out.append(ch);
		}
		return out.toString();
	}

	/** JSON.parse con un segundo intento escapando controles crudos. Lanza si sigue inválido. */
	public static Object parse(String text) {
		try {
			return Json.parse(text);
		} catch (RuntimeException error) {
			String repaired = escapeControlCharsInStrings(text);
			if (repaired.equals(text)) throw error;
			return Json.parse(repaired);
		}
	}
}

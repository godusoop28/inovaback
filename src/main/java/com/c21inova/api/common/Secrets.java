package com.c21inova.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Secrets {

	private Secrets() {
	}

	/** Comparación en tiempo constante (hashear primero iguala las longitudes). */
	public static boolean matches(String provided, String expected) {
		if (provided == null || provided.isEmpty() || expected == null) return false;
		return MessageDigest.isEqual(sha256(provided), sha256(expected));
	}

	public static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String sha256Hex(String value) {
		StringBuilder hex = new StringBuilder();
		for (byte b : sha256(value)) hex.append(String.format("%02x", b));
		return hex.toString();
	}
}

package com.c21inova.api.common;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IDs con la misma forma que el cuid() de Prisma ("c" + tiempo + contador +
 * aleatorio, base 36, minúsculas), para que los registros creados por este
 * backend convivan con los existentes sin distinguirse.
 */
public final class Ids {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final AtomicInteger COUNTER = new AtomicInteger(RANDOM.nextInt(1_679_616));
	private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

	private Ids() {
	}

	public static String cuid() {
		StringBuilder id = new StringBuilder("c");
		id.append(pad(Long.toString(System.currentTimeMillis(), 36), 8));
		id.append(pad(Integer.toString(Math.floorMod(COUNTER.getAndIncrement(), 1_679_616), 36), 4));
		for (int i = 0; i < 12; i++) id.append(ALPHABET.charAt(RANDOM.nextInt(36)));
		return id.toString();
	}

	public static String randomHex(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		StringBuilder hex = new StringBuilder();
		for (byte b : buffer) hex.append(String.format("%02x", b));
		return hex.toString();
	}

	private static String pad(String value, int length) {
		if (value.length() >= length) return value.substring(value.length() - length);
		return "0".repeat(length - value.length()) + value;
	}
}

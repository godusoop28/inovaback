package com.c21inova.api.common;

/**
 * Normalización E.164 para números de México. EasyBroker, ManyChat y
 * WhatsApp no coinciden en si traen "52" o el "1" antiguo de celular; la
 * base necesita una sola forma para comparar.
 */
public final class Phone {

	private static final String DEFAULT_COUNTRY_CODE = "52";

	private Phone() {
	}

	public static String digitsOnly(String raw) {
		return raw == null ? "" : raw.replaceAll("\\D", "");
	}

	/**
	 * "+52XXXXXXXXXX" en el mejor esfuerzo. Nunca lanza: una entrada que no se
	 * reconoce devuelve "" en vez de un número plausible pero equivocado.
	 */
	public static String normalizeE164(String raw) {
		return normalizeE164(raw, DEFAULT_COUNTRY_CODE);
	}

	public static String normalizeE164(String raw, String countryCode) {
		String digits = digitsOnly(raw);
		if (digits.isEmpty()) return "";
		if (digits.startsWith("00")) digits = digits.substring(2);

		if (digits.length() == 10) {
			digits = countryCode + digits;
		} else if (digits.length() == 11 && digits.startsWith("1") && countryCode.equals("52")) {
			digits = countryCode + digits.substring(1);
		} else if (digits.length() == 10 + countryCode.length() + 1 && digits.startsWith(countryCode + "1")) {
			digits = countryCode + digits.substring(countryCode.length() + 1);
		} else if (digits.length() != 10 + countryCode.length()) {
			if (digits.length() < 10 || digits.length() > 15) return "";
		}
		return "+" + digits;
	}

	/** Mismo número sin importar el formato ("523141255975" vs "+523141255975"). */
	public static boolean same(String a, String b) {
		String left = normalizeE164(a);
		return !left.isEmpty() && left.equals(normalizeE164(b));
	}
}

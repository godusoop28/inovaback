package com.c21inova.api.common;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fechas en America/Mexico_City: días de reporte, límites diarios, pausas y
 * rondas. El servidor corre en UTC; toda cuenta de "hoy" pasa por aquí.
 */
public final class MexicoTime {

	public static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
	public static final Locale ES_MX = Locale.forLanguageTag("es-MX");

	private MexicoTime() {
	}

	public static LocalDate date(Instant instant) {
		return instant.atZone(ZONE).toLocalDate();
	}

	/** "AAAA-MM-DD" del día en Ciudad de México. */
	public static String dateKey(Instant instant) {
		return date(instant).toString();
	}

	public static boolean sameDay(Instant a, Instant b) {
		return dateKey(a).equals(dateKey(b));
	}

	/**
	 * Hora de pared de CDMX → instante UTC. Acepta día fuera de rango (día+1 en
	 * fin de mes) como hace Date.UTC: se normaliza sumando días.
	 */
	public static Instant wallTimeToUtc(int year, int month, int day, int hour, int minute) {
		LocalDate base = LocalDate.of(year, month, 1).plusDays(day - 1L);
		return LocalDateTime.of(base, java.time.LocalTime.MIDNIGHT).plusHours(hour).plusMinutes(minute).atZone(ZONE).toInstant();
	}

	public static Instant startOfDay(LocalDate date) {
		return date.atStartOfDay(ZONE).toInstant();
	}

	public static Instant tomorrowAt(int hour, int minute, Instant reference) {
		LocalDate tomorrow = date(reference).plusDays(1);
		return wallTimeToUtc(tomorrow.getYear(), tomorrow.getMonthValue(), tomorrow.getDayOfMonth(), hour, minute);
	}

	/** Inicio y fin (inclusivo, -1 ms) del día de `reference` en CDMX. */
	public static Instant[] dayRange(Instant reference) {
		LocalDate day = date(reference);
		Instant start = startOfDay(day);
		Instant end = startOfDay(day.plusDays(1)).minusMillis(1);
		return new Instant[] { start, end };
	}

	/** Día ISO (1 = lunes … 7 = domingo) y minuto del día en CDMX. */
	public static int[] weekdayAndMinute(Instant now) {
		ZonedDateTime zoned = now.atZone(ZONE);
		return new int[] { zoned.getDayOfWeek().getValue(), zoned.getHour() * 60 + zoned.getMinute() };
	}

	public static int isoWeekday(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		return day.getValue();
	}

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM, HH:mm", ES_MX);

	/** "04 sep, 09:00" en hora de CDMX (para mostrar). */
	public static String formatDateTime(Instant instant) {
		if (instant == null) return "";
		return DATE_TIME.format(instant.atZone(ZONE)).replace(".", "");
	}

	private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("d/M/yyyy, HH:mm:ss", ES_MX);

	/** Equivalente a toLocaleString("es-MX", { timeZone }) para el prompt. */
	public static String formatLong(Instant instant) {
		return LONG.format(instant.atZone(ZONE));
	}
}

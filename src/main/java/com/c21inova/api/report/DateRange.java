package com.c21inova.api.report;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.c21inova.api.common.MexicoTime;

/**
 * Fuente única de "qué periodo está seleccionado" para tablero, reportes y
 * exportaciones, siempre en hora de Ciudad de México.
 * this_week/this_month: desde el lunes/día 1 hasta ahora (periodo en curso).
 * last_week/last_month: periodos cerrados completos.
 */
public final class DateRange {

	public static final List<String> PRESETS = List.of("today", "this_week", "last_week", "this_month", "last_month", "custom");

	public static final Map<String, String> PRESET_LABELS = Map.of("today", "Hoy", "this_week", "Esta semana", "last_week", "Semana anterior",
			"this_month", "Este mes", "last_month", "Mes anterior", "custom", "Rango personalizado");

	public record Resolved(String preset, Instant startDate, Instant endDate, String label) {
	}

	public static class InvalidDateRangeException extends RuntimeException {
		public InvalidDateRangeException(String message) {
			super(message);
		}
	}

	private DateRange() {
	}

	private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

	static LocalDate parseIsoDate(String value) {
		Matcher match = ISO_DATE.matcher(value.trim());
		if (!match.matches()) throw new InvalidDateRangeException("Fecha inválida: \"" + value + "\". Usa el formato AAAA-MM-DD.");
		try {
			return LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
		} catch (DateTimeException e) {
			throw new InvalidDateRangeException("Fecha inválida: \"" + value + "\".");
		}
	}

	static Instant endOfDay(LocalDate date) {
		return MexicoTime.startOfDay(date.plusDays(1)).minusMillis(1);
	}

	static String labelDate(LocalDate date) {
		return String.format("%02d/%02d/%d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
	}

	public static Resolved resolve(String preset, String from, String to) {
		return resolve(preset, from, to, Instant.now());
	}

	public static Resolved resolve(String preset, String from, String to, Instant now) {
		LocalDate today = MexicoTime.date(now);
		LocalDate monday = today.minusDays(MexicoTime.isoWeekday(today) - 1L);
		switch (preset) {
			case "today":
				return new Resolved(preset, MexicoTime.startOfDay(today), endOfDay(today), PRESET_LABELS.get("today"));
			case "this_week":
				return new Resolved(preset, MexicoTime.startOfDay(monday), now, PRESET_LABELS.get("this_week"));
			case "last_week":
				return new Resolved(preset, MexicoTime.startOfDay(monday.minusDays(7)), endOfDay(monday.minusDays(1)), PRESET_LABELS.get("last_week"));
			case "this_month":
				return new Resolved(preset, MexicoTime.startOfDay(today.withDayOfMonth(1)), now, PRESET_LABELS.get("this_month"));
			case "last_month": {
				LocalDate first = today.withDayOfMonth(1).minusMonths(1);
				LocalDate last = today.withDayOfMonth(1).minusDays(1);
				return new Resolved(preset, MexicoTime.startOfDay(first), endOfDay(last), PRESET_LABELS.get("last_month"));
			}
			case "custom": {
				if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
					throw new InvalidDateRangeException("Selecciona una fecha inicial y una fecha final.");
				}
				LocalDate start = parseIsoDate(from);
				LocalDate end = parseIsoDate(to);
				Instant startDate = MexicoTime.startOfDay(start);
				Instant endDate = endOfDay(end);
				if (startDate.isAfter(endDate)) throw new InvalidDateRangeException("La fecha inicial debe ser anterior o igual a la fecha final.");
				return new Resolved(preset, startDate, endDate, labelDate(start) + " — " + labelDate(end));
			}
			default:
				throw new InvalidDateRangeException("Periodo desconocido: \"" + preset + "\".");
		}
	}

	/** ?range=&from=&to= → periodo (por defecto "today"). */
	public static Resolved fromParams(String range, String from, String to) {
		String preset = range != null && PRESETS.contains(range) ? range : "today";
		if ("custom".equals(preset) && (from == null || to == null)) preset = "today";
		return resolve(preset, from, to);
	}

	/** El periodo inmediatamente anterior, de la misma duración. */
	public static Instant[] previous(Instant startDate, Instant endDate) {
		long length = endDate.toEpochMilli() - startDate.toEpochMilli();
		return new Instant[] { Instant.ofEpochMilli(startDate.toEpochMilli() - length - 1), Instant.ofEpochMilli(startDate.toEpochMilli() - 1) };
	}
}

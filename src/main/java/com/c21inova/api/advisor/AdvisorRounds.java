package com.c21inova.api.advisor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.c21inova.api.common.MexicoTime;

/**
 * Rondas por horario. Mientras una ronda está activa, los leads de la
 * ruleta ponderada se reparten SOLO entre los asesores en ronda que además
 * son elegibles y tienen cupo. Si ninguno puede recibirlo, la ruleta normal
 * sigue con todos: nunca se pierde un lead. La asignación directa no cambia.
 */
public final class AdvisorRounds {

	private AdvisorRounds() {
	}

	public record Schedule(String advisorId, List<Integer> weekdays, int startMinute, int endMinute, boolean active) {
	}

	/** ¿La ronda cubre este instante? Una ronda nocturna (22:00–02:00) pertenece al día en que empieza. */
	public static boolean isActiveAt(Schedule round, Instant now) {
		if (!round.active() || round.weekdays().isEmpty()) return false;
		int[] clock = MexicoTime.weekdayAndMinute(now);
		int weekday = clock[0];
		int minute = clock[1];
		if (round.endMinute() > round.startMinute()) {
			return round.weekdays().contains(weekday) && minute >= round.startMinute() && minute < round.endMinute();
		}
		int previousDay = weekday == 1 ? 7 : weekday - 1;
		return (round.weekdays().contains(weekday) && minute >= round.startMinute())
				|| (round.weekdays().contains(previousDay) && minute < round.endMinute());
	}

	public static Set<String> advisorsOnRound(List<Schedule> rounds, Instant now) {
		Set<String> out = new HashSet<>();
		for (Schedule round : rounds) if (isActiveAt(round, now)) out.add(round.advisorId());
		return out;
	}

	public record Pool<T>(List<T> pool, boolean roundApplied) {
	}

	/** Reduce el grupo de la ruleta a los asesores en ronda con cupo; si nadie puede, devuelve el grupo original. */
	public static <T> Pool<T> applyRounds(List<T> candidates, Function<T, String> idOf, Function<T, Integer> dailyLimitOf,
			Map<String, Integer> todayCounts, Set<String> onRound) {
		if (onRound.isEmpty()) return new Pool<>(candidates, false);
		List<T> available = new ArrayList<>();
		for (T advisor : candidates) {
			String id = idOf.apply(advisor);
			if (!onRound.contains(id)) continue;
			int count = todayCounts.getOrDefault(id, 0);
			Integer limit = dailyLimitOf.apply(advisor);
			if (limit == null || count < limit) available.add(advisor);
		}
		return available.isEmpty() ? new Pool<>(candidates, false) : new Pool<>(available, true);
	}

	public static String formatMinute(int minute) {
		int clamped = Math.max(0, Math.min(1440, minute));
		int h = (clamped / 60) % 24;
		int m = clamped % 60;
		return String.format("%02d:%02d", h, m);
	}

	private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

	/** "HH:MM" → minuto del día. "24:00" se acepta como fin del día. */
	public static Integer parseTimeToMinute(String value) {
		if (value == null) return null;
		Matcher match = TIME.matcher(value.trim());
		if (!match.matches()) return null;
		int h = Integer.parseInt(match.group(1));
		int m = Integer.parseInt(match.group(2));
		if (m > 59 || h > 24 || (h == 24 && m != 0)) return null;
		return h * 60 + m;
	}

	public static List<Integer> normalizeWeekdays(List<Integer> weekdays) {
		return new ArrayList<>(new TreeSet<>(weekdays));
	}
}

package com.c21inova.api.assignment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import com.c21inova.api.advisor.Advisor;

/**
 * Núcleo puro del motor de asignación ponderada: sin base de datos, para
 * poder probarlo. AssignmentService obtiene las filas y llama aquí.
 */
public final class AssignmentEngine {

	private static final double RATIO_EPSILON = 1e-9;

	private AssignmentEngine() {
	}

	public static boolean allowsRoute(Advisor advisor, String route) {
		return switch (route) {
			case "PROPERTY" -> advisor.allowedProperty();
			case "EXPLORE" -> advisor.allowedExplore();
			case "CAMPAIGN" -> advisor.allowedCampaign();
			case "TIMEOUT" -> advisor.allowedTimeout();
			default -> false;
		};
	}

	/** Misma regla que el WHERE de lockEligibleAdvisorsForUpdate. */
	public static boolean isEligibleForRoute(Advisor advisor, String route, Instant now) {
		if (!advisor.active()) return false;
		if (advisor.weight() <= 0) return false;
		if (!allowsRoute(advisor, route)) return false;
		return advisor.pausedUntil() == null || !advisor.pausedUntil().isAfter(now);
	}

	/**
	 * Menos asignado ponderado: el asesor elegible con menor (leads de hoy / peso).
	 * Empates: mayor peso; luego al azar (inyectable para pruebas).
	 */
	public static Advisor pickWeightedLeastAssigned(List<Advisor> candidates, Map<String, Integer> todayCounts, DoubleSupplier random) {
		List<Advisor> eligible = new ArrayList<>();
		for (Advisor advisor : candidates) {
			int count = todayCounts.getOrDefault(advisor.id(), 0);
			if (advisor.dailyLimit() == null || count < advisor.dailyLimit()) eligible.add(advisor);
		}
		if (eligible.isEmpty()) return null;

		double bestRatio = Double.POSITIVE_INFINITY;
		List<Advisor> bestGroup = new ArrayList<>();
		for (Advisor advisor : eligible) {
			double ratio = (double) todayCounts.getOrDefault(advisor.id(), 0) / advisor.weight();
			if (ratio < bestRatio - RATIO_EPSILON) {
				bestRatio = ratio;
				bestGroup = new ArrayList<>(List.of(advisor));
			} else if (Math.abs(ratio - bestRatio) <= RATIO_EPSILON) {
				bestGroup.add(advisor);
			}
		}
		if (bestGroup.size() == 1) return bestGroup.get(0);

		int maxWeight = bestGroup.stream().mapToInt(Advisor::weight).max().orElse(0);
		List<Advisor> top = bestGroup.stream().filter(advisor -> advisor.weight() == maxWeight).toList();
		if (top.size() == 1) return top.get(0);
		return top.get((int) Math.floor(random.getAsDouble() * top.size()));
	}

	public static Advisor pickWeightedLeastAssigned(List<Advisor> candidates, Map<String, Integer> todayCounts) {
		return pickWeightedLeastAssigned(candidates, todayCounts, () -> ThreadLocalRandom.current().nextDouble());
	}
}

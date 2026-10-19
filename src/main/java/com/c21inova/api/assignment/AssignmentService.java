package com.c21inova.api.assignment;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.c21inova.api.advisor.Advisor;
import com.c21inova.api.advisor.AdvisorRepository;
import com.c21inova.api.advisor.AdvisorRoundService;
import com.c21inova.api.advisor.AdvisorRounds;
import com.c21inova.api.common.MexicoTime;

@Service
public class AssignmentService {

	public record Candidate(String id, String name, int weight, int todayCount, Integer dailyLimit) {
	}

	/** ok=false → error "NO_ADVISORS_AVAILABLE". */
	public record Selection(boolean ok, LeadAssignment assignment, Advisor advisor, List<Candidate> candidatesConsidered) {
	}

	public record SimulationCandidates(List<Advisor> candidates, Map<String, Integer> todayCounts, Set<String> onRound) {
	}

	private final AdvisorRepository advisors;
	private final AssignmentRepository assignments;
	private final AdvisorRoundService rounds;
	private final TransactionTemplate tx;

	public AssignmentService(AdvisorRepository advisors, AssignmentRepository assignments, AdvisorRoundService rounds, TransactionTemplate tx) {
		this.advisors = advisors;
		this.assignments = assignments;
		this.rounds = rounds;
		this.tx = tx;
	}

	/**
	 * Elige asesor y registra la asignación de forma atómica, con las filas
	 * candidatas bloqueadas. No llama a EasyBroker/ManyChat aquí: esas
	 * llamadas van después de confirmar la transacción.
	 */
	public Selection selectAndAssign(String companyId, String leadId, String route, String method, Instant nowOrNull) {
		Instant now = nowOrNull != null ? nowOrNull : Instant.now();
		Instant[] day = MexicoTime.dayRange(now);
		// Fuera de la transacción: una consulta fallida dentro la abortaría entera.
		Set<String> onRound = AdvisorRounds.advisorsOnRound(rounds.loadSchedules(companyId), now);

		return tx.execute(status -> {
			List<Advisor> locked = advisors.lockEligibleForUpdate(companyId, route, now);
			Map<String, Integer> todayCounts = assignments.countByAdvisor(locked.stream().map(Advisor::id).toList(), day[0], day[1]);
			AdvisorRounds.Pool<Advisor> pool = AdvisorRounds.applyRounds(locked, Advisor::id, Advisor::dailyLimit, todayCounts, onRound);
			Advisor picked = AssignmentEngine.pickWeightedLeastAssigned(pool.pool(), todayCounts);

			List<Candidate> considered = locked.stream()
					.map(a -> new Candidate(a.id(), a.name(), a.weight(), todayCounts.getOrDefault(a.id(), 0), a.dailyLimit()))
					.toList();
			if (picked == null) return new Selection(false, null, null, considered);

			double ratio = (double) todayCounts.getOrDefault(picked.id(), 0) / picked.weight();
			String ratioText = String.format(Locale.ROOT, "%.3f", ratio);
			String reason = pool.roundApplied()
					? "ronda por horario: weighted-least-assigned entre " + pool.pool().size() + " asesor(es) en ronda de " + locked.size()
							+ " candidato(s); ratio=" + ratioText
					: "weighted-least-assigned entre " + locked.size() + " candidato(s); ratio=" + ratioText;
			LeadAssignment assignment = assignments.create(leadId, picked.id(), method, picked.weight(), "ASSIGNED", reason);
			advisors.incrementLeadsTodayCache(picked.id());
			return new Selection(true, assignment, picked, considered);
		});
	}

	/** Asignación directa (la propiedad tiene su propio asesor): sin ruleta ni bloqueo. */
	public LeadAssignment assignDirect(String leadId, String advisorId, int advisorWeight, String method, String reason) {
		return tx.execute(status -> {
			LeadAssignment assignment = assignments.create(leadId, advisorId, method, advisorWeight, "ASSIGNED", reason);
			advisors.incrementLeadsTodayCache(advisorId);
			return assignment;
		});
	}

	/** Conteos del día para una lista de asesores (panel de asesores, tablero). */
	public Map<String, Integer> dailyCounts(List<String> advisorIds, Instant now) {
		Instant[] day = MexicoTime.dayRange(now);
		return assignments.countByAdvisor(advisorIds, day[0], day[1]);
	}

	/** Candidatos y conteos de una ruta, solo lectura y sin bloqueos (simulador de distribución). */
	public SimulationCandidates rotationCandidatesForSimulation(String companyId, String route, Instant now) {
		List<Advisor> candidates = advisors.findEligibleForRoute(companyId, route, now);
		Set<String> onRound = AdvisorRounds.advisorsOnRound(rounds.loadSchedules(companyId), now);
		Map<String, Integer> todayCounts = dailyCounts(candidates.stream().map(Advisor::id).toList(), now);
		return new SimulationCandidates(candidates, todayCounts, onRound);
	}
}

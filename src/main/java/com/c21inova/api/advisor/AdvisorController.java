package com.c21inova.api.advisor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.assignment.AssignmentEngine;
import com.c21inova.api.assignment.AssignmentService;
import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.MexicoTime;

/** Asesores: ADMIN y DIRECCION. */
@RestController
@RequestMapping("/api/advisors")
public class AdvisorController {

	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	private final AdvisorService advisors;
	private final AdvisorRoundService rounds;
	private final AssignmentService assignments;

	public AdvisorController(AdvisorService advisors, AdvisorRoundService rounds, AssignmentService assignments) {
		this.advisors = advisors;
		this.rounds = rounds;
		this.assignments = assignments;
	}

	private static SessionUser access() {
		return SessionUser.requireRole("ADMIN", "DIRECCION");
	}

	/** Misma validación que AdvisorInputSchema del panel. */
	@SuppressWarnings("unchecked")
	static AdvisorService.AdvisorInput parseInput(Map<String, Object> body) {
		if (body == null) throw ApiException.badRequest("Cuerpo JSON inválido.");
		String nombre = body.get("nombre") instanceof String s ? s.trim() : "";
		if (nombre.length() < 2) throw ApiException.unprocessable("El nombre debe tener al menos 2 caracteres.");
		String whatsapp = body.get("whatsapp") instanceof String s ? s.trim() : "";
		if (whatsapp.length() < 8) throw ApiException.unprocessable("El WhatsApp debe incluir lada y número.");
		whatsapp = whatsapp.replaceAll("\\s+", "");
		String email = body.get("emailEasyBroker") instanceof String s ? s.trim().toLowerCase() : "";
		if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw ApiException.unprocessable("Correo inválido.");
		String manyChatId = body.get("manyChatId") instanceof String s ? s.trim() : "";
		boolean activo = Boolean.TRUE.equals(body.get("activo"));
		int peso;
		try {
			Object raw = body.get("peso");
			double value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw));
			if (value != Math.rint(value)) throw ApiException.unprocessable("El peso debe ser un número entero.");
			peso = (int) value;
		} catch (NumberFormatException e) {
			throw ApiException.unprocessable("El peso debe ser un número entero.");
		}
		if (peso <= 0) throw ApiException.unprocessable("El peso debe ser mayor a 0.");
		List<String> rutas = new ArrayList<>();
		if (body.get("rutasPermitidas") instanceof List<?> list) {
			for (Object route : list) {
				if (!(route instanceof String r) || !AdvisorRules.LEAD_ROUTES.contains(r)) throw ApiException.unprocessable("Ruta inválida.");
				rutas.add(r);
			}
		}
		Integer limite = null;
		Object rawLimit = body.get("limiteDiario");
		if (rawLimit != null && !"".equals(rawLimit)) {
			try {
				double value = rawLimit instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(rawLimit));
				if (value != Math.rint(value) || value <= 0) throw ApiException.unprocessable("El límite diario debe ser un entero positivo.");
				limite = (int) value;
			} catch (NumberFormatException e) {
				throw ApiException.unprocessable("El límite diario debe ser un entero positivo.");
			}
		}
		String observaciones = body.get("observaciones") instanceof String s ? s.trim() : "";
		if (observaciones.length() > 500) throw ApiException.unprocessable("Máximo 500 caracteres.");
		return new AdvisorService.AdvisorInput(nombre, whatsapp, email, manyChatId, activo, peso, rutas, limite, observaciones);
	}

	/** Lista con leads de hoy (conteo real desde las asignaciones) y rondas. */
	@GetMapping
	public Map<String, Object> list() {
		SessionUser user = access();
		List<Map<String, Object>> views = advisors.listViews(user.companyId());
		Map<String, Integer> today = assignments.dailyCounts(views.stream().map(v -> (String) v.get("id")).toList(), Instant.now());
		List<Map<String, Object>> withCounts = new ArrayList<>();
		for (Map<String, Object> view : views) {
			Map<String, Object> copy = new LinkedHashMap<>(view);
			copy.put("leadsHoy", today.getOrDefault((String) view.get("id"), 0));
			withCounts.add(copy);
		}
		Object roundList;
		String roundError = null;
		try {
			roundList = rounds.list(user.companyId());
		} catch (RuntimeException error) {
			roundList = List.of();
			roundError = "No se pudieron cargar las rondas: " + error.getMessage();
		}
		return Json.obj("ok", true, "advisors", withCounts, "rounds", roundList, "roundsError", roundError);
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
		SessionUser user = access();
		Advisor advisor = advisors.create(user.companyId(), parseInput(body));
		return ResponseEntity.status(HttpStatus.CREATED).body(Json.obj("ok", true, "advisor", AdvisorRules.toView(advisor)));
	}

	@GetMapping("/{id}")
	public Map<String, Object> get(@PathVariable String id) {
		SessionUser user = access();
		return Json.obj("ok", true, "advisor", AdvisorRules.toView(advisors.require(user.companyId(), id)));
	}

	@PatchMapping("/{id}")
	public Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
		SessionUser user = access();
		return Json.obj("ok", true, "advisor", AdvisorRules.toView(advisors.update(user.companyId(), id, parseInput(body))));
	}

	/** Baja lógica: active=false, nunca DELETE físico. */
	@DeleteMapping("/{id}")
	public Map<String, Object> deactivate(@PathVariable String id) {
		SessionUser user = access();
		return Json.obj("ok", true, "advisor", AdvisorRules.toView(advisors.setActive(user.companyId(), id, false)));
	}

	public record ActiveRequest(Boolean active) {
	}

	@PatchMapping("/{id}/active")
	public Map<String, Object> setActive(@PathVariable String id, @RequestBody ActiveRequest body) {
		SessionUser user = access();
		if (body == null || body.active() == null) throw ApiException.badRequest("active es obligatorio.");
		return Json.obj("ok", true, "advisor", AdvisorRules.toView(advisors.setActive(user.companyId(), id, body.active())));
	}

	public record PauseRequest(String mode, String customDateTime) {
	}

	private static final Pattern LOCAL_DATE_TIME = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})");

	/** mode: 1h | tomorrow_9am | indefinite | custom (customDateTime "2026-09-04T09:00", hora de CDMX). */
	@PostMapping("/{id}/pause")
	public Map<String, Object> pause(@PathVariable String id, @RequestBody PauseRequest body) {
		SessionUser user = access();
		if (body == null || body.mode() == null) throw ApiException.unprocessable("Datos de pausa inválidos.");
		Instant until = switch (body.mode()) {
			case "1h" -> Instant.now().plusSeconds(3600);
			case "tomorrow_9am" -> MexicoTime.tomorrowAt(9, 0, Instant.now());
			case "indefinite" -> null;
			case "custom" -> {
				if (body.customDateTime() == null || body.customDateTime().trim().isEmpty()) throw ApiException.unprocessable("Selecciona fecha y hora.");
				Matcher m = LOCAL_DATE_TIME.matcher(body.customDateTime().trim());
				if (!m.find()) throw ApiException.unprocessable("Fecha y hora de pausa inválidas.");
				yield MexicoTime.wallTimeToUtc(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
						Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
			}
			default -> throw ApiException.unprocessable("Datos de pausa inválidos.");
		};
		return Json.obj("ok", true, "advisor", AdvisorRules.toView(advisors.pause(user.companyId(), id, until)));
	}

	@PostMapping("/{id}/resume")
	public Map<String, Object> resume(@PathVariable String id) {
		SessionUser user = access();
		return Json.obj("ok", true, "advisor", AdvisorRules.toView(advisors.resume(user.companyId(), id)));
	}

	public record SimulateRequest(String route, Integer iterations) {
	}

	/**
	 * Corre la elección ponderada muchas veces en memoria contra una sola
	 * lectura de candidatos. Nunca crea leads ni llama a EasyBroker/ManyChat.
	 */
	@PostMapping("/simulate")
	public Map<String, Object> simulate(@RequestBody SimulateRequest body) {
		SessionUser user = access();
		if (body == null || body.route() == null || !AdvisorRules.LEAD_ROUTES.contains(body.route())) throw ApiException.unprocessable("Ruta inválida.");
		int iterations = body.iterations() == null ? 0 : body.iterations();
		if (iterations < 1 || iterations > 1000) throw ApiException.unprocessable("Las iteraciones deben estar entre 1 y 1000.");
		AssignmentService.SimulationCandidates sim = assignments.rotationCandidatesForSimulation(user.companyId(),
				AdvisorRules.ROUTE_LABEL_TO_CODE.get(body.route()), Instant.now());
		if (sim.candidates().isEmpty()) return Json.obj("ok", false, "error", "No hay asesores disponibles para esta ruta.", "totalCandidates", 0);
		Map<String, Integer> counts = new HashMap<>();
		Map<String, Integer> simulated = new HashMap<>(sim.todayCounts());
		for (int i = 0; i < iterations; i++) {
			// Misma regla que el motor real: con rondas activas, solo asesores en ronda con cupo.
			Advisor picked = AssignmentEngine.pickWeightedLeastAssigned(
					AdvisorRounds.applyRounds(sim.candidates(), Advisor::id, Advisor::dailyLimit, simulated, sim.onRound()).pool(), simulated);
			if (picked != null) {
				counts.merge(picked.id(), 1, Integer::sum);
				simulated.merge(picked.id(), 1, Integer::sum);
			}
		}
		List<Map<String, Object>> results = sim.candidates().stream()
				.map(a -> Json.obj("id", a.id(), "nombre", a.name(), "count", counts.getOrDefault(a.id(), 0), "onRound", sim.onRound().contains(a.id())))
				.sorted((a, b) -> (Integer) b.get("count") - (Integer) a.get("count")).toList();
		return Json.obj("ok", true, "results", results, "totalCandidates", sim.candidates().size(), "roundActive",
				results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("onRound"))));
	}

	// --- Rondas por horario --------------------------------------------------------------------

	@GetMapping("/rounds")
	public Map<String, Object> listRounds() {
		SessionUser user = access();
		return Json.obj("ok", true, "rounds", rounds.list(user.companyId()));
	}

	public record RoundRequest(String advisorId, List<Integer> weekdays, String start, String end, String note) {
	}

	@PostMapping("/rounds")
	public ResponseEntity<Map<String, Object>> createRound(@RequestBody RoundRequest body) {
		SessionUser user = access();
		if (body == null || body.advisorId() == null || body.advisorId().isEmpty()) throw ApiException.unprocessable("Elige un asesor.");
		List<Integer> weekdays = body.weekdays() == null ? List.of() : body.weekdays().stream().filter(d -> d != null && d >= 1 && d <= 7).toList();
		if (weekdays.isEmpty()) throw ApiException.unprocessable("Elige al menos un día.");
		Integer startMinute = AdvisorRounds.parseTimeToMinute(body.start() == null ? "" : body.start());
		Integer endMinute = AdvisorRounds.parseTimeToMinute(body.end() == null ? "" : body.end());
		if (startMinute == null || endMinute == null) throw ApiException.unprocessable("Indica hora de inicio y de fin.");
		if (startMinute >= 1440) throw ApiException.unprocessable("La hora de inicio debe ser antes de las 24:00.");
		if (startMinute.equals(endMinute)) throw ApiException.unprocessable("La hora de inicio y la de fin no pueden ser iguales.");
		String note = body.note() == null ? "" : body.note().length() > 200 ? body.note().substring(0, 200) : body.note();
		rounds.create(user.companyId(), body.advisorId(), weekdays, startMinute, endMinute == 0 ? 1440 : endMinute, note, user.email());
		return ResponseEntity.status(HttpStatus.CREATED).body(Json.obj("ok", true, "message", "Ronda agregada."));
	}

	@PatchMapping("/rounds/{roundId}")
	public Map<String, Object> toggleRound(@PathVariable String roundId, @RequestBody ActiveRequest body) {
		SessionUser user = access();
		if (body == null || body.active() == null) throw ApiException.badRequest("active es obligatorio.");
		rounds.setActive(user.companyId(), roundId, body.active());
		return Json.obj("ok", true);
	}

	@DeleteMapping("/rounds/{roundId}")
	public Map<String, Object> deleteRound(@PathVariable String roundId) {
		SessionUser user = access();
		rounds.delete(user.companyId(), roundId);
		return Json.obj("ok", true);
	}
}

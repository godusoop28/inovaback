package com.c21inova.api.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.c21inova.api.common.Json;

/**
 * Cómo se explica en el panel el estado de una acción de integración
 * (IntegrationJob). Nunca incluye tokens ni el payload.
 */
public final class IntegrationNotice {

	private IntegrationNotice() {
	}

	/** Campos de un IntegrationJob que necesita el panel. */
	public record NoticeJob(String id, String type, String status, int attempts, String lastError, String errorKind, String errorCode,
			Instant lastAttemptAt, Instant nextRetryAt, Instant createdAt, Object payload) {
	}

	public static final Map<String, String> JOB_ACTION_LABELS = Map.of(
			"MANYCHAT_FLOW", "Aviso al asesor por WhatsApp (flujo de ManyChat)",
			"MANYCHAT_FIELDS", "Actualizar datos del contacto en ManyChat",
			"EASYBROKER_CREATE", "Crear contacto en EasyBroker",
			"EASYBROKER_ASSIGN", "Asignar asesor en EasyBroker");

	private static final Map<String, String> KIND_EXPLANATIONS = Map.ofEntries(
			Map.entry("CONFIG", "Falta configuración indispensable."),
			Map.entry("AUTH", "ManyChat rechazó la autenticación o los permisos del token."),
			Map.entry("FLOW", "ManyChat rechazó el flujo configurado (inexistente, mal escrito o sin publicar)."),
			Map.entry("CONTACT", "ManyChat rechazó el contacto destinatario para esta operación."),
			Map.entry("CHANNEL", "El canal no permitió el envío (p. ej. ventana de 24 h de WhatsApp o contacto sin opt-in)."),
			Map.entry("RATE_LIMIT", "ManyChat limitó la cantidad de solicitudes."),
			Map.entry("PROVIDER", "Error temporal del proveedor."),
			Map.entry("TIMEOUT", "ManyChat no respondió a tiempo."),
			Map.entry("NETWORK", "Falló la conexión con ManyChat."),
			Map.entry("REJECTED", "ManyChat rechazó la solicitud."),
			Map.entry("LOCAL", "Error interno al procesar la acción."),
			Map.entry("SUPERSEDED", "La acción ya no aplica."));

	private static final Set<String> CONFIG_KINDS = Set.of("CONFIG", "AUTH", "FLOW");

	public static boolean isOpenJob(String status) {
		return "PENDING".equals(status) || "RETRYING".equals(status) || "FAILED".equals(status) || "UNCERTAIN".equals(status);
	}

	public static String title(String status, String errorKind) {
		return switch (status) {
			case "PENDING" -> "Automatización pendiente";
			case "RETRYING" -> "Reintento programado";
			case "UNCERTAIN" -> "Resultado de envío incierto";
			case "FAILED" -> errorKind != null && CONFIG_KINDS.contains(errorKind) ? "Requiere revisar la configuración"
					: "No se pudo ejecutar la automatización";
			case "SUCCESS" -> "Automatización confirmada";
			default -> "Automatización descartada";
		};
	}

	public static String reason(String status, String errorKind, String lastError) {
		String explanation = errorKind != null ? KIND_EXPLANATIONS.get(errorKind) : null;
		String detail = lastError != null && !lastError.trim().isEmpty() ? lastError.trim() : null;
		if ("UNCERTAIN".equals(status)) return (detail != null ? detail : "Sin respuesta del proveedor.") + " Verifica en ManyChat si llegó antes de reenviar.";
		if (explanation != null && detail != null && !detail.startsWith(explanation)) return explanation + " " + detail;
		if (explanation != null) return explanation;
		return detail != null ? detail : "Sin detalle registrado.";
	}

	/**
	 * Aviso vigente de un lead: por cada tipo de acción manda el trabajo más
	 * reciente; `confirmedTypes` cubre recuperaciones por otra vía.
	 */
	public static Map<String, Object> current(List<NoticeJob> jobs, Function<NoticeJob, String> targetFor, Set<String> confirmedTypes) {
		Map<String, NoticeJob> latestByType = new LinkedHashMap<>();
		for (NoticeJob job : jobs) {
			NoticeJob current = latestByType.get(job.type());
			if (current == null || job.createdAt().isAfter(current.createdAt())) latestByType.put(job.type(), job);
		}
		List<NoticeJob> open = new ArrayList<>();
		for (NoticeJob job : latestByType.values()) {
			if (isOpenJob(job.status()) && !confirmedTypes.contains(job.type())) open.add(job);
		}
		if (open.isEmpty()) return null;
		open.sort(Comparator.comparingInt(IntegrationNotice::rank));
		NoticeJob job = open.get(0);
		boolean scheduled = "RETRYING".equals(job.status()) || "PENDING".equals(job.status());
		return Json.obj(
				"jobId", job.id(),
				"tone", scheduled ? "warning" : "danger",
				"title", title(job.status(), job.errorKind()),
				"action", JOB_ACTION_LABELS.getOrDefault(job.type(), job.type()),
				"target", targetFor != null ? targetFor.apply(job) : "—",
				"lastAttemptAt", com.c21inova.api.common.Texts.iso(job.lastAttemptAt()),
				"attempts", job.attempts(),
				"reason", reason(job.status(), job.errorKind(), job.lastError()),
				"code", job.errorCode(),
				"nextRetryAt", scheduled ? com.c21inova.api.common.Texts.iso(job.nextRetryAt()) : null);
	}

	private static int rank(NoticeJob job) {
		return switch (job.status()) {
			case "FAILED" -> 0;
			case "UNCERTAIN" -> 1;
			case "RETRYING" -> 2;
			default -> 3;
		};
	}

	/** Mensaje de auditoría de un intento fallido. */
	public static String failureAuditMessage(String type, String status, String errorKind, String errorCode, String reason, int attempts, Instant nextRetryAt) {
		String title = title(status, errorKind);
		String action = JOB_ACTION_LABELS.getOrDefault(type, type);
		String next = nextRetryAt != null ? "; próximo intento " + com.c21inova.api.common.Texts.iso(nextRetryAt) : "RETRYING".equals(status) ? "" : "; sin reintento automático";
		return action + ": " + title + ". " + reason + (errorCode != null ? " [" + errorCode + "]" : "") + " (intento " + attempts + next + ").";
	}

	/** Destino legible de un trabajo sin exponer IDs completos. */
	public static String target(String type, Object payload, String advisorName, String advisorSubscriberId) {
		if (type.startsWith("EASYBROKER")) return "EasyBroker";
		String subscriberId = Json.str(Json.asMap(payload), "subscriberId");
		if (advisorName != null && subscriberId != null && subscriberId.equals(advisorSubscriberId)) return "Asesor " + advisorName + " (ManyChat)";
		String suffix = subscriberId != null ? subscriberId.substring(Math.max(0, subscriberId.length() - 4)) : "";
		return !suffix.isEmpty() ? "Contacto ManyChat de un asesor anterior (…" + suffix + ")" : "Contacto ManyChat";
	}

	private static final Set<String> JOB_AUDIT_EVENTS = Set.of("INTEGRATION_JOB_FAILED", "RETRY_SCHEDULED");

	public record AuditRow(String eventType, String message, Instant createdAt) {
	}

	/** Primer error del historial que NO está explicado por un trabajo de integración. */
	public static String uncoveredAuditError(List<AuditRow> logs, List<NoticeJob> jobs, boolean assignmentNotified) {
		for (AuditRow log : logs) {
			if (JOB_AUDIT_EVENTS.contains(log.eventType())) continue;
			if ("MANYCHAT_NOTIFICATION_FAILED".equals(log.eventType())) {
				Instant at = log.createdAt();
				boolean covered = assignmentNotified
						|| jobs.stream().anyMatch(job -> "MANYCHAT_FLOW".equals(job.type()) && !job.createdAt().isBefore(at));
				if (covered) continue;
			}
			return log.message() == null ? "" : log.message();
		}
		return "";
	}

	// --- Recuperación selectiva de avisos MANYCHAT_FLOW ---------------------------

	public record Recovery(String category, String action) {
	}

	public static final Set<String> REQUEUEABLE = Set.of("PERMANENT_FAILURE", "UNCERTAIN");
	public static final Set<String> CLOSABLE = Set.of("RECOVERED_STALE_NOTICE", "SUPERSEDED");

	public static Recovery categorizeRecovery(String jobStatus, String jobSubscriberId, boolean leadExists, String assignedAdvisorId,
			String assignedAdvisorSubscriberId, boolean noticeConfirmed) {
		if (!leadExists || assignedAdvisorId == null) return new Recovery("UNASSIGNED", "assign_manually");
		if (noticeConfirmed) return new Recovery("RECOVERED_STALE_NOTICE", "close");
		if (jobSubscriberId == null ? assignedAdvisorSubscriberId != null : !jobSubscriberId.equals(assignedAdvisorSubscriberId)) {
			return new Recovery("SUPERSEDED", "close");
		}
		if ("UNCERTAIN".equals(jobStatus)) return new Recovery("UNCERTAIN", "review_then_requeue");
		if ("FAILED".equals(jobStatus)) return new Recovery("PERMANENT_FAILURE", "review_then_requeue");
		return new Recovery("ASSIGNED_NOTICE_PENDING", "wait");
	}
}

package com.c21inova.api.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.c21inova.api.assignment.AssignmentRepository;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.Retry;
import com.c21inova.api.integration.IntegrationJobRepository.Job;
import com.c21inova.api.integration.IntegrationJobRepository.NewJob;
import com.c21inova.api.integration.ManyChatErrors.ManyChatApiException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatConfigException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatNetworkException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatTimeoutException;
import com.c21inova.api.lead.AdvisorNotice;
import com.c21inova.api.lead.LeadRepository;

/**
 * Cola de reintentos de EasyBroker/ManyChat: 1 min, 5 min, 15 min, 1 h y
 * luego FAILED visible. Solo los errores recuperables se reintentan.
 */
@Service
public class RetryService {

	private static final Logger log = LoggerFactory.getLogger(RetryService.class);

	public static final List<Integer> BACKOFF_MINUTES = List.of(1, 5, 15, 60);
	private static final long JOB_LEASE_MS = 10 * 60 * 1000;

	public record Outcome(String status, Instant nextRetryAt) {
	}

	public record RunSummary(int processed, int succeeded, int failed, int rescheduled, int uncertain, int cancelled) {
	}

	private sealed interface JobResult permits Done, Cancelled {
	}

	private record Done(String message) implements JobResult {
	}

	private record Cancelled(String reason) implements JobResult {
	}

	private final IntegrationJobRepository jobs;
	private final LeadRepository leads;
	private final AssignmentRepository assignments;
	private final EasyBrokerService easyBroker;
	private final ManyChatService manyChat;
	private final AuditService audit;

	public RetryService(IntegrationJobRepository jobs, LeadRepository leads, AssignmentRepository assignments, EasyBrokerService easyBroker,
			ManyChatService manyChat, AuditService audit) {
		this.jobs = jobs;
		this.leads = leads;
		this.assignments = assignments;
		this.easyBroker = easyBroker;
		this.manyChat = manyChat;
		this.audit = audit;
	}

	// --- Encolar ------------------------------------------------------------------

	/** assign: asesor a confirmar en EasyBroker cuando el contacto exista (si el create falló). */
	public void enqueueEasyBrokerCreate(String companyId, String leadId, Map<String, Object> payload) {
		jobs.create(NewJob.pending(companyId, leadId, "EASYBROKER_CREATE", payload));
		audit.log(companyId, leadId, null, "RETRY_SCHEDULED", "ok", "Reintento programado: crear contact_request en EasyBroker.");
	}

	public void enqueueEasyBrokerAssign(String companyId, String leadId, Map<String, Object> payload) {
		jobs.create(NewJob.pending(companyId, leadId, "EASYBROKER_ASSIGN", payload));
		audit.log(companyId, leadId, null, "RETRY_SCHEDULED", "ok", "Reintento programado: asignar asesor en EasyBroker.");
	}

	/**
	 * Registra el aviso al asesor que falló con su clasificación real: solo un
	 * fallo recuperable queda en la cola; uno permanente queda FAILED y uno
	 * incierto UNCERTAIN, sin reintentos que no van a funcionar o duplicarían.
	 */
	public void enqueueManyChatFlow(String companyId, String leadId, Map<String, Object> payload, Throwable firstError) {
		Instant now = Instant.now();
		ManyChatErrors.Failure failure = classifyFailure("MANYCHAT_FLOW", firstError);
		Outcome outcome = decideOutcome(failure, 1, now);
		Job job = jobs.create(new NewJob(companyId, leadId, "MANYCHAT_FLOW", payload, outcome.nextRetryAt() != null ? outcome.nextRetryAt() : now,
				outcome.status(), 1, failure.reason(), failure.kind(), failure.code(), now));
		boolean retrying = "RETRYING".equals(outcome.status());
		audit.log(companyId, leadId, Json.str(payload, "advisorId"), retrying ? "RETRY_SCHEDULED" : "INTEGRATION_JOB_FAILED", retrying ? "warning" : "error",
				IntegrationNotice.failureAuditMessage("MANYCHAT_FLOW", outcome.status(), failure.kind(), failure.code(), failure.reason(), 1,
						outcome.nextRetryAt()),
				Json.obj("jobId", job.id(), "errorKind", failure.kind(), "errorCode", failure.code()));
	}

	// --- Clasificación --------------------------------------------------------------

	private static String endpointOf(Throwable error) {
		if (error instanceof ManyChatApiException api) return api.endpoint;
		if (error instanceof ManyChatTimeoutException timeout) return timeout.path;
		if (error instanceof ManyChatNetworkException network) return network.path;
		return null;
	}

	/** ManyChat según HTTP + cuerpo; EasyBroker: todo fallo es recuperable hasta agotar intentos. */
	public static ManyChatErrors.Failure classifyFailure(String type, Throwable error) {
		if ("MANYCHAT_FLOW".equals(type) || "MANYCHAT_FIELDS".equals(type)) {
			String endpoint = endpointOf(error);
			boolean idempotent = "MANYCHAT_FIELDS".equals(type) || !(endpoint != null ? endpoint : "/sending/").contains("/sending/");
			return ManyChatErrors.classify(error, idempotent);
		}
		String message = error.getMessage() != null ? error.getMessage() : "Error desconocido";
		return new ManyChatErrors.Failure("PROVIDER", true, false, "EASYBROKER", message.length() > 300 ? message.substring(0, 300) : message, null);
	}

	/** Espera progresiva solo para errores recuperables; respeta Retry-After si pide esperar más. */
	public static Outcome decideOutcome(ManyChatErrors.Failure failure, int attempts, Instant now) {
		if (failure.uncertain()) return new Outcome("UNCERTAIN", null);
		if (!failure.retryable()) return new Outcome("FAILED", null);
		Instant backoff = Retry.nextBackoffRetryAt(attempts, BACKOFF_MINUTES, now);
		if (backoff == null) return new Outcome("FAILED", null);
		Instant retryAfter = failure.retryAfterMs() != null ? now.plusMillis(failure.retryAfterMs()) : null;
		return new Outcome("RETRYING", retryAfter != null && retryAfter.isAfter(backoff) ? retryAfter : backoff);
	}

	// --- Ejecución -------------------------------------------------------------------

	static List<AdvisorNotice.Field> fieldsOf(Object raw) {
		List<AdvisorNotice.Field> fields = new ArrayList<>();
		for (Map<String, Object> field : Json.objects(raw)) {
			Integer fieldId = Json.integer(field, "fieldId");
			if (fieldId != null) fields.add(new AdvisorNotice.Field(fieldId, Json.str(field, "value") == null ? "" : Json.str(field, "value")));
		}
		return fields;
	}

	/** Solo el paso pendiente: reescribe los campos de ESTE lead y lanza el flujo. Nunca toca asesor ni EasyBroker. */
	private JobResult runManyChatFlow(Job job) {
		Map<String, Object> payload = Json.asMap(job.payload());
		String subscriberId = Json.str(payload, "subscriberId");
		String assignmentId = Json.str(payload, "assignmentId");
		if (job.leadId() != null) {
			AssignmentRepository.LeadNoticeState state = assignments.findLeadNoticeState(job.leadId()).orElse(null);
			if (state == null) return new Cancelled("El lead ya no existe; el aviso pendiente se descarta.");
			if (state.assignedAdvisorSubscriberId() == null ? subscriberId != null : !state.assignedAdvisorSubscriberId().equals(subscriberId)) {
				return new Cancelled("El lead ya tiene otro asesor asignado; el aviso pendiente al asesor anterior se descarta.");
			}
			boolean current = state.assignmentId() != null && state.assignmentAdvisorId() != null
					&& state.assignmentAdvisorId().equals(state.assignedAdvisorId());
			if (current && state.assignmentNotified()) return new Cancelled("El aviso al asesor ya estaba confirmado; no se reenvía.");
			if (assignmentId == null && current) assignmentId = state.assignmentId();
		}
		String flowNs = Json.str(payload, "flowNs");
		if (flowNs == null || flowNs.isEmpty()) {
			throw new ManyChatConfigException("MANYCHAT_ADVISOR_FLOW_ID", "Falta MANYCHAT_ADVISOR_FLOW_ID: no hay flujo configurado para avisar al asesor.");
		}
		List<AdvisorNotice.Field> fields = fieldsOf(payload.get("fields"));
		if (!fields.isEmpty()) manyChat.setCustomFields(subscriberId, AdvisorNotice.fitFields(fields));
		manyChat.sendFlow(subscriberId, flowNs);
		// ManyChat ya aceptó: un fallo local al anotarlo no debe volver fallido el trabajo.
		if (job.leadId() != null && assignmentId != null) {
			try {
				assignments.markNotified(job.leadId(), assignmentId);
			} catch (RuntimeException error) {
				log.error("[RETRY] aviso enviado pero no se pudo marcar la asignación {}", job.id(), error);
			}
		}
		return new Done("Aviso al asesor recuperado: ManyChat aceptó el flujo.");
	}

	private JobResult runJob(Job job) {
		Map<String, Object> payload = Json.asMap(job.payload());
		switch (job.type()) {
			case "EASYBROKER_CREATE" -> {
				EasyBrokerService.ContactRequest result = easyBroker.createContactRequest(Json.str(payload, "name"), Json.str(payload, "phone"),
						Json.str(payload, "message"), Json.str(payload, "source"), Json.str(payload, "propertyId"));
				if (job.leadId() != null && result.id() != null) leads.update(job.leadId(), "easyBrokerContactRequestId", result.id());
				Map<String, Object> assign = Json.asMap(payload.get("assign"));
				if (assign != null && job.leadId() != null) {
					Map<String, Object> next = new LinkedHashMap<>();
					next.put("phone", Json.str(payload, "phone"));
					next.put("source", Json.str(payload, "source"));
					if (payload.get("propertyId") != null) next.put("propertyId", payload.get("propertyId"));
					next.putAll(assign);
					enqueueEasyBrokerAssign(job.companyId(), job.leadId(), next);
				}
				return new Done(null);
			}
			case "EASYBROKER_ASSIGN" -> {
				String contactId = Json.str(payload, "contactId");
				if (contactId == null) {
					EasyBrokerService.ContactRequest found = easyBroker.findRecentContactRequest(Json.str(payload, "phone"), Json.str(payload, "source"),
							Json.str(payload, "propertyId"));
					if (found == null || found.contactId() == null) throw new IllegalStateException("El contacto todavía no aparece en EasyBroker; se reintentará.");
					contactId = found.contactId();
					if (job.leadId() != null) leads.update(job.leadId(), "easyBrokerContactId", contactId);
				}
				easyBroker.assignContactToAdvisor(contactId, Json.str(payload, "advisorEmail"));
				String assignmentId = Json.str(payload, "assignmentId");
				if (assignmentId != null) assignments.updateStatus(assignmentId, "CONFIRMED", true, null);
				return new Done(null);
			}
			case "MANYCHAT_FIELDS" -> {
				manyChat.setCustomFields(Json.str(payload, "subscriberId"), fieldsOf(payload.get("fields")));
				return new Done(null);
			}
			case "MANYCHAT_FLOW" -> {
				return runManyChatFlow(job);
			}
			default -> throw new IllegalStateException("Tipo de trabajo desconocido: " + job.type());
		}
	}

	/** Lo llama el cron. Nunca lanza: cada fallo queda aislado y cada trabajo se toma de forma atómica. */
	public RunSummary processDue(Instant now) {
		List<Job> due = jobs.findDue(now, 20);
		int processed = 0, succeeded = 0, failed = 0, rescheduled = 0, uncertain = 0, cancelled = 0;
		for (Job job : due) {
			if (!jobs.claim(job, Instant.now().plusMillis(JOB_LEASE_MS))) continue;
			processed++;
			int attempts = job.attempts() + 1;
			JobResult result;
			try {
				result = runJob(job);
			} catch (RuntimeException error) {
				ManyChatErrors.Failure failure = classifyFailure(job.type(), error);
				Outcome outcome = decideOutcome(failure, attempts, Instant.now());
				jobs.markFailed(job.id(), attempts, outcome.status(), failure.reason(), failure.kind(), failure.code(), outcome.nextRetryAt());
				if ("RETRYING".equals(outcome.status())) rescheduled++;
				else if ("UNCERTAIN".equals(outcome.status())) uncertain++;
				else failed++;
				audit.log(job.companyId(), job.leadId(), null, "INTEGRATION_JOB_FAILED", "RETRYING".equals(outcome.status()) ? "warning" : "error",
						IntegrationNotice.failureAuditMessage(job.type(), outcome.status(), failure.kind(), failure.code(), failure.reason(), attempts,
								outcome.nextRetryAt()),
						Json.obj("jobId", job.id(), "errorKind", failure.kind(), "errorCode", failure.code()));
				continue;
			}
			if (result instanceof Cancelled c) {
				jobs.markCancelled(job.id(), c.reason());
				cancelled++;
				audit.log(job.companyId(), job.leadId(), null, "INTEGRATION_JOB_RETRIED", "skipped", c.reason(), Json.obj("jobId", job.id()));
				continue;
			}
			jobs.markSucceeded(job.id(), attempts);
			succeeded++;
			String message = ((Done) result).message();
			audit.log(job.companyId(), job.leadId(), null, "MANYCHAT_FLOW".equals(job.type()) ? "MANYCHAT_NOTIFICATION_SENT" : "INTEGRATION_JOB_RETRIED", "ok",
					message != null ? message : "Reintento de " + job.type() + " exitoso (intento " + attempts + ").", Json.obj("jobId", job.id()));
		}
		return new RunSummary(processed, succeeded, failed, rescheduled, uncertain, cancelled);
	}
}

package com.c21inova.api.conversation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.property.PropertyCatalogService;

/** Conversaciones del asistente. Operar: ADMIN y DIRECCION. Configurar y simular: solo ADMIN. */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

	private final ConversationAdminService admin;
	private final AssistantSettingsService settings;
	private final ConversationService conversations;
	private final ConversationAsync async;
	private final PropertyCatalogService catalog;

	public ConversationController(ConversationAdminService admin, AssistantSettingsService settings, ConversationService conversations,
			ConversationAsync async, PropertyCatalogService catalog) {
		this.admin = admin;
		this.settings = settings;
		this.conversations = conversations;
		this.async = async;
		this.catalog = catalog;
	}

	private static SessionUser operator() {
		return SessionUser.requireRole("ADMIN", "DIRECCION");
	}

	@GetMapping
	public Map<String, Object> list(@RequestParam(defaultValue = "all") String filter, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int pageSize) {
		SessionUser user = operator();
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(admin.list(user.companyId(), filter, q, Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
		return out;
	}

	@GetMapping("/attention")
	public Map<String, Object> attention() {
		SessionUser user = operator();
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(admin.attentionCounts(user.companyId()));
		return out;
	}

	@GetMapping("/escalations")
	public Map<String, Object> escalations(@RequestParam(defaultValue = "50") int limit) {
		SessionUser user = operator();
		return Json.obj("ok", true, "escalations", admin.openEscalations(user.companyId(), Math.min(Math.max(1, limit), 200)));
	}

	@GetMapping("/advisors")
	public Map<String, Object> advisorThreads(@RequestParam(required = false) String q) {
		SessionUser user = operator();
		return Json.obj("ok", true, "threads", admin.advisorThreads(user.companyId(), q));
	}

	@GetMapping("/advisors/{advisorId}")
	public Map<String, Object> advisorThread(@PathVariable String advisorId) {
		SessionUser user = operator();
		Map<String, Object> thread = admin.advisorThread(user.companyId(), advisorId);
		if (thread == null) throw ApiException.notFound("Asesor no encontrado.");
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(thread);
		return out;
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable String id) {
		SessionUser user = operator();
		Map<String, Object> detail = admin.detail(user.companyId(), id);
		if (detail == null) throw ApiException.notFound("Conversación no encontrada.");
		return Json.obj("ok", true, "conversation", detail);
	}

	public record ControlRequest(String control, String reason) {
	}

	@PostMapping("/{id}/control")
	public Map<String, Object> setControl(@PathVariable String id, @RequestBody ControlRequest body) {
		SessionUser user = operator();
		if (body == null || (!"HUMAN".equals(body.control()) && !"PAUSED".equals(body.control()))) throw ApiException.unprocessable("Control inválido.");
		String reason = body.reason() == null ? "" : body.reason().trim();
		if (reason.length() < 3) throw ApiException.unprocessable("Indica el motivo.");
		if (reason.length() > 500) throw ApiException.unprocessable("Máximo 500 caracteres.");
		admin.setControl(user.companyId(), id, body.control(), reason, user.email());
		return Json.obj("ok", true, "message", "La IA dejó de responder a este contacto.");
	}

	public record ResumeRequest(Boolean answerPending) {
	}

	@PostMapping("/{id}/resume")
	public Map<String, Object> resume(@PathVariable String id, @RequestBody(required = false) ResumeRequest body) {
		SessionUser user = operator();
		boolean answerPending = body != null && Boolean.TRUE.equals(body.answerPending());
		boolean willAnswer = admin.resume(user.companyId(), id, user.email(), answerPending);
		if (willAnswer) async.process(id);
		return Json.obj("ok", true, "message", answerPending ? "IA reanudada; responderá los mensajes pendientes." : "IA reanudada desde el próximo mensaje.");
	}

	public record HumanReplyRequest(String text) {
	}

	@PostMapping("/{id}/human-reply")
	public Map<String, Object> humanReply(@PathVariable String id, @RequestBody HumanReplyRequest body) {
		SessionUser user = operator();
		String text = body == null || body.text() == null ? "" : body.text().trim();
		if (text.length() < 2) throw ApiException.unprocessable("Escribe lo que se respondió al cliente.");
		admin.recordHumanReply(user.companyId(), id, text, user.email());
		return Json.obj("ok", true, "message", "Respuesta registrada en la memoria de la conversación (no se envió nada al cliente).");
	}

	public record ResolveRequest(String notes) {
	}

	@PostMapping("/escalations/{escalationId}/resolve")
	public Map<String, Object> resolve(@PathVariable String escalationId, @RequestBody(required = false) ResolveRequest body) {
		SessionUser user = operator();
		String conversationId = admin.resolveEscalation(user.companyId(), escalationId, user.email(),
				body == null || body.notes() == null || body.notes().isEmpty() ? null : body.notes());
		return Json.obj("ok", true, "conversationId", conversationId);
	}

	public record RequeueRequest(Boolean confirmUncertain) {
	}

	@PostMapping("/messages/{messageId}/requeue")
	public Map<String, Object> requeue(@PathVariable String messageId, @RequestBody(required = false) RequeueRequest body) {
		SessionUser user = operator();
		String conversationId = admin.requeueMessage(user.companyId(), messageId, body != null && Boolean.TRUE.equals(body.confirmUncertain()));
		async.flush(conversationId);
		return Json.obj("ok", true, "conversationId", conversationId);
	}

	// --- Configuración del asistente (ADMIN) ------------------------------------------------------

	@GetMapping("/settings")
	public Map<String, Object> getSettings() {
		SessionUser user = operator();
		AssistantSettingsService.Settings current = settings.get(user.companyId());
		Map<String, Object> view = current.toView();
		view.put("effectiveMode", settings.effectiveMode(current));
		return Json.obj("ok", true, "settings", view);
	}

	public record SettingsRequest(String mode, Integer debounceSeconds, Integer maxWaitSeconds, Integer maxClarifications, Integer abandonHandoffMinutes,
			Integer existingLeadWindowDays, Integer handoffReopenMinutes, Object testSubscriberIds, Object managementSubscriberIds) {
	}

	private static int range(Integer value, int min, int max, String name) {
		if (value == null || value < min || value > max) throw ApiException.unprocessable(name + " debe estar entre " + min + " y " + max + ".");
		return value;
	}

	/** IDs de ManyChat: lista o texto separado por comas/espacios; solo dígitos, sin duplicados. */
	static List<String> idList(Object raw) {
		List<String> pieces = new ArrayList<>();
		if (raw instanceof List<?> list) for (Object item : list) pieces.add(String.valueOf(item));
		else if (raw != null) pieces.addAll(List.of(String.valueOf(raw).split("[\\s,;]+")));
		return new ArrayList<>(new LinkedHashSet<>(pieces.stream().map(String::trim).filter(id -> id.matches("^\\d{1,20}$")).toList()));
	}

	@PutMapping("/settings")
	public Map<String, Object> saveSettings(@RequestBody SettingsRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		if (body == null || body.mode() == null || !List.of("OFF", "TEST_ONLY", "ON").contains(body.mode())) throw ApiException.unprocessable("Modo inválido.");
		int debounce = range(body.debounceSeconds(), 2, 20, "El silencio de agrupación");
		int maxWait = range(body.maxWaitSeconds(), 5, 60, "La espera máxima");
		if (maxWait < debounce) throw ApiException.unprocessable("La espera máxima debe ser mayor o igual al silencio de agrupación.");
		AssistantSettingsService.Settings saved = settings.update(user.companyId(), new AssistantSettingsService.Update(body.mode(), debounce, maxWait,
				range(body.maxClarifications(), 1, 6, "Las aclaraciones"), range(body.abandonHandoffMinutes(), 0, 24 * 60, "La canalización por abandono"),
				range(body.existingLeadWindowDays(), 1, 365, "La ventana de lead existente"), range(body.handoffReopenMinutes(), 0, 24 * 60, "La espera tras canalizar"),
				idList(body.testSubscriberIds()), idList(body.managementSubscriberIds())), user.email());
		return Json.obj("ok", true, "message", "Configuración guardada.", "settings", saved.toView());
	}

	@PostMapping("/catalog/sync")
	public Map<String, Object> syncCatalog() {
		SessionUser user = SessionUser.requireRole("ADMIN");
		PropertyCatalogService.SyncSummary summary = catalog.syncCatalog(user.companyId());
		Map<String, Object> portals = catalog.syncPortalListings(user.companyId());
		return Json.obj("ok", true, "message", "Inventario sincronizado: " + summary.total() + " publicadas, " + summary.detailsFetched()
				+ " detalles actualizados, " + summary.unpublished() + " despublicadas; " + portals.get("saved") + " anuncios en portales.");
	}

	public record SimulateRequest(String text, String subscriberId, String name) {
	}

	/**
	 * Simulador: conversación de prueba que recorre el pipeline real (ráfagas,
	 * OpenAI, EasyBroker de solo lectura) pero nunca envía WhatsApp ni crea leads.
	 */
	@PostMapping("/simulate")
	public Map<String, Object> simulate(@RequestBody SimulateRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		String text = body == null || body.text() == null ? "" : body.text().trim();
		if (text.isEmpty()) throw ApiException.unprocessable("Escribe un mensaje.");
		String subscriberId = body.subscriberId() == null ? "" : body.subscriberId().trim();
		if (!subscriberId.matches("^sim-[a-f0-9]{12}$")) subscriberId = Conversation.SIMULATOR_SUBSCRIBER_PREFIX + Ids.randomHex(6);
		String name = body.name() == null || body.name().trim().isEmpty() ? "Contacto simulado" : body.name().trim();
		ConversationService.IngestResult result = conversations.ingest(user.companyId(),
				new ConversationService.Inbound(subscriberId, text, "+520000000000", name, null, null, true, Json.obj("simulator", true)));
		if (!result.duplicate() && result.shouldProcess()) async.process(result.conversationId());
		return Json.obj("ok", true, "conversationId", result.conversationId(), "subscriberId", subscriberId);
	}
}

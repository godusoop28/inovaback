package com.c21inova.api.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.CompanyService;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.LenientJson;
import com.c21inova.api.conversation.ConversationAsync;
import com.c21inova.api.conversation.ConversationService;
import com.c21inova.api.lead.Lead;
import com.c21inova.api.lead.LeadRepository;
import com.c21inova.api.lead.LeadRules;
import com.c21inova.api.lead.LeadService;
import com.c21inova.api.property.PropertySearchService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Entradas de ManyChat (External Request). Los nombres de campo y las formas
 * de respuesta son las mismas que ya usan los flujos de ManyChat: solo hay
 * que cambiar la URL base en ManyChat.
 */
@RestController
@RequestMapping("/api/webhooks/manychat")
public class ManyChatWebhookController {

	private static final Logger log = LoggerFactory.getLogger(ManyChatWebhookController.class);

	/** Un lead por teléfono cada 24 h: varios mensajes deben llegar a UN asesor. */
	private static final int PHONE_DEDUP_WINDOW_HOURS = 24;
	private static final int MAX_BODY_BYTES = 16 * 1024;
	/** Más de esto por minuto y contacto no es una persona escribiendo. */
	private static final int MAX_MESSAGES_PER_MINUTE = 30;

	private final IntegrationSecrets secrets;
	private final CompanyService companies;
	private final AuditService audit;
	private final WebhookEventService webhookEvents;
	private final LeadService leadService;
	private final LeadRepository leads;
	private final ConversationService conversations;
	private final ConversationAsync async;
	private final PropertySearchService propertySearch;
	private final JdbcClient jdbc;

	public ManyChatWebhookController(IntegrationSecrets secrets, CompanyService companies, AuditService audit, WebhookEventService webhookEvents,
			LeadService leadService, LeadRepository leads, ConversationService conversations, ConversationAsync async, PropertySearchService propertySearch,
			JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.companies = companies;
		this.audit = audit;
		this.webhookEvents = webhookEvents;
		this.leadService = leadService;
		this.leads = leads;
		this.conversations = conversations;
		this.async = async;
		this.propertySearch = propertySearch;
	}

	private static String text(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null) return null;
		String text = value.toString().trim();
		return text;
	}

	private static String optionalText(Map<String, Object> body, String key) {
		String value = text(body, key);
		return value == null || value.isEmpty() ? null : value;
	}

	// --- Leads ----------------------------------------------------------------------------------

	/**
	 * Alta de lead en tiempo real. Acepta nombre, telefono_cliente,
	 * interes_cliente, datos_propiedad, origen, subscriber_id /
	 * manychat_subscriber_id, requestId / request_id, titulo_propiedad, url_propiedad.
	 */
	@PostMapping("/lead")
	public ResponseEntity<Map<String, Object>> lead(HttpServletRequest request, @RequestBody(required = false) String rawBody) {
		ResponseEntity<Map<String, Object>> denied = secrets.checkIntegration(request);
		if (denied != null) return denied;
		String companyId = companies.defaultCompanyId();
		String raw = rawBody == null ? "" : rawBody;

		Map<String, Object> body;
		try {
			// ManyChat pega {Last Text Input} crudo: un salto de línea vuelve inválido el JSON.
			body = Json.asMap(LenientJson.parse(raw));
			if (body == null) throw new IllegalArgumentException("no es objeto");
		} catch (RuntimeException invalid) {
			audit.log(companyId, null, null, "WEBHOOK_REJECTED", "error", "Webhook de lead rechazado: cuerpo JSON inválido.",
					Json.obj("rawBody", raw.length() > 2000 ? raw.substring(0, 2000) : raw));
			return ResponseEntity.badRequest().body(Json.obj("ok", false, "error", Json.obj("code", "INVALID_BODY", "message", "Cuerpo JSON inválido.")));
		}

		String nombre = text(body, "nombre");
		String telefono = text(body, "telefono_cliente");
		String interes = text(body, "interes_cliente");
		List<Map<String, Object>> issues = new java.util.ArrayList<>();
		if (nombre == null || nombre.isEmpty()) issues.add(Json.obj("path", List.of("nombre"), "message", "nombre es obligatorio."));
		if (telefono == null || telefono.isEmpty()) issues.add(Json.obj("path", List.of("telefono_cliente"), "message", "telefono_cliente es obligatorio."));
		if (interes == null || interes.isEmpty()) issues.add(Json.obj("path", List.of("interes_cliente"), "message", "interes_cliente es obligatorio."));
		if (!issues.isEmpty()) {
			audit.log(companyId, null, null, "WEBHOOK_REJECTED", "error", "Webhook de lead rechazado: payload inválido.",
					Json.obj("issues", issues, "rawBody", raw.length() > 2000 ? raw.substring(0, 2000) : raw));
			return ResponseEntity.unprocessableEntity()
					.body(Json.obj("ok", false, "error", Json.obj("code", "INVALID_BODY", "message", "Payload inválido.", "issues", issues)));
		}
		String datos = text(body, "datos_propiedad") == null ? "" : text(body, "datos_propiedad");
		String origen = text(body, "origen") == null ? "" : text(body, "origen");
		String subscriberId = optionalText(body, "subscriber_id") != null ? optionalText(body, "subscriber_id") : optionalText(body, "manychat_subscriber_id");
		String explicitRequestId = optionalText(body, "requestId") != null ? optionalText(body, "requestId") : optionalText(body, "request_id");
		String requestId = explicitRequestId != null ? explicitRequestId : LeadRules.fingerprint(telefono, interes, datos, origen, Instant.now());

		Map<String, Object> payload = Json.obj("nombre", nombre, "telefono_cliente", telefono, "interes_cliente", interes, "datos_propiedad", datos, "origen",
				origen);
		for (String key : List.of("subscriber_id", "manychat_subscriber_id", "request_id", "requestId", "titulo_propiedad", "url_propiedad")) {
			if (text(body, key) != null) payload.put(key, text(body, key));
		}
		WebhookEventService.Delivery delivery = webhookEvents.record("manychat", subscriberId != null ? subscriberId + ":" + requestId : null, "lead", payload,
				requestId);

		if (delivery.duplicateLead()) {
			Lead existing = leads.findByRequestId(requestId).orElse(null);
			audit.log(companyId, existing == null ? null : existing.id(), null, "LEAD_DUPLICATE_SKIPPED", "skipped",
					"Webhook repetido para requestId " + requestId + "; no se creó un lead nuevo ni se repitieron llamadas externas.");
			webhookEvents.markProcessed(delivery.webhookEventId(), null);
			return ResponseEntity.ok(Json.obj("ok", true, "duplicate", true, "leadId", existing == null ? null : existing.id(), "status",
					existing == null ? null : existing.status()));
		}

		try {
			LeadService.Result result = leadService.processIncomingLead(companyId,
					new LeadService.IncomingLead(nombre, telefono, interes, datos, origen, subscriberId, requestId, optionalText(body, "titulo_propiedad"),
							optionalText(body, "url_propiedad"), null),
					"manychat_webhook", requestId, null, PHONE_DEDUP_WINDOW_HOURS);
			webhookEvents.markProcessed(delivery.webhookEventId(), null);
			Map<String, Object> response = Json.obj("ok", true);
			response.putAll(result.toJson());
			return ResponseEntity.ok(response);
		} catch (DuplicateKeyException concurrent) {
			// Dos entregas del mismo requestId a la vez: la perdedora choca con la clave única.
			Lead existing = leads.findByRequestId(requestId).orElse(null);
			audit.log(companyId, existing == null ? null : existing.id(), null, "LEAD_DUPLICATE_SKIPPED", "skipped",
					"Webhook concurrente para requestId " + requestId + "; ya existía un lead con este requestId.");
			webhookEvents.markProcessed(delivery.webhookEventId(), null);
			return ResponseEntity.ok(Json.obj("ok", true, "duplicate", true, "leadId", existing == null ? null : existing.id(), "status",
					existing == null ? null : existing.status()));
		} catch (LeadService.DuplicatePhoneLeadException duplicate) {
			Lead existing = duplicate.existing;
			audit.log(companyId, existing.id(), null, "LEAD_DUPLICATE_SKIPPED", "skipped", "Mensaje adicional del mismo teléfono en menos de "
					+ PHONE_DEDUP_WINDOW_HOURS + " h; no se creó otro lead ni se notificó a otro asesor.",
					Json.obj("interesCliente", interes, "datosPropiedad", datos, "origen", origen, "requestId", requestId));
			webhookEvents.markProcessed(delivery.webhookEventId(), null);
			return ResponseEntity.ok(Json.obj("ok", true, "duplicate", true, "leadId", existing.id(), "status", existing.status()));
		} catch (RuntimeException error) {
			String message = error.getMessage() != null ? error.getMessage() : "Error desconocido";
			log.error("[WEBHOOK lead] pipeline falló", error);
			webhookEvents.markProcessed(delivery.webhookEventId(), message);
			// Un lead en PROCESSING nunca se volvería a mover (el reintento se deduplica): queda FAILED, visible en el panel.
			Lead stuck = null;
			try {
				stuck = leads.findByRequestId(requestId).orElse(null);
			} catch (RuntimeException ignored) {
				// Sin lead que marcar.
			}
			if (stuck != null && ("PROCESSING".equals(stuck.status()) || "RECEIVED".equals(stuck.status()))) {
				try {
					leads.update(stuck.id(), "status", "FAILED");
				} catch (RuntimeException ignored) {
					// Se reporta abajo igual.
				}
				audit.log(companyId, stuck.id(), null, "LEAD_FAILED", "error", "El procesamiento del lead falló: " + message);
			}
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Json.obj("ok", false, "error", Json.obj("code", "INTERNAL_ERROR", "message", "No se pudo procesar el lead.")));
		}
	}

	// --- Mensajes del asistente --------------------------------------------------------------------

	private static final Pattern SUBSCRIBER_ID = Pattern.compile("^\\d{1,20}$");

	/** {{variable}} sin reemplazar, "" o null → ausente. */
	private static String manyChatText(Object value) {
		if (value == null) return null;
		String text = value.toString().trim();
		if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) text = String.valueOf(number.longValue());
		return text.isEmpty() || text.matches("^\\{\\{.*\\}\\}$") ? null : text;
	}

	/**
	 * Entrada del asistente: solo autentica, valida y PERSISTE, y responde de
	 * inmediato { ok, handled: "true" | "false" }. "false" = el asistente no
	 * atiende a este contacto y ManyChat debe seguir con el flujo anterior.
	 */
	@PostMapping("/message")
	public ResponseEntity<Map<String, Object>> message(HttpServletRequest request, @RequestBody(required = false) String rawBody) {
		ResponseEntity<Map<String, Object>> denied = secrets.checkIntegration(request);
		if (denied != null) return denied;
		String raw = rawBody == null ? "" : rawBody;
		if (raw.length() > MAX_BODY_BYTES) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Json.obj("ok", false, "handled", "false", "error", "BODY_TOO_LARGE"));
		}
		Map<String, Object> body;
		try {
			body = Json.asMap(LenientJson.parse(raw));
			if (body == null) throw new IllegalArgumentException("no es objeto");
		} catch (RuntimeException invalid) {
			return ResponseEntity.badRequest().body(Json.obj("ok", false, "handled", "false", "error", "INVALID_BODY"));
		}
		// "{Full Contact Data}" de ManyChat: el objeto Subscriber (id, name, last_input_text, whatsapp_phone…).
		if (body.get("subscriber_id") == null && body.get("id") != null) {
			Object phone = body.get("whatsapp_phone") != null && !"".equals(body.get("whatsapp_phone")) ? body.get("whatsapp_phone") : body.get("phone");
			body = Json.obj("subscriber_id", body.get("id"), "text", body.get("last_input_text"), "phone", phone, "name", body.get("name"), "last_interaction",
					body.get("last_interaction"));
		}
		String companyId = companies.defaultCompanyId();
		String subscriberId = manyChatText(body.get("subscriber_id"));
		if (subscriberId == null || !SUBSCRIBER_ID.matcher(subscriberId).matches()) {
			audit.log(companyId, null, null, "WEBHOOK_REJECTED", "error", "Mensaje de conversación rechazado: payload inválido.",
					Json.obj("issues", List.of(Json.obj("path", List.of("subscriber_id"), "message", "subscriber_id inválido."))));
			return ResponseEntity.unprocessableEntity().body(Json.obj("ok", false, "handled", "false", "error", "INVALID_PAYLOAD"));
		}

		int recent = countRecentMessages(companyId, subscriberId);
		if (recent >= MAX_MESSAGES_PER_MINUTE) {
			// handled=true: que el flujo anterior no conteste una ráfaga abusiva.
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Json.obj("ok", false, "handled", "true", "error", "RATE_LIMITED"));
		}

		String textValue = manyChatText(body.get("text"));
		ConversationService.IngestResult result = conversations.ingest(companyId, new ConversationService.Inbound(subscriberId,
				// Imágenes/audios llegan sin texto: se registran igual para no perder el turno.
				textValue != null ? textValue : "[El cliente envió un mensaje sin texto (imagen, audio o archivo)]", manyChatText(body.get("phone")),
				manyChatText(body.get("name")), manyChatText(body.get("message_id")), manyChatText(body.get("last_interaction")), false, null));
		if (!result.duplicate() && result.shouldProcess()) async.process(result.conversationId());
		return ResponseEntity.ok(Json.obj("ok", true, "handled", result.handled() ? "true" : "false", "duplicate", result.duplicate()));
	}

	private int countRecentMessages(String companyId, String subscriberId) {
		return jdbc.sql("""
				SELECT count(*)::int FROM conversation_messages m JOIN conversations c ON c.id = m."conversationId"
				WHERE m."companyId" = :co AND m.role = 'USER' AND m."createdAt" >= :since AND c."manyChatSubscriberId" = :s
				""").param("co", companyId).param("since", Db.ts(Instant.now().minusSeconds(60))).param("s", subscriberId).query(Integer.class).single();
	}

	// --- Búsqueda de propiedades -----------------------------------------------------------------------

	/** Respuesta plana opcion_N_id/titulo/url: la misma que ya mapea el flujo de ManyChat. */
	private static Map<String, Object> flatten(List<PropertySearchService.Option> options) {
		Map<String, Object> flat = new java.util.LinkedHashMap<>();
		for (int i = 0; i < 3; i++) {
			PropertySearchService.Option option = i < options.size() ? options.get(i) : null;
			flat.put("opcion_" + (i + 1) + "_id", option == null ? "" : option.id());
			flat.put("opcion_" + (i + 1) + "_titulo", option == null ? "" : option.titulo());
			flat.put("opcion_" + (i + 1) + "_url", option == null || option.url() == null ? "" : option.url());
		}
		return flat;
	}

	@PostMapping("/property-search")
	public ResponseEntity<Map<String, Object>> propertySearch(HttpServletRequest request, @RequestBody(required = false) String rawBody) {
		ResponseEntity<Map<String, Object>> denied = secrets.checkIntegration(request);
		if (denied != null) return denied;
		Map<String, Object> body;
		try {
			body = Json.asMap(Json.parse(rawBody == null ? "" : rawBody));
			if (body == null) throw new IllegalArgumentException();
		} catch (RuntimeException invalid) {
			return ResponseEntity.badRequest().body(Json.obj("resultado", "error", "mensaje", "Cuerpo JSON inválido."));
		}
		String query = text(body, "busqueda_propiedad");
		if (query == null || query.isEmpty()) {
			return ResponseEntity.unprocessableEntity().body(Json.obj("resultado", "error", "mensaje", "Payload inválido.", "issues",
					List.of(Json.obj("path", List.of("busqueda_propiedad"), "message", "busqueda_propiedad es obligatoria."))));
		}
		long startedAt = System.currentTimeMillis();
		String companyId = companies.defaultCompanyId();
		try {
			PropertySearchService.Result result = propertySearch.search(query);
			long elapsed = System.currentTimeMillis() - startedAt;
			String status = !result.opciones().isEmpty() ? ("keyword".equals(result.metodo()) ? "warning" : "ok") : "warning";
			audit.log(companyId, null, null, "PROPERTY_SEARCH", status, "Búsqueda \"" + query + "\": " + result.resultado() + " (" + result.opciones().size()
					+ " opciones, " + (result.metodo() != null ? result.metodo() : "-") + ", " + elapsed + " ms).",
					Json.obj("query", query, "elapsedMs", elapsed, "resultado", result.resultado(), "opciones", result.opciones(), "metodo", result.metodo(),
							"aiError", result.aiError()));
			Map<String, Object> response = Json.obj("resultado", result.resultado());
			response.putAll(flatten(result.opciones()));
			return ResponseEntity.ok(response);
		} catch (RuntimeException error) {
			// Como el webhook anterior de Make: siempre 200 para no romper el flujo de ManyChat.
			log.error("[PROPERTY_SEARCH] búsqueda falló", error);
			long elapsed = System.currentTimeMillis() - startedAt;
			String message = error.getMessage() != null ? error.getMessage() : "Error desconocido";
			audit.log(companyId, null, null, "PROPERTY_SEARCH", "error", "Búsqueda \"" + query + "\" falló tras " + elapsed + " ms: " + message,
					Json.obj("query", query, "elapsedMs", elapsed, "error", message));
			Map<String, Object> response = Json.obj("resultado", "sin_resultados");
			response.putAll(flatten(List.of()));
			return ResponseEntity.ok(response);
		}
	}
}

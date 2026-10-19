package com.c21inova.api.integration;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import com.c21inova.api.common.Json;
import com.c21inova.api.common.Retry;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.ManyChatErrors.ManyChatConfigException;
import com.c21inova.api.lead.AdvisorNotice;

@Service
public class ManyChatService {

	private static final Retry.Options RETRY = new Retry.Options(3, 1000, 6000);

	public record SubscriberInfo(String id, String name, String whatsappPhone, String phone) {
	}

	private final ManyChatClient client;
	private final Env env;

	public ManyChatService(ManyChatClient client, Env env) {
		this.client = client;
		this.env = env;
	}

	/** Reintento inmediato solo para fallos recuperables sin Retry-After (si ManyChat pide esperar, espera la cola). */
	private static Predicate<Exception> inlineRetryable(boolean idempotent) {
		return error -> {
			ManyChatErrors.Failure failure = ManyChatErrors.classify(error, idempotent);
			return failure.retryable() && failure.retryAfterMs() == null;
		};
	}

	public void setCustomFields(String subscriberId, List<AdvisorNotice.Field> fields) {
		if (fields.isEmpty()) return;
		Map<String, Object> body = Json.obj("subscriber_id", subscriberId, "fields",
				fields.stream().map(f -> Json.obj("field_id", f.fieldId(), "field_value", f.value())).toList());
		// Escribir campos es idempotente: un timeout sí puede repetirse.
		Retry.withRetry(() -> client.post("/fb/subscriber/setCustomFields", body, ManyChatClient.DEFAULT_TIMEOUT_MS),
				RETRY.withShouldRetry(inlineRetryable(true)));
	}

	/** Reintenta en la misma petición solo 429/5xx; un 4xx es permanente y un timeout es ambiguo. */
	public void sendFlow(String subscriberId, String flowNs) {
		Map<String, Object> body = Json.obj("subscriber_id", subscriberId, "flow_ns", flowNs);
		Retry.withRetry(() -> client.post("/fb/sending/sendFlow", body, ManyChatClient.DEFAULT_TIMEOUT_MS),
				RETRY.withShouldRetry(inlineRetryable(false)));
	}

	/**
	 * Aviso al asesor: primero reescribe los Custom Fields del contacto del
	 * asesor con ESTE lead (ajustados al límite de la plantilla) y luego lanza
	 * el flujo; si no, ManyChat repetiría el lead anterior.
	 */
	public void notifyAdvisor(String advisorSubscriberId, String flowNsOrNull, List<AdvisorNotice.Field> customFields) {
		String flowNs = flowNsOrNull != null ? flowNsOrNull : env.manyChatAdvisorFlowId();
		if (flowNs == null) {
			throw new ManyChatConfigException("MANYCHAT_ADVISOR_FLOW_ID", "Falta MANYCHAT_ADVISOR_FLOW_ID: no hay flujo configurado para avisar al asesor.");
		}
		if (customFields != null && !customFields.isEmpty()) {
			setCustomFields(advisorSubscriberId, AdvisorNotice.fitFields(customFields));
		}
		sendFlow(advisorSubscriberId, flowNs);
	}

	/**
	 * Texto libre por WhatsApp (sendContent v2). Solo dentro de la ventana de
	 * 24 h. SIN reintento interno: un timeout es ambiguo y repetirlo duplicaría.
	 */
	public void sendWhatsAppText(String subscriberId, String text) {
		Object id = subscriberId.matches("\\d+") ? Long.parseLong(subscriberId) : subscriberId;
		client.post("/fb/sending/sendContent", Json.obj("subscriber_id", id, "data",
				Json.obj("version", "v2", "content", Json.obj("type", "whatsapp", "messages", List.of(Json.obj("type", "text", "text", text))))), 12_000);
	}

	/** sendFlow SIN reintento interno (reportes a propietarios): quien llama marca el envío como incierto. */
	public void sendFlowOnce(String subscriberId, String flowNs) {
		Object id = subscriberId.matches("\\d+") ? Long.parseLong(subscriberId) : subscriberId;
		client.post("/fb/sending/sendFlow", Json.obj("subscriber_id", id, "flow_ns", flowNs), 12_000);
	}

	public SubscriberInfo findSubscriberByPhone(String phoneE164) {
		Map<String, Object> result = Json.asMap(client.get("/fb/subscriber/findBySystemField", Map.of("phone", phoneE164)));
		Object data = result == null ? null : result.get("data");
		Map<String, Object> info = data instanceof List<?> list ? (list.isEmpty() ? null : Json.asMap(list.get(0))) : Json.asMap(data);
		return toInfo(info);
	}

	public SubscriberInfo getSubscriberInfo(String subscriberId) {
		Map<String, Object> result = Json.asMap(client.get("/fb/subscriber/getInfo", Map.of("subscriber_id", subscriberId)));
		return toInfo(result == null ? null : Json.asMap(result.get("data")));
	}

	private static SubscriberInfo toInfo(Map<String, Object> info) {
		if (info == null) return null;
		Object id = info.get("id");
		return new SubscriberInfo(id == null ? null : id.toString(), Json.str(info, "name"), Json.str(info, "whatsapp_phone"), Json.str(info, "phone"));
	}
}

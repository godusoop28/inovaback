package com.c21inova.api.conversation;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.c21inova.api.common.Brand;
import com.c21inova.api.common.Secrets;

/**
 * Sesión conversacional, ráfagas de mensajes y espera tras canalizar. Puro.
 *
 * Espera automática (reopenAt): tras canalizar, el bot solo da un aviso breve
 * durante N minutos contados desde la canalización; al vencer, el siguiente
 * mensaje abre sesión nueva. Pausa manual (control HUMAN/PAUSED): nunca vence
 * sola. Una sesión nueva NO es un lead nuevo ni una reasignación.
 */
public final class Session {

	private Session() {
	}

	/** Sin actividad del cliente por más de esto, su siguiente mensaje abre sesión nueva. */
	public static final long SESSION_IDLE_MS = 6L * 60 * 60 * 1000;
	/** Un aviso de espera como máximo cada 5 minutos. */
	public static final long WAIT_NOTICE_COOLDOWN_MS = 5L * 60 * 1000;

	// --- Ráfagas -----------------------------------------------------------------------

	public record Burst(Instant processAfter, Instant burstStartedAt) {
	}

	/**
	 * Cada mensaje empuja processAfter a now + debounce, pero nunca más allá de
	 * burstStartedAt + maxWait.
	 */
	public static Burst computeProcessAfter(Instant now, Instant burstStartedAt, int debounceSeconds, int maxWaitSeconds) {
		Instant start = burstStartedAt != null ? burstStartedAt : now;
		long debounceMs = Math.max(0, debounceSeconds) * 1000L;
		long maxWaitMs = Math.max(debounceSeconds, maxWaitSeconds) * 1000L;
		long byDebounce = now.toEpochMilli() + debounceMs;
		long byCap = start.toEpochMilli() + maxWaitMs;
		return new Burst(Instant.ofEpochMilli(Math.min(byDebounce, byCap)), start);
	}

	public static boolean isBurstReady(Instant processAfter, Instant now) {
		return processAfter == null || !processAfter.isAfter(now);
	}

	private static String normalizeForDedupe(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Clave de deduplicación de una entrega de ManyChat: el ID del mensaje si
	 * viene; si no, huella de subscriber + marca de tiempo de la interacción +
	 * texto. Sin ninguno, null (mejor un duplicado visible que perder un mensaje).
	 */
	public static String inboundDedupeKey(String messageId, String subscriberId, String text, String interactionAt) {
		String id = messageId == null ? null : messageId.trim();
		if (id != null && !id.isEmpty()) return "id:" + id;
		String at = interactionAt == null ? null : interactionAt.trim();
		if (at == null || at.isEmpty()) return null;
		return "fp:" + Secrets.sha256Hex(subscriberId + "|" + at + "|" + normalizeForDedupe(text));
	}

	// --- Sesión --------------------------------------------------------------------------

	/** "wait" | "new_session" | "continue". lastInboundAt es el mensaje ANTERIOR del cliente. */
	public static String decideSession(Instant now, Instant reopenAt, Instant lastInboundAt, boolean hasHistory) {
		if (reopenAt != null) return now.isBefore(reopenAt) ? "wait" : "new_session";
		if (hasHistory && lastInboundAt != null && now.toEpochMilli() - lastInboundAt.toEpochMilli() > SESSION_IDLE_MS) return "new_session";
		return "continue";
	}

	public static Instant computeReopenAt(Instant completedAt, int minutes) {
		if (minutes <= 0) return null;
		return completedAt.plusSeconds(minutes * 60L);
	}

	public static boolean isWaiting(Instant reopenAt, Instant now) {
		return reopenAt != null && now.isBefore(reopenAt);
	}

	public static int remainingWaitMinutes(Instant reopenAt, Instant now) {
		return (int) Math.max(1, Math.ceil((reopenAt.toEpochMilli() - now.toEpochMilli()) / 60_000.0));
	}

	public static boolean shouldSendWaitNotice(Instant now, Instant waitNoticeAt) {
		return waitNoticeAt == null || now.toEpochMilli() - waitNoticeAt.toEpochMilli() >= WAIT_NOTICE_COOLDOWN_MS;
	}

	// --- Textos fijos ------------------------------------------------------------------------

	/** status: assigned | existing | no_advisor. */
	public record AssignmentFacts(String status, String advisorName, boolean advisorNotified) {
	}

	static String cleanName(String name) {
		if (name == null) return null;
		String value = name.replaceAll("\\s+", " ").trim();
		return value.isEmpty() ? null : value.length() > 80 ? value.substring(0, 80) : value;
	}

	/** Confirmación de la canalización. Nunca dice "ruleta" ni afirma un aviso que falló. */
	public static String buildAssignmentConfirmation(AssignmentFacts facts) {
		String name = cleanName(facts.advisorName());
		if ("no_advisor".equals(facts.status()) || name == null) {
			return "Tu solicitud quedó registrada. En este momento no hay un asesor disponible para asignarla automáticamente, así que el equipo de "
					+ Brand.BRAND_NAME + " la revisará.";
		}
		if ("existing".equals(facts.status())) {
			return "Tu solicitud quedó registrada con " + name + ", del equipo de asesores de " + Brand.BRAND_NAME + ", que ya lleva tu seguimiento.";
		}
		return facts.advisorNotified()
				? "Tu solicitud quedó asignada a " + name + ", del equipo de asesores de " + Brand.BRAND_NAME
						+ ", y ya le enviamos tus datos para que te contacte (puede escribirte desde su propio WhatsApp)."
				: "Tu solicitud quedó asignada a " + name + ", del equipo de asesores de " + Brand.BRAND_NAME
						+ ". El equipo le hará llegar tus datos para darte seguimiento.";
	}

	private static String minutesText(int minutes) {
		return minutes == 1 ? "1 minuto" : minutes + " minutos";
	}

	/** Aviso durante la espera, adaptado al estado real de la canalización. reason: commercial | human | management. */
	public static String buildWaitNotice(String reason, AssignmentFacts assignment, int minutesLeft, boolean savedForTeam) {
		String name = assignment == null ? null : cleanName(assignment.advisorName());
		String head;
		if ("commercial".equals(reason) && assignment != null && name != null && !"no_advisor".equals(assignment.status())) {
			head = "existing".equals(assignment.status())
					? "Tu solicitud ya está con " + name + ", del equipo de asesores de " + Brand.BRAND_NAME + ", que lleva tu seguimiento."
					: assignment.advisorNotified() ? "Tu solicitud ya fue enviada a " + name + ", del equipo de asesores de " + Brand.BRAND_NAME + "."
							: "Tu solicitud ya quedó asignada a " + name + ", del equipo de asesores de " + Brand.BRAND_NAME + ".";
		} else if ("human".equals(reason)) {
			head = "Tu solicitud ya quedó registrada para que una persona del equipo de " + Brand.BRAND_NAME + " te atienda.";
		} else {
			head = "Tu solicitud ya quedó registrada con el equipo de " + Brand.BRAND_NAME + ".";
		}
		String saved = savedForTeam ? " Guardé tu mensaje para que el equipo lo tenga presente." : "";
		return head + saved + " Si tienes otra duda o quieres iniciar una nueva conversación conmigo, espera " + minutesText(minutesLeft)
				+ " y vuelve a escribir.";
	}

	private static String normalize(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "").toLowerCase(Locale.ROOT);
	}

	/** ¿La respuesta ya nombra al asesor? (nombre y primer apellido, sin acentos). */
	public static boolean mentionsAdvisor(String reply, String advisorName) {
		String name = cleanName(advisorName);
		if (name == null) return false;
		List<String> words = Arrays.stream(normalize(name).split(" ")).limit(2).toList();
		String text = normalize(reply);
		return words.stream().allMatch(text::contains);
	}

	private static final Set<String> LOW_SIGNAL_WORDS = Set.of("ok", "okay", "oki", "va", "vale", "sale", "si", "no", "gracias", "muchas", "mil",
			"perfecto", "excelente", "de", "acuerdo", "hola", "buen", "buenos", "buenas", "dia", "dias", "tarde", "tardes", "noche", "noches", "entendido",
			"listo", "claro", "que", "tal");

	/** Saludo/agradecimiento sin información nueva. */
	public static boolean isLowSignalMessage(String text) {
		List<String> words = Arrays.stream(normalize(text).replaceAll("[^\\p{L}\\p{N}\\s]", " ").split("\\s+")).filter(w -> !w.isEmpty())
				.map(w -> w.replaceAll("(.)\\1+$", "$1")).toList();
		return words.isEmpty() || words.stream().allMatch(LOW_SIGNAL_WORDS::contains);
	}

	/** ¿La canalización registrada ocurrió en la sesión actual? */
	public static boolean handoffInCurrentSession(Instant handoffAt, Instant sessionStartedAt) {
		if (handoffAt == null) return false;
		return sessionStartedAt == null || !handoffAt.isBefore(sessionStartedAt);
	}
}

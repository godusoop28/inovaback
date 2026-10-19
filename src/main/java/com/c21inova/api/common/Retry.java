package com.c21inova.api.common;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * Espera exponencial para llamadas a EasyBroker/ManyChat y para "esperar a
 * que EasyBroker procese el contact_request" (reemplaza las esperas fijas de Make).
 */
public final class Retry {

	private Retry() {
	}

	@FunctionalInterface
	public interface Attempt<T> {
		T run() throws Exception;
	}

	public record Options(int maxAttempts, long baseDelayMs, long maxDelayMs, Predicate<Exception> shouldRetry) {
		public Options(int maxAttempts, long baseDelayMs, long maxDelayMs) {
			this(maxAttempts, baseDelayMs, maxDelayMs, null);
		}

		public Options withShouldRetry(Predicate<Exception> predicate) {
			return new Options(maxAttempts, baseDelayMs, maxDelayMs, predicate);
		}

		public Options withMaxAttempts(int attempts) {
			return new Options(attempts, baseDelayMs, maxDelayMs, shouldRetry);
		}
	}

	public static void sleep(long ms) {
		if (ms <= 0) return;
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrumpido durante la espera", e);
		}
	}

	static long backoffDelay(int attempt, long baseDelayMs, long maxDelayMs) {
		return Math.min(baseDelayMs * (1L << (attempt - 1)), maxDelayMs);
	}

	/** Reintenta mientras falle; relanza el último error al agotar intentos. */
	public static <T> T withRetry(Attempt<T> fn, Options options) {
		Exception last = null;
		for (int attempt = 1; attempt <= options.maxAttempts(); attempt++) {
			try {
				return fn.run();
			} catch (Exception error) {
				last = error;
				if (attempt == options.maxAttempts() || (options.shouldRetry() != null && !options.shouldRetry().test(error))) break;
				sleep(backoffDelay(attempt, options.baseDelayMs(), options.maxDelayMs()));
			}
		}
		throw rethrow(last);
	}

	/** Consulta hasta obtener un resultado no nulo. Nunca lanza: devuelve null al agotar. */
	public static <T> T pollUntil(Attempt<T> fn, Options options) {
		for (int attempt = 1; attempt <= options.maxAttempts(); attempt++) {
			try {
				T result = fn.run();
				if (result != null) return result;
			} catch (Exception ignored) {
				// Se vuelve a intentar.
			}
			if (attempt == options.maxAttempts()) return null;
			sleep(backoffDelay(attempt, options.baseDelayMs(), options.maxDelayMs()));
		}
		return null;
	}

	/**
	 * Calendario de reintentos de la cola (1 min, 5 min, 15 min, 1 h). `attempts`
	 * es el número de fallos (base 1): el intento 1 usa el primer escalón.
	 */
	public static Instant nextBackoffRetryAt(int attempts, List<Integer> scheduleMinutes, Instant now) {
		if (attempts > scheduleMinutes.size() || attempts < 1) return null;
		return now.plusSeconds(scheduleMinutes.get(attempts - 1) * 60L);
	}

	private static RuntimeException rethrow(Exception error) {
		if (error instanceof RuntimeException runtime) return runtime;
		return new IllegalStateException(error.getMessage(), error);
	}
}

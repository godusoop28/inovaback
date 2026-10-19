package com.c21inova.api.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Procesamiento en segundo plano tras responder al webhook (equivalente al
 * after() del panel). Si se corta, el cron de conversaciones lo retoma: el
 * mensaje ya está guardado.
 */
@Component
public class ConversationAsync {

	private static final Logger log = LoggerFactory.getLogger(ConversationAsync.class);

	private final ConversationProcessor processor;

	public ConversationAsync(ConversationProcessor processor) {
		this.processor = processor;
	}

	@Async
	public void process(String conversationId) {
		try {
			processor.process(conversationId, 110_000);
		} catch (RuntimeException error) {
			log.error("[ASSISTANT] procesamiento en segundo plano falló {}", conversationId, error);
		}
	}

	@Async
	public void flush(String conversationId) {
		try {
			processor.flushOutbox(conversationId);
		} catch (RuntimeException error) {
			log.error("[ASSISTANT] envío en segundo plano falló {}", conversationId, error);
		}
	}
}

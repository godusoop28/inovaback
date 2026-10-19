package com.c21inova.api.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Variables de entorno del backend. Mismos nombres que usaba el panel en
 * Next.js para poder copiar la configuración tal cual. Ningún getter
 * devuelve un secreto a un cliente: solo los usan las integraciones.
 */
@Component
public class Env {

	/**
	 * Portales cuyos anuncios el asistente puede intentar leer. Si el portal
	 * bloquea (Inmuebles24, Lamudi → 403) o exige sesión (Facebook), se usan
	 * solo las palabras del enlace; nunca se intenta saltar el bloqueo.
	 */
	private static final String DEFAULT_LINK_DOMAINS = String.join(",",
			"easybroker.com", "mercadolibre.com.mx", "vivanuncios.com.mx", "inmuebles24.com", "lamudi.com.mx",
			"propiedades.com", "casasyterrenos.com", "century21mexico.com", "facebook.com", "fb.me", "meli.la");

	private final Environment environment;

	public Env(Environment environment) {
		this.environment = environment;
	}

	public String optional(String name) {
		String value = environment.getProperty(name);
		return value != null && !value.isEmpty() ? value : null;
	}

	public String require(String name) {
		String value = optional(name);
		if (value == null) {
			throw new IllegalStateException("Falta la variable de entorno \"" + name + "\". Revisa README.md para configurarla.");
		}
		return value;
	}

	// --- Auth -----------------------------------------------------------------

	/** Clave para firmar los JWT del panel. JWT_SECRET o, si no, AUTH_SECRET (la misma del panel anterior). */
	public String jwtSecret() {
		String value = optional("JWT_SECRET");
		return value != null ? value : require("AUTH_SECRET");
	}

	// --- EasyBroker -----------------------------------------------------------

	public String easyBrokerApiKey() {
		return require("EASYBROKER_API_KEY");
	}

	/** Correo que EasyBroker devuelve para una propiedad sin asesor dedicado: cae a la ruleta ponderada. */
	public String easyBrokerFallbackAgentEmail() {
		String value = optional("EASYBROKER_FALLBACK_AGENT_EMAIL");
		return (value != null ? value : "contacto@c21inova.com").toLowerCase();
	}

	// --- ManyChat -------------------------------------------------------------

	public String manyChatApiKey() {
		return require("MANYCHAT_API_KEY");
	}

	public String manyChatAdvisorFlowId() {
		return optional("MANYCHAT_ADVISOR_FLOW_ID");
	}

	// --- OpenAI ---------------------------------------------------------------

	public String openAiApiKey() {
		return require("OPENAI_API_KEY");
	}

	/** Sin valor por defecto a propósito: el modelo que la cuenta tenga disponible. */
	public String propertySearchModel() {
		return require("OPENAI_PROPERTY_SEARCH_MODEL");
	}

	public String assistantModel() {
		String value = optional("OPENAI_ASSISTANT_MODEL");
		return value != null ? value : require("OPENAI_PROPERTY_SEARCH_MODEL");
	}

	/** Interruptor de emergencia: "true" apaga el asistente aunque el panel diga ON. */
	public boolean assistantKillSwitch() {
		return "true".equals(optional("ASSISTANT_DISABLED"));
	}

	public List<String> assistantLinkDomains() {
		String raw = optional("ASSISTANT_LINK_DOMAINS");
		return Arrays.stream((raw != null ? raw : DEFAULT_LINK_DOMAINS).split(","))
				.map(domain -> domain.trim().toLowerCase())
				.filter(domain -> !domain.isEmpty())
				.toList();
	}

	/** URL pública del panel (front) para enlazar conversaciones en avisos a gerencia. */
	public String panelUrl() {
		String value = optional("PANEL_URL");
		if (value == null) value = optional("AUTH_URL");
		if (value == null) value = optional("NEXTAUTH_URL");
		return value == null ? "" : value.replaceAll("/$", "");
	}

	// --- Secretos de integración ------------------------------------------------

	public String integrationSecret() {
		return require("INTEGRATION_SECRET");
	}

	public String cronSecret() {
		return require("CRON_SECRET");
	}

	// --- Modo de automatización ---------------------------------------------------

	/**
	 * "shadow" corre el pipeline completo (Lead, asignación y auditoría reales)
	 * sin llamar de verdad a EasyBroker ni ManyChat. Por defecto shadow: pasar a
	 * "live" es un cambio de variable de entorno en el hosting, nunca del panel.
	 */
	public String automationMode() {
		return "live".equals(optional("AUTOMATION_MODE")) ? "live" : "shadow";
	}

	public boolean isLiveAutomation() {
		return "live".equals(automationMode());
	}

	public boolean internalTestingEnabled() {
		return "true".equals(optional("ENABLE_INTERNAL_TESTING"));
	}

	/**
	 * Tareas programadas internas (reintentos, conversaciones, reportes). Apagadas
	 * por defecto para que no compitan con otro despliegue que todavía corra los
	 * mismos crons; también se pueden disparar por HTTP con CRON_SECRET.
	 */
	public boolean schedulerEnabled() {
		return "true".equals(optional("SCHEDULER_ENABLED"));
	}

	public boolean databaseConfigured() {
		return optional("DATABASE_URL") != null || optional("JDBC_DATABASE_URL") != null;
	}

	public boolean easyBrokerConfigured() {
		return optional("EASYBROKER_API_KEY") != null;
	}

	public boolean manyChatConfigured() {
		return optional("MANYCHAT_API_KEY") != null;
	}

	public boolean openAiConfigured() {
		return optional("OPENAI_API_KEY") != null;
	}

	public boolean integrationSecretConfigured() {
		return optional("INTEGRATION_SECRET") != null;
	}
}

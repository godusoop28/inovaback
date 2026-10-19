package com.c21inova.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.c21inova.api.common.Json;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Recorre la API completa contra un Postgres real con las mismas migraciones
 * de Prisma que producción. AUTOMATION_MODE=shadow: nunca llama de verdad a
 * EasyBroker, ManyChat ni OpenAI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

	private static final String SECRET = "secreto-de-prueba";
	private static final String CRON = "cron-de-prueba";
	private static EmbeddedPostgres postgres;
	private static com.sun.net.httpserver.HttpServer fake;

	/**
	 * Falso OpenAI/ManyChat: OpenAI primero pide la herramienta de canalización y,
	 * con su resultado, devuelve la respuesta final; ManyChat acepta todo.
	 */
	private static void startFakeApis() throws IOException {
		fake = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		fake.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String response;
			if (exchange.getRequestURI().getPath().endsWith("/responses")) {
				boolean afterTool = body.contains("function_call_output");
				Map<String, Object> facts = new java.util.LinkedHashMap<>();
				for (String key : com.c21inova.api.conversation.Policy.FACT_KEYS) facts.put(key, Map.of("value", "", "status", "unknown"));
				facts.put("zone", Map.of("value", "Zapopan", "status", "known"));
				Map<String, Object> finalOutput = Map.of("reply", "Con gusto te ayudo.", "primary_intent", "BUY", "secondary_intents", List.of(), "facts", facts,
						"property_ids", List.of(), "summary", "Busca casa en Zapopan.", "asked_clarification", false, "made_progress", true);
				Object output = afterTool
						? List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", Json.write(finalOutput)))))
						: List.of(Map.of("type", "function_call", "call_id", "c1", "name", "request_commercial_handoff", "arguments",
								"{\"intent\":\"BUY\",\"reason\":\"Busca casa en Zapopan\",\"property_public_id\":null}"));
				response = Json.write(Map.of("id", "r1", "status", "completed", "output", output, "usage", Map.of("input_tokens", 10, "output_tokens", 5)));
			} else {
				response = "{\"status\":\"success\"}";
			}
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		fake.start();
	}

	static {
		try {
			startFakeApis();
			postgres = EmbeddedPostgres.builder().start();
			try (Connection connection = postgres.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
				Path dir = Path.of(ApiIntegrationTest.class.getResource("/migrations").toURI());
				try (Stream<Path> files = Files.list(dir)) {
					for (Path file : files.sorted().toList()) statement.execute(Files.readString(file, StandardCharsets.UTF_8));
				}
			}
		} catch (Exception error) {
			throw new IllegalStateException("No se pudo levantar Postgres de prueba", error);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("JDBC_DATABASE_URL", () -> postgres.getJdbcUrl("postgres", "postgres"));
		registry.add("JWT_SECRET", () -> "jwt-de-prueba");
		registry.add("INTEGRATION_SECRET", () -> SECRET);
		registry.add("CRON_SECRET", () -> CRON);
		registry.add("AUTOMATION_MODE", () -> "shadow");
		registry.add("SCHEDULER_ENABLED", () -> "false");
		String fakeUrl = "http://127.0.0.1:" + fake.getAddress().getPort();
		registry.add("OPENAI_BASE_URL", () -> fakeUrl + "/v1");
		registry.add("OPENAI_API_KEY", () -> "sk-prueba");
		registry.add("OPENAI_PROPERTY_SEARCH_MODEL", () -> "modelo-prueba");
		registry.add("MANYCHAT_BASE_URL", () -> fakeUrl);
		registry.add("MANYCHAT_API_KEY", () -> "1:prueba");
	}

	@AfterAll
	static void stop() throws IOException {
		if (fake != null) fake.stop(0);
		if (postgres != null) postgres.close();
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	private final HttpClient http = HttpClient.newHttpClient();
	private String token;

	private record Res(int status, Object body, String raw) {
		@SuppressWarnings("unchecked")
		Map<String, Object> map() {
			return (Map<String, Object>) body;
		}
	}

	private Res call(String method, String path, Object body, Map<String, String> headers) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (token != null) builder.header("Authorization", "Bearer " + token);
		headers.forEach(builder::header);
		String payload = body == null ? null : body instanceof String s ? s : Json.write(body);
		builder.header("Content-Type", "application/json");
		builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
		HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		Object parsed;
		try {
			parsed = response.body().isEmpty() ? null : Json.parse(response.body());
		} catch (RuntimeException notJson) {
			parsed = null;
		}
		return new Res(response.statusCode(), parsed, response.body());
	}

	private Res call(String method, String path, Object body) throws Exception {
		return call(method, path, body, Map.of());
	}

	private static void ok(Res res, int status) {
		assertEquals(status, res.status(), () -> "HTTP " + res.status() + ": " + res.raw());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> l(Object o) {
		return (List<Object>) o;
	}

	/** Espera (máx. 30 s) a que la consulta devuelva una fila: el asistente procesa en segundo plano. */
	private String waitFor(String sql) throws InterruptedException {
		for (int i = 0; i < 60; i++) {
			String value = jdbc.sql(sql).query(String.class).optional().orElse(null);
			if (value != null) return value;
			Thread.sleep(500);
		}
		throw new AssertionError("Tiempo agotado esperando: " + sql);
	}

	private void seedAdmin() {
		String company = jdbc.sql("SELECT id FROM companies WHERE slug = 'century21-innova'").query(String.class).optional().orElse(null);
		if (company == null) {
			jdbc.sql("INSERT INTO companies (id, name, slug, active, \"createdAt\", \"updatedAt\") VALUES ('comp1', 'Century 21 Inova', 'century21-innova', true, now(), now())")
					.update();
			company = "comp1";
		}
		jdbc.sql("""
				INSERT INTO users (id, "companyId", name, email, "passwordHash", role, active, "createdAt", "updatedAt")
				VALUES ('u1', :c, 'Administrador', 'admin@gmail.com', :hash, 'ADMIN', true, now(), now()) ON CONFLICT (email) DO NOTHING
				""").param("c", company).param("hash", new BCryptPasswordEncoder(10).encode("Contra12")).update();
	}

	@Test
	void fullApiFlow() throws Exception {
		seedAdmin();
		Map<String, String> secret = Map.of("x-inova-secret", SECRET);

		// --- Salud y login ---
		Res health = call("GET", "/api/health", null);
		ok(health, 200);
		assertEquals("connected", health.map().get("database"));
		ok(call("GET", "/api/advisors", null), 401);
		ok(call("POST", "/api/auth/login", Map.of("email", "admin@gmail.com", "password", "mala")), 401);
		Res login = call("POST", "/api/auth/login", Map.of("email", " Admin@Gmail.com ", "password", "Contra12"));
		ok(login, 200);
		token = (String) login.map().get("token");
		Res me = call("GET", "/api/auth/me", null);
		ok(me, 200);
		assertEquals("ADMIN", m(me.map().get("user")).get("role"));

		// --- Asesores ---
		Res a1 = call("POST", "/api/advisors", Map.of("nombre", "Ana López", "whatsapp", "33 1234 5678", "emailEasyBroker", "ANA@C21.COM", "manyChatId", "111",
				"activo", true, "peso", 5, "rutasPermitidas", List.of(), "observaciones", ""));
		ok(a1, 201);
		String ana = (String) m(a1.map().get("advisor")).get("id");
		Res a2 = call("POST", "/api/advisors", Map.of("nombre", "Beto Ruiz", "whatsapp", "3398765432", "emailEasyBroker", "", "manyChatId", "", "activo", true,
				"peso", 10, "rutasPermitidas", List.of("Explorar opciones"), "limiteDiario", 2, "observaciones", "solo explorar"));
		ok(a2, 201);
		String beto = (String) m(a2.map().get("advisor")).get("id");
		assertEquals(List.of("Explorar opciones"), m(a2.map().get("advisor")).get("rutasPermitidas"));
		ok(call("POST", "/api/advisors", Map.of("nombre", "X", "whatsapp", "1", "activo", true, "peso", 1)), 422);
		Res advisors = call("GET", "/api/advisors", null);
		ok(advisors, 200);
		assertEquals(2, l(advisors.map().get("advisors")).size());
		ok(call("PATCH", "/api/advisors/" + beto, Map.of("nombre", "Beto Ruiz", "whatsapp", "3398765432", "emailEasyBroker", "", "manyChatId", "", "activo",
				true, "peso", 8, "rutasPermitidas", List.of("Explorar opciones", "Timeout"), "limiteDiario", "", "observaciones", "")), 200);
		Res paused = call("POST", "/api/advisors/" + ana + "/pause", Map.of("mode", "custom", "customDateTime", "2030-01-02T09:30"));
		ok(paused, 200);
		assertEquals("2030-01-02T15:30:00.000Z", m(paused.map().get("advisor")).get("pausadoHasta"));
		ok(call("POST", "/api/advisors/" + ana + "/pause", Map.of("mode", "indefinite")), 200);
		ok(call("POST", "/api/advisors/" + ana + "/resume", null), 200);
		Res round = call("POST", "/api/advisors/rounds", Map.of("advisorId", ana, "weekdays", List.of(5, 1, 1), "start", "22:00", "end", "02:00", "note", "noche"));
		ok(round, 201);
		Res rounds = call("GET", "/api/advisors/rounds", null);
		ok(rounds, 200);
		Map<String, Object> firstRound = m(l(rounds.map().get("rounds")).get(0));
		assertEquals(List.of(1, 5), firstRound.get("weekdays"));
		assertEquals(true, firstRound.get("overnight"));
		ok(call("PATCH", "/api/advisors/rounds/" + firstRound.get("id"), Map.of("active", false)), 200);
		ok(call("DELETE", "/api/advisors/rounds/" + firstRound.get("id"), null), 200);
		Res sim = call("POST", "/api/advisors/simulate", Map.of("route", "Explorar opciones", "iterations", 30));
		ok(sim, 200);
		assertEquals(2, sim.map().get("totalCandidates"));

		// --- Webhook de leads (shadow) ---
		ok(call("POST", "/api/webhooks/manychat/lead", Map.of("nombre", "Cliente", "telefono_cliente", "3311112222", "interes_cliente", "Explorar")), 401);
		Res lead = call("POST", "/api/webhooks/manychat/lead",
				Map.of("nombre", "Cliente Uno", "telefono_cliente", "33 1111 2222", "interes_cliente", "Explorar opciones", "origen", "WhatsApp", "subscriber_id",
						"900"),
				secret);
		ok(lead, 200);
		assertEquals("shadow", lead.map().get("mode"));
		assertEquals(true, m(lead.map().get("selection")).get("ok"));
		assertEquals(List.of("would_create_easybroker_contact", "would_assign_advisor", "would_notify_manychat"), lead.map().get("actions"));
		String leadId = (String) lead.map().get("leadId");
		Res again = call("POST", "/api/webhooks/manychat/lead",
				Map.of("nombre", "Cliente Uno", "telefono_cliente", "+52 33 1111 2222", "interes_cliente", "Vi una propiedad", "datos_propiedad", "EB-XX0001"),
				secret);
		ok(again, 200);
		assertEquals(true, again.map().get("duplicate"));
		// Cuerpo con salto de línea crudo dentro de una cadena (como lo manda ManyChat).
		Res lenient = call("POST", "/api/webhooks/manychat/lead",
				"{\"nombre\":\"Cliente Dos\",\"telefono_cliente\":\"3322223333\",\"interes_cliente\":\"Campaña propiedad\",\"datos_propiedad\":\"Hola\nEB-AB1234\"}",
				secret);
		ok(lenient, 200);
		assertEquals("CAMPAIGN", lenient.map().get("interestType"));
		ok(call("POST", "/api/webhooks/manychat/lead", Map.of("nombre", "", "telefono_cliente", "1"), secret), 422);
		ok(call("POST", "/api/webhooks/manychat/lead", "{no json", secret), 400);

		// --- Leads en el panel ---
		Res leads = call("GET", "/api/leads?page=1&limit=10", null);
		ok(leads, 200);
		assertEquals(2, leads.map().get("total"));
		Map<String, Object> row = m(l(leads.map().get("leads")).get(1));
		assertEquals("Ruleta", row.get("metodoAsignacion"));
		assertEquals("Asignado", row.get("estado"));
		ok(call("GET", "/api/leads?ruta=EXPLORE&estado=pending&q=Uno&range=today", null), 200);
		Res detail = call("GET", "/api/leads/" + leadId, null);
		ok(detail, 200);
		assertFalse(l(detail.map().get("auditLogs")).isEmpty());
		ok(call("PATCH", "/api/leads/" + leadId, Map.of("status", "COMPLETED")), 200);
		ok(call("PATCH", "/api/leads/" + leadId, Map.of("status", "NOPE")), 422);
		Res reassign = call("POST", "/api/leads/" + leadId + "/reassign", Map.of("advisorId", ana, "reason", "prueba"));
		ok(reassign, 200);
		ok(call("GET", "/api/leads/counts", null), 200);
		ok(call("GET", "/api/leads/filters", null), 200);

		// --- Tablero y reportes ---
		Res dashboard = call("GET", "/api/dashboard?range=today", null);
		ok(dashboard, 200);
		assertEquals(2, m(m(dashboard.map().get("report")).get("summary")).get("total"));
		Res report = call("GET", "/api/reports?range=this_month&compare=true&ruta=EXPLORE", null);
		ok(report, 200);
		assertNotNull(report.map().get("previous"));
		Res csv = call("GET", "/api/reports/csv?range=today", null);
		ok(csv, 200);
		assertTrue(csv.raw().contains("Cliente Uno"));
		ok(call("GET", "/api/reports/properties/csv", null), 200);
		ok(call("GET", "/api/reports?range=custom&from=2026-02-31&to=2026-03-01", null), 400);

		// --- Usuarios ---
		Res created = call("POST", "/api/users", Map.of("nombre", "Dirección", "correo", "dir@c21.com", "password", "12345678", "rol", "DIRECCION", "activo", true));
		ok(created, 201);
		String userId = (String) m(created.map().get("user")).get("id");
		ok(call("POST", "/api/users", Map.of("nombre", "Otro", "correo", "dir@c21.com", "password", "12345678", "rol", "CONSULTA", "activo", true)), 400);
		ok(call("PATCH", "/api/users/u1", Map.of("nombre", "Administrador", "correo", "admin@gmail.com", "rol", "CONSULTA", "activo", true)), 400);
		ok(call("POST", "/api/users/" + userId + "/password", Map.of("password", "otraClave1")), 200);
		ok(call("PATCH", "/api/users/" + userId + "/active", Map.of("activo", false)), 200);
		ok(call("DELETE", "/api/users/u1", null), 400);
		assertEquals(2, l(call("GET", "/api/users", null).map().get("users")).size());

		// --- Asistente: configuración e ingesta ---
		ok(call("PUT", "/api/conversations/settings", Map.of("mode", "TEST_ONLY", "debounceSeconds", 2, "maxWaitSeconds", 5, "maxClarifications", 3,
				"abandonHandoffMinutes", 30, "existingLeadWindowDays", 30, "handoffReopenMinutes", 10, "testSubscriberIds", "555, abc, 555",
				"managementSubscriberIds", List.of())), 200);
		assertEquals(List.of("555"), m(call("GET", "/api/conversations/settings", null).map().get("settings")).get("testSubscriberIds"));
		Res notHandled = call("POST", "/api/webhooks/manychat/message", Map.of("subscriber_id", "777", "text", "Hola", "phone", "3399990000", "name", "Pepe"),
				secret);
		ok(notHandled, 200);
		assertEquals("false", notHandled.map().get("handled"));
		Res handled = call("POST", "/api/webhooks/manychat/message",
				Map.of("id", 555, "last_input_text", "Hola\nquiero info", "whatsapp_phone", "+5213300000000", "name", "{{first_name}}", "last_interaction",
						"2026-10-18 10:00"),
				secret);
		ok(handled, 200);
		assertEquals("true", handled.map().get("handled"));
		Res duplicate = call("POST", "/api/webhooks/manychat/message",
				Map.of("subscriber_id", "555", "text", "Hola\nquiero info", "last_interaction", "2026-10-18 10:00"), secret);
		assertEquals(true, duplicate.map().get("duplicate"));
		ok(call("POST", "/api/webhooks/manychat/message", Map.of("subscriber_id", "abc"), secret), 422);
		Res list = call("GET", "/api/conversations?filter=all", null);
		ok(list, 200);
		assertEquals(2, list.map().get("total"));
		String conversationId = (String) jdbc.sql("SELECT id FROM conversations WHERE \"manyChatSubscriberId\" = '555'").query(String.class).single();
		Res conv = call("GET", "/api/conversations/" + conversationId, null);
		ok(conv, 200);
		assertEquals("+523300000000", m(conv.map().get("conversation")).get("phone"));
		ok(call("POST", "/api/conversations/" + conversationId + "/control", Map.of("control", "HUMAN", "reason", "lo atiende Ana")), 200);
		ok(call("POST", "/api/conversations/" + conversationId + "/human-reply", Map.of("text", "Ya te escribí por WhatsApp")), 200);
		ok(call("POST", "/api/conversations/" + conversationId + "/resume", Map.of("answerPending", false)), 200);
		ok(call("GET", "/api/conversations/attention", null), 200);
		ok(call("GET", "/api/conversations/escalations", null), 200);
		Res threads = call("GET", "/api/conversations/advisors", null);
		ok(threads, 200);
		ok(call("GET", "/api/conversations/advisors/" + ana, null), 200);
		for (String filter : List.of("attention", "ai", "waiting", "human", "paused", "handed_off", "errors", "test")) {
			ok(call("GET", "/api/conversations?filter=" + filter + "&q=Pepe", null), 200);
		}

		// --- Turno completo del asistente (OpenAI falso) ---
		// Contacto de prueba real (TEST_ONLY): la respuesta se envía por ManyChat (falso) y queda SENT.
		// (El mensaje anterior se pausó a mano y, al reanudar sin "responder pendientes", no se contesta.)
		ok(call("POST", "/api/webhooks/manychat/message", Map.of("subscriber_id", "555", "text", "busco casa", "message_id", "m-2"), secret), 200);
		String sent = waitFor("SELECT status::text FROM conversation_messages WHERE \"conversationId\" = '" + conversationId
				+ "' AND role = 'ASSISTANT' AND status = 'SENT' LIMIT 1");
		assertEquals("SENT", sent);
		Res simulated = call("POST", "/api/conversations/simulate", Map.of("text", "Quiero comprar una casa en Zapopan"));
		ok(simulated, 200);
		String simId = (String) simulated.map().get("conversationId");
		String reply = waitFor("SELECT text FROM conversation_messages WHERE \"conversationId\" = '" + simId + "' AND role = 'ASSISTANT' AND status = 'SIMULATED' LIMIT 1");
		assertTrue(reply.startsWith("Con gusto te ayudo.\n\nTu solicitud quedó asignada a "), reply);
		Map<String, Object> simConversation = m(call("GET", "/api/conversations/" + simId, null).map().get("conversation"));
		assertEquals("ASSIGNED", simConversation.get("handoffState"));
		assertEquals("BUY", simConversation.get("primaryIntent"));
		assertNotNull(simConversation.get("reopenAt"));
		assertEquals("COMPLETED", m(l(simConversation.get("turns")).get(0)).get("status"));
		assertEquals("Zapopan", m(m(simConversation.get("facts")).get("zone")).get("value"));
		// Durante la espera tras canalizar: aviso fijo, sin IA ni reasignación.
		ok(call("POST", "/api/conversations/simulate", Map.of("text", "y también quiero vender mi depa", "subscriberId", simConversation.get("manyChatSubscriberId"))), 200);
		String notice = waitFor("SELECT text FROM conversation_messages WHERE \"conversationId\" = '" + simId
				+ "' AND role = 'ASSISTANT' AND metadata->>'waitNotice' = 'true' LIMIT 1");
		assertTrue(notice.contains("Guardé tu mensaje para que el equipo lo tenga presente."), notice);
		assertEquals(0, jdbc.sql("SELECT count(*)::int FROM leads WHERE source = 'assistant_conversation'").query(Integer.class).single());

		// --- Propiedades: destinatarios, actividades y configuración ---
		Res prospect = call("POST", "/api/properties/recipients",
				Map.of("publicId", "eb-ab1234", "name", "Dueña", "phone", "3322223333", "relation", "propietario", "manyChatSubscriberId", ""));
		ok(prospect, 400);
		ok(call("POST", "/api/properties/recipients", Map.of("publicId", "eb-ab1234", "name", "Dueña", "phone", "3322223333", "relation", "propietario",
				"manyChatSubscriberId", "123", "confirmNotProspect", true)), 201);
		String recipient = jdbc.sql("SELECT id FROM property_recipients").query(String.class).single();
		ok(call("PATCH", "/api/properties/recipients/" + recipient, Map.of("weeklyReport", true, "eventNotifications", false, "active", true)), 400);
		ok(call("POST", "/api/properties/recipients/" + recipient + "/consent", Map.of("decision", "grant", "evidence", "corto")), 400);
		ok(call("POST", "/api/properties/recipients/" + recipient + "/consent", Map.of("decision", "grant", "evidence", "Lo autorizó por WhatsApp el 18/10")), 200);
		Res event = call("POST", "/api/properties/events", Map.of("publicId", "EB-AB1234", "type", "SHOWING", "status", "SCHEDULED", "title", "Visita con cliente",
				"scheduledAt", "2026-10-20T11:00", "notifyRecipients", true));
		ok(event, 201);
		String eventId = (String) m(event.map().get("event")).get("id");
		Res updated = call("PUT", "/api/properties/events/" + eventId, Map.of("publicId", "EB-AB1234", "type", "SHOWING", "status", "DONE", "title",
				"Visita con cliente", "scheduledAt", "2026-10-20T11:00", "outcome", "Le gustó", "notifyRecipients", true));
		ok(updated, 200);
		assertEquals(2, m(updated.map().get("event")).get("version"));
		ok(call("POST", "/api/properties/events", Map.of("publicId", "EB-AB1234", "type", "SHOWING", "status", "SCHEDULED", "title", "Sin fecha")), 400);
		ok(call("GET", "/api/properties?range=this_week", null), 200);
		ok(call("GET", "/api/properties/EB-AB1234", null), 200);
		ok(call("GET", "/api/properties/EB-AB1234/weekly-preview", null), 200);
		ok(call("GET", "/api/properties/settings", null), 200);
		ok(call("PUT", "/api/properties/settings", Map.of("weeklyEnabled", true, "weeklyHour", 9)), 400);
		ok(call("PUT", "/api/properties/settings", Map.of("weeklyEnabled", true, "weeklyHour", 9, "weeklyMinute", 30, "manyChatWeeklyFlowNs", "content123",
				"weeklyFieldIds", Map.of("property", 1, "period", 2, "weekLeads", 3, "cumulative", 4, "sources", 5, "activity", "6"))), 200);
		ok(call("POST", "/api/properties/recipients/" + recipient + "/test", null), 400);

		// --- Administración y crons ---
		Res readiness = call("GET", "/api/admin/readiness", null);
		ok(readiness, 200);
		assertEquals(true, readiness.map().get("databaseReachable"));
		ok(call("GET", "/api/admin/integration-jobs/recovery", null), 200);
		ok(call("POST", "/api/admin/integration-jobs/recovery", Map.of("requeue", List.of("x"), "confirm", true)), 200);
		ok(call("GET", "/api/admin/audit", null), 200);
		ok(call("POST", "/api/testing/simulate-lead", Map.of("nombre", "a", "telefono", "1", "interes", "x")), 403);
		token = null;
		ok(call("GET", "/api/cron/retry-integrations", null), 401);
		ok(call("GET", "/api/cron/retry-integrations", null, Map.of("Authorization", "Bearer " + CRON)), 200);
		ok(call("POST", "/api/cron/property-reports?secret=" + CRON, null), 200);
		Res cronConversations = call("GET", "/api/cron/conversations", null, Map.of("x-cron-secret", CRON));
		ok(cronConversations, 200);

		// --- Roles: CONSULTA no entra a leads pero sí a reportes ---
		jdbc.sql("UPDATE users SET role = 'CONSULTA', active = true WHERE id = :id").param("id", userId).update();
		token = (String) call("POST", "/api/auth/login", Map.of("email", "dir@c21.com", "password", "otraClave1")).map().get("token");
		ok(call("GET", "/api/leads", null), 403);
		ok(call("GET", "/api/reports?range=today", null), 200);
		jdbc.sql("UPDATE users SET active = false WHERE id = :id").param("id", userId).update();
		ok(call("GET", "/api/reports?range=today", null), 401);
	}
}

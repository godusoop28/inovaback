package com.c21inova.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.c21inova.api.common.Json;
import com.sun.net.httpserver.HttpServer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Pipeline de leads en modo LIVE contra EasyBroker y ManyChat falsos:
 * asignación directa al asesor de la propiedad, contact_request, búsqueda del
 * contacto, asignación en EasyBroker, aviso por ManyChat y cola de reintentos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LivePipelineTest {

	private static final String SECRET = "secreto";
	private static EmbeddedPostgres postgres;
	private static HttpServer fake;
	private static final List<String> calls = new CopyOnWriteArrayList<>();
	private static final AtomicBoolean manyChatFails = new AtomicBoolean(false);

	static {
		try {
			postgres = EmbeddedPostgres.builder().start();
			try (Connection connection = postgres.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
				Path dir = Path.of(LivePipelineTest.class.getResource("/migrations").toURI());
				try (Stream<Path> files = Files.list(dir)) {
					for (Path file : files.sorted().toList()) statement.execute(Files.readString(file, StandardCharsets.UTF_8));
				}
			}
			fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			fake.createContext("/", exchange -> {
				String path = exchange.getRequestURI().getPath();
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				calls.add(exchange.getRequestMethod() + " " + path + " " + body);
				int status = 200;
				String response;
				if (path.equals("/eb/properties/EB-AB1234")) {
					response = Json.write(Map.of("public_id", "EB-AB1234", "title", "Casa en Valle Real", "property_type", "Casa", "location",
							Map.of("name", "Valle Real, Zapopan", "street", "secreta 123"), "public_url", "https://www.easybroker.com/mx/listings/casa-valle-real",
							"agent", Map.of("email", "ANA@c21.com"), "operations", List.of(Map.of("type", "sale", "amount", 8500000, "formatted_amount", "$8,500,000")),
							"bedrooms", 4, "bathrooms", 3, "updated_at", "2026-10-01"));
				} else if (path.equals("/eb/contact_requests") && "POST".equals(exchange.getRequestMethod())) {
					response = "{\"status\":\"successful\"}";
				} else if (path.equals("/eb/contact_requests")) {
					response = Json.write(Map.of("content", List.of(Map.of("id", 1, "contact_id", 99, "phone", "523311112222", "source", "WhatsApp ManyChat",
							"property_id", "EB-AB1234"))));
				} else if (path.startsWith("/eb/contacts/")) {
					response = "{}";
				} else if (path.startsWith("/fb/") && manyChatFails.get() && path.endsWith("sendFlow")) {
					status = 400;
					response = "{\"status\":\"error\",\"message\":\"Flow content999 not found\"}";
				} else if (path.startsWith("/fb/")) {
					response = "{\"status\":\"success\"}";
				} else {
					status = 404;
					response = "{}";
				}
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			fake.start();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		String fakeUrl = "http://127.0.0.1:" + fake.getAddress().getPort();
		registry.add("JDBC_DATABASE_URL", () -> postgres.getJdbcUrl("postgres", "postgres"));
		registry.add("JWT_SECRET", () -> "jwt");
		registry.add("INTEGRATION_SECRET", () -> SECRET);
		registry.add("CRON_SECRET", () -> "cron");
		registry.add("AUTOMATION_MODE", () -> "live");
		registry.add("EASYBROKER_BASE_URL", () -> fakeUrl + "/eb");
		registry.add("EASYBROKER_API_KEY", () -> "eb-key");
		registry.add("MANYCHAT_BASE_URL", () -> fakeUrl);
		registry.add("MANYCHAT_API_KEY", () -> "1:key");
		registry.add("MANYCHAT_ADVISOR_FLOW_ID", () -> "content20260714062251_472759");
	}

	@AfterAll
	static void stop() throws IOException {
		fake.stop(0);
		postgres.close();
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	private Map<String, Object> lead(Map<String, Object> body) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/manychat/lead"))
				.header("Content-Type", "application/json").header("x-inova-secret", SECRET).POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		return Json.parseObject(response.body());
	}

	@Test
	void livePipeline() throws Exception {
		jdbc.sql("INSERT INTO companies (id, name, slug, active, \"createdAt\", \"updatedAt\") VALUES ('c1', 'Century 21 Inova', 'century21-innova', true, now(), now())")
				.update();
		jdbc.sql("""
				INSERT INTO advisors (id, "companyId", name, phone, "easyBrokerEmail", "manyChatSubscriberId", active, weight, "createdAt", "updatedAt")
				VALUES ('ana', 'c1', 'Ana López', '+523310000001', 'ana@c21.com', '111', true, 5, now(), now()),
				       ('beto', 'c1', 'Beto Ruiz', '+523310000002', 'beto@c21.com', '222', true, 5, now(), now())
				""").update();

		// 1. Propiedad con agente propio: asignación directa a Ana, EasyBroker confirmado y aviso enviado.
		Map<String, Object> result = lead(Map.of("nombre", "Cliente", "telefono_cliente", "3311112222", "interes_cliente", "Vi una propiedad", "datos_propiedad",
				"EB-AB1234", "subscriber_id", "900"));
		assertEquals("live", result.get("mode"));
		@SuppressWarnings("unchecked")
		Map<String, Object> selection = (Map<String, Object>) result.get("selection");
		assertEquals("Ana López", selection.get("advisorName"));
		assertEquals("DIRECT_PROPERTY_ADVISOR", selection.get("method"));
		assertEquals(List.of("created_easybroker_contact", "assigned_advisor_in_easybroker", "notified_manychat"), result.get("actions"));
		String leadId = (String) result.get("leadId");
		assertEquals("COMPLETED", jdbc.sql("SELECT status::text FROM leads WHERE id = :id").param("id", leadId).query(String.class).single());
		assertEquals("99", jdbc.sql("SELECT \"easyBrokerContactId\" FROM leads WHERE id = :id").param("id", leadId).query(String.class).single());
		assertEquals(List.of(true, true), jdbc.sql("SELECT \"easyBrokerConfirmed\", \"manyChatNotified\" FROM lead_assignments WHERE \"leadId\" = :id")
				.param("id", leadId).query((rs, i) -> List.of(rs.getBoolean(1), rs.getBoolean(2))).single());
		assertTrue(calls.stream().anyMatch(c -> c.startsWith("PATCH /eb/contacts/99") && c.contains("ana@c21.com")), calls.toString());
		String fields = calls.stream().filter(c -> c.contains("setCustomFields")).findFirst().orElseThrow();
		assertTrue(fields.contains("\"subscriber_id\":\"111\"") && fields.contains("PROPIEDAD DE INTER") && fields.contains("Valle Real, Zapopan")
				&& !fields.contains("secreta 123"), fields);
		assertTrue(calls.stream().anyMatch(c -> c.contains("sendFlow") && c.contains("content20260714062251_472759")));
		assertEquals(1, jdbc.sql("SELECT count(*)::int FROM property_inquiries WHERE \"publicId\" = 'EB-AB1234'").query(Integer.class).single());

		// 2. ManyChat rechaza el flujo: la asignación queda, el aviso queda FAILED (configuración) y no se reintenta solo.
		manyChatFails.set(true);
		Map<String, Object> second = lead(Map.of("nombre", "Otro", "telefono_cliente", "3322223333", "interes_cliente", "Explorar opciones"));
		assertTrue(((List<?>) second.get("actions")).contains("manychat_notification_failed"));
		String secondId = (String) second.get("leadId");
		assertEquals("NOTIFIED", jdbc.sql("SELECT status::text FROM leads WHERE id = :id").param("id", secondId).query(String.class).single());
		assertEquals(List.of("FAILED", "FLOW", "HTTP_400"), jdbc.sql("""
				SELECT status::text, "errorKind", "errorCode" FROM integration_jobs WHERE "leadId" = :id AND type = 'MANYCHAT_FLOW'
				""").param("id", secondId).query((rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3))).single());

		// 3. Recuperación manual: se reencola, el cron lo envía (ManyChat ya responde) y el lead pasa a COMPLETED.
		manyChatFails.set(false);
		String jobId = jdbc.sql("SELECT id FROM integration_jobs WHERE \"leadId\" = :id AND type = 'MANYCHAT_FLOW'").param("id", secondId).query(String.class).single();
		jdbc.sql("UPDATE integration_jobs SET status = 'PENDING', \"nextRetryAt\" = now() WHERE id = :id").param("id", jobId).update();
		HttpResponse<String> cron = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/cron/retry-integrations"))
				.header("Authorization", "Bearer cron").GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, cron.statusCode(), cron.body());
		assertTrue(cron.body().contains("\"succeeded\":1"), cron.body());
		assertEquals("SUCCESS", jdbc.sql("SELECT status::text FROM integration_jobs WHERE id = :id").param("id", jobId).query(String.class).single());
		assertEquals("COMPLETED", jdbc.sql("SELECT status::text FROM leads WHERE id = :id").param("id", secondId).query(String.class).single());
	}
}

package com.c21inova.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.c21inova.api.common.Json;
import com.c21inova.api.inventory.InventoryJobs;
import com.c21inova.api.inventory.InventoryMapping;
import com.c21inova.api.inventory.InventorySyncService;
import com.sun.net.httpserver.HttpServer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Inventario EasyBroker → CRM → 21Online contra APIs falsas: altas, cambios,
 * eliminación con copia, emparejamiento con anuncios previos de 21Online (sin
 * duplicar) y restauración en EasyBroker reutilizando el mismo anuncio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventorySyncTest {

	private static EmbeddedPostgres postgres;
	private static HttpServer fake;
	private static final List<String> calls = new CopyOnWriteArrayList<>();
	/** Inventario vivo del EasyBroker falso. */
	private static final Map<String, Map<String, Object>> eb = new ConcurrentHashMap<>();

	static Map<String, Object> property(String id, String title, String status, double price, String updatedAt) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("public_id", id);
		p.put("title", title);
		p.put("description", "Descripción de " + title);
		p.put("property_type", "Casa");
		p.put("status", status);
		p.put("bedrooms", 3);
		p.put("bathrooms", 2);
		p.put("construction_size", 200.0);
		p.put("updated_at", updatedAt);
		p.put("location", Map.of("name", "Providencia, Guadalajara, Jalisco", "street", "Colomos", "exterior_number", "10", "postal_code", "44660",
				"latitude", 20.69, "longitude", -103.38, "show_exact_location", true));
		p.put("operations", List.of(Map.of("type", "sale", "amount", price, "currency", "MXN", "formatted_amount", "$" + price)));
		p.put("property_images", List.of(Map.of("url", "https://assets.easybroker.com/" + id + ".jpg")));
		p.put("agent", Map.of("full_name", "Ana López", "email", "ana@c21.com"));
		p.put("public_url", "https://www.easybroker.com/listings/" + id.toLowerCase());
		return p;
	}

	static {
		try {
			postgres = EmbeddedPostgres.builder().start();
			try (Connection connection = postgres.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
				Path dir = Path.of(InventorySyncTest.class.getResource("/migrations").toURI());
				try (Stream<Path> files = Files.list(dir)) {
					for (Path file : files.sorted().toList()) statement.execute(Files.readString(file, StandardCharsets.UTF_8));
				}
			}
			fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			fake.createContext("/", exchange -> {
				String method = exchange.getRequestMethod();
				String path = exchange.getRequestURI().getPath();
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				calls.add(method + " " + path + " " + body);
				int status = 200;
				Object response;
				if (path.equals("/eb/properties") && method.equals("GET")) {
					List<Map<String, Object>> content = new ArrayList<>();
					for (Map<String, Object> p : eb.values()) {
						content.add(Map.of("public_id", p.get("public_id"), "title", p.get("title"), "status", p.get("status"), "updated_at", p.get("updated_at")));
					}
					response = Map.of("content", content, "pagination", Map.of("total", content.size(), "page", 1, "limit", 50));
				} else if (path.equals("/eb/properties") && method.equals("POST")) {
					Map<String, Object> created = new LinkedHashMap<>(Json.parseObject(body));
					created.put("public_id", "EB-NE0001");
					created.put("updated_at", "2026-10-24T10:00:00-06:00");
					created.put("location", Map.of("name", "Providencia, Guadalajara, Jalisco"));
					eb.put("EB-NE0001", created);
					response = created;
				} else if (path.startsWith("/eb/properties/")) {
					Map<String, Object> p = eb.get(path.substring("/eb/properties/".length()));
					if (p == null) {
						status = 404;
						response = Map.of("error", "Not found");
					} else {
						response = p;
					}
				} else if (path.equals("/c21/oauth2/token")) {
					response = Map.of("access_token", "tok", "expires_in", 3600, "token_type", "Bearer");
				} else if (path.equals("/c21/api/v1/external/Property") && method.equals("GET")) {
					// Anuncio que ya existía en 21Online (alta manual) con el mismo precio, ciudad y recámaras que EB-BB0002.
					response = Map.of("value", List.of(Map.of("ListingKey", "LK-OLD-B", "ListPrice", 5_000_000, "City", "Guadalajara", "BedroomsTotal", 3)));
				} else if (path.equals("/c21/api/v1/external/Property") && method.equals("POST")) {
					response = Map.of("ListingKey", "LK-" + Json.parseObject(body).get("ExternalId"));
				} else if (path.startsWith("/c21/api/v1/external/Property/") && method.equals("PATCH")) {
					response = Map.of("ListingKey", path.substring("/c21/api/v1/external/Property/".length()));
				} else {
					status = 404;
					response = Map.of("error", "unknown " + path);
				}
				byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			fake.start();
		} catch (Exception error) {
			throw new IllegalStateException("No se pudo preparar la prueba", error);
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		String fakeUrl = "http://127.0.0.1:" + fake.getAddress().getPort();
		registry.add("JDBC_DATABASE_URL", () -> postgres.getJdbcUrl("postgres", "postgres"));
		registry.add("JWT_SECRET", () -> "jwt");
		registry.add("INTEGRATION_SECRET", () -> "s");
		registry.add("CRON_SECRET", () -> "cron");
		registry.add("SCHEDULER_ENABLED", () -> "false");
		registry.add("PROPERTY_SYNC_ENABLED", () -> "false");
		registry.add("EASYBROKER_BASE_URL", () -> fakeUrl + "/eb");
		registry.add("EASYBROKER_API_KEY", () -> "eb-key");
		registry.add("C21_API_BASE_URL", () -> fakeUrl + "/c21");
		registry.add("C21_CLIENT_ID", () -> "client");
		registry.add("C21_CLIENT_SECRET", () -> "secret");
	}

	@AfterAll
	static void stop() throws IOException {
		fake.stop(0);
		postgres.close();
	}

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InventoryJobs jobs;

	@Autowired
	InventorySyncService sync;

	private Map<String, Object> row(String publicId) {
		return jdbc.sql("""
				SELECT title, status, price, "deletedAt", "restoredAsPublicId", "restoredFromPublicId", "c21ListingKey", "c21Status", "c21Error"
				FROM eb_properties WHERE "publicId" = :id
				""").param("id", publicId).query((rs, i) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) out.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
			return out;
		}).optional().orElse(null);
	}

	private long count(String prefix) {
		return calls.stream().filter(c -> c.startsWith(prefix)).count();
	}

	@Test
	void mappingIsPublicOnly() {
		Map<String, Object> raw = new LinkedHashMap<>(property("EB-ZZ0001", "Casa", "reserved", 1_000_000, "x"));
		raw.put("private_description", "secreto");
		Map<String, Object> reso = InventoryMapping.toReso(raw, false);
		assertEquals("EB-ZZ0001", reso.get("ExternalId"));
		assertEquals("Pending", reso.get("StandardStatus"));
		assertEquals("Residential", reso.get("PropertyType"));
		assertEquals("Guadalajara", reso.get("City"));
		assertEquals("Providencia", reso.get("SubdivisionName"));
		assertFalse(Json.write(reso).contains("secreto"));
		assertEquals("Withdrawn", InventoryMapping.resoStatus("published", true));
		assertEquals(InventoryMapping.hash(reso), InventoryMapping.hash(InventoryMapping.toReso(raw, false)));
	}

	@Test
	void fullLifecycle() {
		jdbc.sql("INSERT INTO companies (id, name, slug, active, \"createdAt\", \"updatedAt\") VALUES ('c1', 'Century 21 Inova', 'century21-innova', true, now(), now()) ON CONFLICT DO NOTHING")
				.update();
		eb.put("EB-AA0001", property("EB-AA0001", "Casa A", "published", 3_000_000, "2026-10-01T10:00:00-06:00"));
		eb.put("EB-BB0002", property("EB-BB0002", "Casa B", "published", 5_000_000, "2026-10-01T10:00:00-06:00"));
		eb.put("EB-CC0003", property("EB-CC0003", "Casa C", "not_published", 7_000_000, "2026-10-01T10:00:00-06:00"));

		// 1) Primera corrida: todo al CRM; A se crea en 21Online, B se empareja con su anuncio previo, C no publicada no se envía.
		Map<String, Object> first = jobs.runNow("test");
		assertEquals(true, first.get("ok"), first.toString());
		assertEquals("synced", row("EB-AA0001").get("c21Status"));
		assertEquals("LK-EB-AA0001", row("EB-AA0001").get("c21ListingKey"));
		assertEquals("LK-OLD-B", row("EB-BB0002").get("c21ListingKey"));
		assertEquals("synced", row("EB-BB0002").get("c21Status"));
		assertEquals("not_sent", row("EB-CC0003").get("c21Status"));
		assertEquals(1, count("POST /c21/api/v1/external/Property "), calls.toString());
		assertEquals(1, count("PATCH /c21/api/v1/external/Property/LK-OLD-B"));

		// 2) Sin cambios: no se reenvía nada a 21Online.
		calls.clear();
		jobs.runNow("test");
		assertEquals(0, count("POST /c21") + count("PATCH /c21"), calls.toString());

		// 3) Se edita B y se elimina A en EasyBroker.
		eb.put("EB-BB0002", property("EB-BB0002", "Casa B renovada", "published", 5_000_000, "2026-10-02T10:00:00-06:00"));
		eb.remove("EB-AA0001");
		calls.clear();
		jobs.runNow("test");
		assertEquals("Casa B renovada", row("EB-BB0002").get("title"));
		assertNotNull(row("EB-AA0001").get("deletedAt"), "A queda como eliminada (con copia)");
		assertEquals("removed", row("EB-AA0001").get("c21Status"));
		assertTrue(calls.stream().anyMatch(c -> c.startsWith("PATCH /c21/api/v1/external/Property/LK-EB-AA0001") && c.contains("Withdrawn")), calls.toString());
		assertTrue(calls.stream().anyMatch(c -> c.startsWith("PATCH /c21/api/v1/external/Property/LK-OLD-B") && c.contains("Casa B renovada")), calls.toString());

		// 4) Restaurar A: se crea de nuevo en EasyBroker y reutiliza el anuncio de 21Online (sin duplicar).
		sync.requestRestore("c1", "EB-AA0001", "admin@c21.com");
		calls.clear();
		jobs.runNow("test");
		assertEquals(1, count("POST /eb/properties "), calls.toString());
		assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST /eb/properties ") && c.contains("Casa A") && c.contains("Providencia")));
		assertEquals("EB-NE0001", row("EB-AA0001").get("restoredAsPublicId"));
		Map<String, Object> restored = row("EB-NE0001");
		assertNotNull(restored);
		assertNull(restored.get("deletedAt"));
		assertEquals("EB-AA0001", restored.get("restoredFromPublicId"));
		assertEquals("LK-EB-AA0001", restored.get("c21ListingKey"));
		assertEquals(0, count("POST /c21/api/v1/external/Property "), "no se crea un anuncio nuevo en 21Online");
		assertEquals(1, count("PATCH /c21/api/v1/external/Property/LK-EB-AA0001"));
		assertEquals("synced", restored.get("c21Status"));
	}
}

package com.c21inova.api.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.C21OnlineClient;
import com.c21inova.api.integration.C21OnlineClient.C21ApiException;
import com.c21inova.api.integration.EasyBrokerClient.EasyBrokerApiException;
import com.c21inova.api.integration.EasyBrokerService;

/**
 * Inventario completo de EasyBroker (la fuente de verdad) reflejado en el
 * CRM (tabla eb_properties) y en 21Online (API Externa RESO).
 *
 * - Altas y cambios de EasyBroker se copian al CRM y se envían a 21Online
 *   (upsert por ExternalId = código EB, así nunca se duplica).
 * - Una propiedad que desaparece de EasyBroker queda en el CRM como
 *   eliminada, con la copia completa de sus datos, y en 21Online se retira.
 * - "Restaurar" vuelve a crearla en EasyBroker con esa copia; desde ahí se
 *   propaga como cualquier otra (en 21Online se reutiliza su anuncio).
 */
@Service
public class InventorySyncService {

	private static final Logger log = LoggerFactory.getLogger(InventorySyncService.class);

	private static final int PAGE_SIZE = 50;
	private static final int MAX_PAGES = 40;
	private static final int DETAIL_CONCURRENCY = 4;
	private static final int C21_MAX_WRITES_PER_RUN = 120;
	private static final Duration LEASE = Duration.ofMinutes(15);

	record Row(String id, String publicId, String status, String ebUpdatedAt, Instant deletedAt, String restoredAsPublicId, String c21ListingKey,
			String c21Status, String c21Hash, Map<String, Object> data) {
	}

	private static Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Row(rs.getString("id"), rs.getString("publicId"), rs.getString("status"), rs.getString("ebUpdatedAt"), Db.instant(rs, "deletedAt"),
				rs.getString("restoredAsPublicId"), rs.getString("c21ListingKey"), rs.getString("c21Status"), rs.getString("c21Hash"),
				Json.asMap(Db.json(rs, "data")));
	}

	private static final String ROW_COLUMNS = """
			id, "publicId", status, "ebUpdatedAt", "deletedAt", "restoredAsPublicId", "c21ListingKey", "c21Status", "c21Hash", data::text AS data
			""";

	private final JdbcClient jdbc;
	private final EasyBrokerService easyBroker;
	private final C21OnlineClient c21;
	private final AuditService audit;
	private final Env env;

	public InventorySyncService(JdbcClient jdbc, EasyBrokerService easyBroker, C21OnlineClient c21, AuditService audit, Env env) {
		this.jdbc = jdbc;
		this.easyBroker = easyBroker;
		this.c21 = c21;
		this.audit = audit;
		this.env = env;
	}

	public boolean c21Enabled() {
		return c21.configured() && !"false".equals(env.optional("C21_SYNC_ENABLED"));
	}

	// --- Estado y lease ------------------------------------------------------------

	public void ensureState(String companyId) {
		jdbc.sql("""
				INSERT INTO property_sync_state (id, "companyId", "createdAt", "updatedAt") VALUES (:id, :c, now(), now())
				ON CONFLICT ("companyId") DO NOTHING
				""").param("id", Ids.cuid()).param("c", companyId).update();
	}

	public Map<String, Object> state(String companyId) {
		ensureState(companyId);
		Map<String, Object> out = jdbc.sql("""
				SELECT "requestedAt", "lastRunAt", "lastSuccessAt", "lastStatus", "lastError", "lastSummary"::text AS summary
				FROM property_sync_state WHERE "companyId" = :c
				""").param("c", companyId).query((rs, i) -> Json.obj("requestedAt", Db.instant(rs, "requestedAt"), "lastRunAt", Db.instant(rs, "lastRunAt"),
				"lastSuccessAt", Db.instant(rs, "lastSuccessAt"), "lastStatus", rs.getString("lastStatus"), "lastError", rs.getString("lastError"),
				"lastSummary", Db.json(rs, "summary"))).single();
		out.put("c21Enabled", c21Enabled());
		return out;
	}

	/** Pide una corrida (el panel lo hace escribiendo requestedAt en la base). */
	public void request(String companyId, String by) {
		ensureState(companyId);
		jdbc.sql("UPDATE property_sync_state SET \"requestedAt\" = now(), \"requestedBy\" = :by, \"updatedAt\" = now() WHERE \"companyId\" = :c")
				.param("by", by).param("c", companyId).update();
	}

	/** ¿Toca correr? Hay petición, restauraciones pendientes o pasó el intervalo. */
	public boolean due(String companyId, Duration interval) {
		ensureState(companyId);
		Boolean due = jdbc.sql("""
				SELECT s."requestedAt" IS NOT NULL OR s."lastRunAt" IS NULL OR s."lastRunAt" < :limit
				  OR EXISTS (SELECT 1 FROM eb_properties p WHERE p."companyId" = s."companyId" AND p."restoreRequestedAt" IS NOT NULL)
				FROM property_sync_state s WHERE s."companyId" = :c
				""").param("limit", Db.ts(Instant.now().minus(interval))).param("c", companyId).query(Boolean.class).single();
		return Boolean.TRUE.equals(due);
	}

	private boolean claim(String companyId) {
		ensureState(companyId);
		return jdbc.sql("""
				UPDATE property_sync_state SET "lastStatus" = 'running', "lastRunAt" = now(), "requestedAt" = NULL, "updatedAt" = now()
				WHERE "companyId" = :c AND ("lastStatus" IS DISTINCT FROM 'running' OR "lastRunAt" IS NULL OR "lastRunAt" < :stale)
				""").param("c", companyId).param("stale", Db.ts(Instant.now().minus(LEASE))).update() == 1;
	}

	// --- Corrida completa ----------------------------------------------------------

	public Map<String, Object> run(String companyId, String trigger) {
		if (!claim(companyId)) return Json.obj("ok", true, "skipped", "already_running");
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("trigger", trigger);
		try {
			summary.put("restores", processRestores(companyId));
			summary.put("easybroker", syncEasyBroker(companyId));
			summary.put("c21", syncC21(companyId));
			Object c21Error = Json.asMap(summary.get("c21")) != null ? Json.asMap(summary.get("c21")).get("error") : null;
			String status = c21Error != null ? "partial" : "ok";
			jdbc.sql("""
					UPDATE property_sync_state SET "lastStatus" = :status, "lastSuccessAt" = now(), "lastError" = :error,
					  "lastSummary" = CAST(:summary AS jsonb), "updatedAt" = now() WHERE "companyId" = :c
					""").param("status", status).param("error", c21Error == null ? null : c21Error.toString())
					.param("summary", Db.jsonParam(summary)).param("c", companyId).update();
			summary.put("ok", true);
			return summary;
		} catch (RuntimeException error) {
			log.error("[INVENTORY] sincronización falló", error);
			String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
			jdbc.sql("""
					UPDATE property_sync_state SET "lastStatus" = 'error', "lastError" = :error, "lastSummary" = CAST(:summary AS jsonb), "updatedAt" = now()
					WHERE "companyId" = :c
					""").param("error", message.length() > 500 ? message.substring(0, 500) : message).param("summary", Db.jsonParam(summary))
					.param("c", companyId).update();
			summary.put("ok", false);
			summary.put("error", message);
			return summary;
		}
	}

	// --- EasyBroker → CRM ----------------------------------------------------------

	private List<Row> rows(String companyId) {
		return jdbc.sql("SELECT " + ROW_COLUMNS + " FROM eb_properties WHERE \"companyId\" = :c").param("c", companyId)
				.query(InventorySyncService::mapRow).list();
	}

	Map<String, Object> syncEasyBroker(String companyId) {
		EasyBrokerService.Page<Map<String, Object>> first = easyBroker.listAllPropertiesRawPage(1, PAGE_SIZE);
		List<Map<String, Object>> listed = new ArrayList<>(first.content());
		int totalPages = Math.min(first.total() != null ? (int) Math.ceil(first.total() / (double) PAGE_SIZE) : first.hasNext() ? MAX_PAGES : 1, MAX_PAGES);
		for (int page = 2; page <= totalPages; page++) {
			EasyBrokerService.Page<Map<String, Object>> next = easyBroker.listAllPropertiesRawPage(page, PAGE_SIZE);
			listed.addAll(next.content());
			if (next.content().isEmpty() || (first.total() == null && !next.hasNext())) break;
		}
		// Sin duplicados ni filas sin código.
		Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
		for (Map<String, Object> item : listed) {
			String publicId = Json.str(item, "public_id");
			if (publicId != null) byId.put(publicId, item);
		}
		boolean complete = !byId.isEmpty() && (first.total() == null || byId.size() >= first.total());

		Map<String, Row> existing = new HashMap<>();
		for (Row row : rows(companyId)) existing.put(row.publicId(), row);

		List<String> needsDetail = byId.values().stream().filter(item -> {
			Row row = existing.get(Json.str(item, "public_id"));
			String updatedAt = Json.str(item, "updated_at");
			return row == null || row.deletedAt() != null || row.ebUpdatedAt() == null || !Objects.equals(row.ebUpdatedAt(), updatedAt);
		}).map(item -> Json.str(item, "public_id")).toList();
		Map<String, Map<String, Object>> details = fetchDetails(needsDetail);

		int created = 0, updated = 0, reappeared = 0, detailErrors = 0;
		for (Map.Entry<String, Map<String, Object>> entry : byId.entrySet()) {
			String publicId = entry.getKey();
			Row row = existing.get(publicId);
			Map<String, Object> detail = details.get(publicId);
			boolean wanted = needsDetail.contains(publicId);
			if (wanted && detail == null) detailErrors++;
			if (row != null && !wanted) {
				// Sin cambios en EasyBroker: solo se marca como vista (el estado puede venir en el listado).
				String status = InventoryMapping.statusOf(entry.getValue());
				jdbc.sql("UPDATE eb_properties SET \"lastSeenAt\" = now(), status = :status, \"updatedAt\" = now() WHERE id = :id")
						.param("status", status).param("id", row.id()).update();
				continue;
			}
			Map<String, Object> data = detail != null ? new LinkedHashMap<>(detail) : new LinkedHashMap<>(entry.getValue());
			if (data.get("status") == null && entry.getValue().get("status") != null) data.put("status", entry.getValue().get("status"));
			// Si el detalle falló, no se guarda la marca de tiempo: se reintenta en la próxima corrida.
			String ebUpdatedAt = detail != null ? Json.str(entry.getValue(), "updated_at") : null;
			if (row != null && detail == null) {
				jdbc.sql("UPDATE eb_properties SET \"lastSeenAt\" = now(), \"deletedAt\" = NULL, \"deletedReason\" = NULL, \"updatedAt\" = now() WHERE id = :id")
						.param("id", row.id()).update();
				if (row.deletedAt() != null) reappeared++;
				continue;
			}
			upsert(companyId, publicId, data, ebUpdatedAt);
			if (row == null) created++;
			else if (row.deletedAt() != null) reappeared++;
			else updated++;
		}

		int deleted = 0;
		List<String> deletedIds = new ArrayList<>();
		if (complete) {
			for (Row row : existing.values()) {
				if (row.deletedAt() == null && !byId.containsKey(row.publicId())) {
					jdbc.sql("""
							UPDATE eb_properties SET "deletedAt" = now(), "deletedReason" = 'easybroker', "restoreRequestedAt" = NULL, "updatedAt" = now()
							WHERE id = :id AND "deletedAt" IS NULL
							""").param("id", row.id()).update();
					deleted++;
					deletedIds.add(row.publicId());
				}
			}
		}
		if (deleted > 0) {
			audit.log(companyId, null, null, "INVENTORY_DELETED", "SUCCESS", "Propiedades eliminadas en EasyBroker: " + String.join(", ", deletedIds),
					Json.obj("publicIds", deletedIds));
		}
		return Json.obj("listed", byId.size(), "complete", complete, "created", created, "updated", updated, "reappeared", reappeared, "deleted",
				deleted, "detailErrors", detailErrors);
	}

	private Map<String, Map<String, Object>> fetchDetails(List<String> publicIds) {
		Map<String, Map<String, Object>> out = new HashMap<>();
		if (publicIds.isEmpty()) return out;
		try (ExecutorService pool = Executors.newFixedThreadPool(DETAIL_CONCURRENCY, Thread.ofVirtual().factory())) {
			Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
			for (String publicId : publicIds) {
				futures.put(publicId, pool.submit(() -> {
					try {
						return easyBroker.getPropertyRaw(publicId);
					} catch (RuntimeException error) {
						log.warn("[INVENTORY] detalle de {} no disponible: {}", publicId, error.getMessage());
						return null;
					}
				}));
			}
			for (Map.Entry<String, Future<Map<String, Object>>> future : futures.entrySet()) {
				try {
					Map<String, Object> detail = future.getValue().get();
					if (detail != null && Json.str(detail, "public_id") != null) out.put(future.getKey(), detail);
				} catch (Exception ignored) {
					// Sin detalle: se reintenta en la próxima corrida.
				}
			}
		}
		return out;
	}

	private void upsert(String companyId, String publicId, Map<String, Object> data, String ebUpdatedAt) {
		InventoryMapping.Summary s = InventoryMapping.summarize(data);
		jdbc.sql("""
				INSERT INTO eb_properties (id, "companyId", "publicId", title, "propertyType", status, "operationType", price, currency, location, bedrooms,
				  bathrooms, "parkingSpaces", "constructionSize", "lotSize", "agentName", "agentEmail", "coverImageUrl", "imageCount", "publicUrl", data,
				  "ebCreatedAt", "ebUpdatedAt", "firstSyncedAt", "lastSeenAt", "createdAt", "updatedAt")
				VALUES (:id, :c, :publicId, :title, :type, :status, :opType, :price, :currency, :location, :bedrooms, :bathrooms, :parking, :construction,
				  :lot, :agentName, :agentEmail, :cover, :imageCount, :url, CAST(:data AS jsonb), :ebCreatedAt, :ebUpdatedAt, now(), now(), now(), now())
				ON CONFLICT ("companyId", "publicId") DO UPDATE SET title = EXCLUDED.title, "propertyType" = EXCLUDED."propertyType",
				  status = EXCLUDED.status, "operationType" = EXCLUDED."operationType", price = EXCLUDED.price, currency = EXCLUDED.currency,
				  location = EXCLUDED.location, bedrooms = EXCLUDED.bedrooms, bathrooms = EXCLUDED.bathrooms, "parkingSpaces" = EXCLUDED."parkingSpaces",
				  "constructionSize" = EXCLUDED."constructionSize", "lotSize" = EXCLUDED."lotSize", "agentName" = EXCLUDED."agentName",
				  "agentEmail" = EXCLUDED."agentEmail", "coverImageUrl" = EXCLUDED."coverImageUrl", "imageCount" = EXCLUDED."imageCount",
				  "publicUrl" = COALESCE(EXCLUDED."publicUrl", eb_properties."publicUrl"), data = EXCLUDED.data,
				  "ebCreatedAt" = COALESCE(EXCLUDED."ebCreatedAt", eb_properties."ebCreatedAt"), "ebUpdatedAt" = EXCLUDED."ebUpdatedAt",
				  "lastSeenAt" = now(), "deletedAt" = NULL, "deletedReason" = NULL, "updatedAt" = now()
				""").param("id", Ids.cuid()).param("c", companyId).param("publicId", publicId).param("title", s.title()).param("type", s.propertyType())
				.param("status", s.status()).param("opType", s.operationType()).param("price", s.price()).param("currency", s.currency())
				.param("location", s.location()).param("bedrooms", s.bedrooms()).param("bathrooms", s.bathrooms()).param("parking", s.parkingSpaces())
				.param("construction", s.constructionSize()).param("lot", s.lotSize()).param("agentName", s.agentName()).param("agentEmail", s.agentEmail())
				.param("cover", s.coverImageUrl()).param("imageCount", s.imageCount()).param("url", s.publicUrl()).param("data", Db.jsonParam(data))
				.param("ebCreatedAt", s.ebCreatedAt()).param("ebUpdatedAt", ebUpdatedAt).update();
	}

	// --- Restaurar ----------------------------------------------------------------

	Map<String, Object> processRestores(String companyId) {
		List<Row> pending = jdbc.sql("SELECT " + ROW_COLUMNS + """
				 FROM eb_properties WHERE "companyId" = :c AND "restoreRequestedAt" IS NOT NULL AND "deletedAt" IS NOT NULL
				""").param("c", companyId).query(InventorySyncService::mapRow).list();
		int restored = 0, failed = 0;
		for (Row row : pending) {
			try {
				String newId = restore(companyId, row);
				restored++;
				audit.log(companyId, null, null, "INVENTORY_RESTORED", "SUCCESS",
						"Propiedad " + row.publicId() + " restaurada en EasyBroker" + (newId.equals(row.publicId()) ? "" : " como " + newId),
						Json.obj("publicId", row.publicId(), "newPublicId", newId));
			} catch (RuntimeException error) {
				failed++;
				String message = restoreErrorMessage(error);
				jdbc.sql("UPDATE eb_properties SET \"restoreRequestedAt\" = NULL, \"restoreError\" = :error, \"updatedAt\" = now() WHERE id = :id")
						.param("error", message).param("id", row.id()).update();
				audit.log(companyId, null, null, "INVENTORY_RESTORED", "FAILED", "No se pudo restaurar " + row.publicId() + ": " + message);
			}
		}
		return Json.obj("requested", pending.size(), "restored", restored, "failed", failed);
	}

	static String restoreErrorMessage(RuntimeException error) {
		if (error instanceof EasyBrokerApiException eb) {
			Map<String, Object> body = Json.asMap(eb.body);
			Object errors = body != null ? (body.get("errors") != null ? body.get("errors") : body.get("error")) : eb.body;
			String text = "EasyBroker " + eb.status + (errors != null ? ": " + (errors instanceof String s ? s : Json.write(errors)) : "");
			return text.length() > 500 ? text.substring(0, 500) : text;
		}
		String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
		return message.length() > 500 ? message.substring(0, 500) : message;
	}

	private boolean existsInEasyBroker(String publicId) {
		try {
			easyBroker.getPropertyRaw(publicId);
			return true;
		} catch (EasyBrokerApiException error) {
			if (error.status == 404) return false;
			throw error;
		}
	}

	/** Devuelve el código con el que quedó en EasyBroker (el mismo si seguía existiendo). */
	private String restore(String companyId, Row row) {
		if (existsInEasyBroker(row.publicId())) {
			jdbc.sql("""
					UPDATE eb_properties SET "deletedAt" = NULL, "deletedReason" = NULL, "restoreRequestedAt" = NULL, "restoreError" = NULL,
					  "restoredAt" = now(), "ebUpdatedAt" = NULL, "updatedAt" = now() WHERE id = :id
					""").param("id", row.id()).update();
			return row.publicId();
		}
		Map<String, Object> created;
		try {
			created = easyBroker.createProperty(InventoryMapping.toEasyBrokerCreate(row.data(), true));
		} catch (EasyBrokerApiException error) {
			if (error.status != 422) throw error;
			// Las imágenes guardadas pueden ya no ser válidas: se crea sin ellas y se avisa.
			created = easyBroker.createProperty(InventoryMapping.toEasyBrokerCreate(row.data(), false));
		}
		String newId = Json.str(created, "public_id");
		if (newId == null) throw new IllegalStateException("EasyBroker no devolvió el código de la propiedad creada");
		Map<String, Object> data = new LinkedHashMap<>(created);
		if (data.get("status") == null) data.put("status", InventoryMapping.statusOf(row.data()));
		upsert(companyId, newId, data, null);
		// La nueva hereda el anuncio de 21Online de la eliminada: se actualiza el mismo, sin duplicar.
		jdbc.sql("""
				UPDATE eb_properties SET "restoredFromPublicId" = :old, "c21ListingKey" = COALESCE("c21ListingKey", :key), "c21Status" = 'pending',
				  "c21Hash" = NULL, "updatedAt" = now() WHERE "companyId" = :c AND "publicId" = :newId
				""").param("old", row.publicId()).param("key", row.c21ListingKey()).param("c", companyId).param("newId", newId).update();
		jdbc.sql("""
				UPDATE eb_properties SET "restoreRequestedAt" = NULL, "restoreError" = NULL, "restoredAt" = now(), "restoredAsPublicId" = :newId,
				  "c21ListingKey" = NULL, "c21Status" = 'transferred', "updatedAt" = now() WHERE id = :id
				""").param("newId", newId).param("id", row.id()).update();
		return newId;
	}

	// --- CRM → 21Online -------------------------------------------------------------

	Map<String, Object> syncC21(String companyId) {
		if (!c21Enabled()) return Json.obj("enabled", false);
		int created = 0, updated = 0, withdrawn = 0, unchanged = 0, failed = 0, skipped = 0, matched = 0;
		List<Row> all = rows(companyId);
		try {
			matched = matchExisting(companyId, all);
			if (matched > 0) all = rows(companyId);
		} catch (C21ApiException error) {
			if (error.status == 401 || error.status == 403) return Json.obj("enabled", true, "error", "Credenciales de 21Online rechazadas: " + error.getMessage());
			log.warn("[INVENTORY] no se pudo leer el inventario de 21Online para emparejar: {}", error.getMessage());
		}
		int writes = 0;
		for (Row row : all) {
			if (writes >= C21_MAX_WRITES_PER_RUN) break;
			if (row.restoredAsPublicId() != null || row.data() == null) continue;
			boolean deleted = row.deletedAt() != null;
			try {
				if (deleted) {
					if (row.c21ListingKey() == null || "removed".equals(row.c21Status())) continue;
					writes++;
					c21.request("PATCH", "/api/v1/external/Property/" + encode(row.c21ListingKey()), Json.obj("StandardStatus", "Withdrawn"));
					markC21(row.id(), row.c21ListingKey(), "removed", null, null);
					withdrawn++;
					continue;
				}
				Map<String, Object> payload = InventoryMapping.toReso(row.data(), false);
				String hash = InventoryMapping.hash(payload);
				if (hash.equals(row.c21Hash()) && "synced".equals(row.c21Status())) {
					unchanged++;
					continue;
				}
				String reso = (String) payload.get("StandardStatus");
				if (row.c21ListingKey() == null && !"Active".equals(reso) && !"Pending".equals(reso)) {
					// No se crean anuncios de propiedades no publicadas, vendidas o rentadas.
					if (!"not_sent".equals(row.c21Status())) markC21(row.id(), null, "not_sent", null, null);
					skipped++;
					continue;
				}
				writes++;
				Object response;
				if (row.c21ListingKey() != null) {
					Map<String, Object> patch = new LinkedHashMap<>(payload);
					patch.remove("ExternalId");
					response = c21.request("PATCH", "/api/v1/external/Property/" + encode(row.c21ListingKey()), patch);
					updated++;
				} else {
					response = c21.request("POST", "/api/v1/external/Property", payload);
					created++;
				}
				String listingKey = Json.str(Json.asMap(response), "ListingKey");
				markC21(row.id(), listingKey != null ? listingKey : row.c21ListingKey(), "synced", hash, null);
			} catch (C21ApiException error) {
				if (error.status == 401 || error.status == 403) {
					return Json.obj("enabled", true, "error", "Credenciales de 21Online rechazadas o sin permiso: " + error.getMessage(), "created", created,
							"updated", updated, "withdrawn", withdrawn);
				}
				failed++;
				markC21(row.id(), row.c21ListingKey(), "error", row.c21Hash(), error.getMessage());
			} catch (RuntimeException error) {
				failed++;
				markC21(row.id(), row.c21ListingKey(), "error", row.c21Hash(), error.getMessage());
			}
		}
		return Json.obj("enabled", true, "matched", matched, "created", created, "updated", updated, "withdrawn", withdrawn, "unchanged", unchanged,
				"skipped", skipped, "failed", failed);
	}

	private void markC21(String id, String listingKey, String status, String hash, String error) {
		String message = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
		jdbc.sql("""
				UPDATE eb_properties SET "c21ListingKey" = :key, "c21Status" = :status, "c21Hash" = :hash, "c21Error" = :error,
				  "c21SyncedAt" = CASE WHEN :status IN ('synced', 'removed') THEN now() ELSE "c21SyncedAt" END, "updatedAt" = now() WHERE id = :id
				""").param("key", listingKey).param("status", status).param("hash", hash).param("error", message).param("id", id).update();
	}

	static String matchSignature(Object price, Object city, Object bedrooms) {
		if (!(price instanceof Number p) || p.doubleValue() <= 0) return null;
		String cityKey = InventoryMapping.matchKey(city == null ? "" : city.toString());
		if (cityKey.isEmpty()) return null;
		long beds = bedrooms instanceof Number b ? Math.round(b.doubleValue()) : -1;
		return Math.round(p.doubleValue()) + "|" + cityKey + "|" + beds;
	}

	/**
	 * Propiedades que ya existían en 21Online antes de la integración (alta
	 * manual): se emparejan una sola vez por precio + ciudad + recámaras, solo
	 * cuando la coincidencia es única de ambos lados. Así no se duplican.
	 */
	int matchExisting(String companyId, List<Row> all) {
		List<Row> candidates = all.stream().filter(r -> r.deletedAt() == null && r.c21ListingKey() == null && r.restoredAsPublicId() == null
				&& ("pending".equals(r.c21Status()) || "error".equals(r.c21Status()))).toList();
		if (candidates.isEmpty()) return 0;
		Set<String> knownKeys = new HashSet<>();
		for (Row row : all) if (row.c21ListingKey() != null) knownKeys.add(row.c21ListingKey());

		Map<String, List<String>> remoteBySignature = new HashMap<>();
		String next = "/api/v1/external/Property?itemsPerPage=100";
		for (int page = 0; next != null && page < 50; page++) {
			Map<String, Object> body = Json.asMap(c21.request("GET", next, null));
			for (Map<String, Object> item : Json.objects(body == null ? null : body.get("value"))) {
				String key = Json.str(item, "ListingKey");
				if (key == null || knownKeys.contains(key)) continue;
				String signature = matchSignature(item.get("ListPrice"), item.get("City"), item.get("BedroomsTotal"));
				if (signature != null) remoteBySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(key);
			}
			next = body == null ? null : Json.str(body, "nextLink");
		}
		Map<String, List<Row>> localBySignature = new HashMap<>();
		for (Row row : candidates) {
			Map<String, Object> reso = InventoryMapping.toReso(row.data(), false);
			String signature = matchSignature(reso.get("ListPrice"), reso.get("City"), reso.get("BedroomsTotal"));
			if (signature != null) localBySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(row);
		}
		int matched = 0;
		for (Map.Entry<String, List<Row>> entry : localBySignature.entrySet()) {
			List<String> remote = remoteBySignature.get(entry.getKey());
			if (remote == null || remote.size() != 1 || entry.getValue().size() != 1) continue;
			Row row = entry.getValue().get(0);
			jdbc.sql("UPDATE eb_properties SET \"c21ListingKey\" = :key, \"c21Status\" = 'pending', \"updatedAt\" = now() WHERE id = :id")
					.param("key", remote.get(0)).param("id", row.id()).update();
			matched++;
		}
		if (matched > 0) audit.log(companyId, null, null, "INVENTORY_C21_MATCHED", "SUCCESS", matched + " propiedad(es) emparejadas con anuncios existentes de 21Online");
		return matched;
	}

	private static String encode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
	}

	// --- Panel / API ---------------------------------------------------------------

	public void requestRestore(String companyId, String publicId, String by) {
		int updated = jdbc.sql("""
				UPDATE eb_properties SET "restoreRequestedAt" = now(), "restoreRequestedBy" = :by, "restoreError" = NULL, "updatedAt" = now()
				WHERE "companyId" = :c AND "publicId" = :publicId AND "deletedAt" IS NOT NULL AND "restoredAsPublicId" IS NULL
				""").param("by", by).param("c", companyId).param("publicId", publicId.toUpperCase(Locale.ROOT)).update();
		if (updated == 0) throw com.c21inova.api.common.ApiException.notFound("No hay una propiedad eliminada con ese código.");
	}

	public List<Map<String, Object>> list(String companyId, boolean deleted) {
		return jdbc.sql("""
				SELECT "publicId", title, "propertyType", status, "operationType", price, currency, location, bedrooms, bathrooms, "coverImageUrl",
				  "publicUrl", "agentName", "ebUpdatedAt", "lastSeenAt", "deletedAt", "restoreRequestedAt", "restoreError", "restoredAsPublicId",
				  "c21ListingKey", "c21Status", "c21Error", "c21SyncedAt"
				FROM eb_properties WHERE "companyId" = :c AND ("deletedAt" IS NOT NULL) = :deleted ORDER BY "updatedAt" DESC
				""").param("c", companyId).param("deleted", deleted).query((rs, i) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			int count = rs.getMetaData().getColumnCount();
			for (int col = 1; col <= count; col++) row.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
			return row;
		}).list();
	}
}

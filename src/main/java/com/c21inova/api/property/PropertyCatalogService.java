package com.c21inova.api.property;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.conversation.LinkFetcher;
import com.c21inova.api.conversation.ListingExtract;
import com.c21inova.api.conversation.PropertyReference;
import com.c21inova.api.integration.EasyBrokerClient.EasyBrokerApiException;
import com.c21inova.api.integration.EasyBrokerService;
import com.c21inova.api.lead.AdvisorNotice;

/**
 * Índice local del inventario publicado de EasyBroker para buscar sin mandar
 * el catálogo completo a OpenAI. EasyBroker sigue siendo la fuente de verdad:
 * antes de afirmar precio o disponibilidad se consulta el detalle vivo.
 */
@Service
public class PropertyCatalogService {

	private static final Logger log = LoggerFactory.getLogger(PropertyCatalogService.class);

	private static final int PAGE_SIZE = 50;
	private static final int MAX_PAGES = 40;
	private static final long CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000;
	private static final int DETAIL_CONCURRENCY = 4;

	public record CacheEntry(String id, String publicId, String title, String propertyType, String location, Object operations, Integer bedrooms,
			Double bathrooms, String publicUrl, String internalId, String searchText, String ebUpdatedAt, boolean published, Instant syncedAt) {

		List<Double> prices() {
			List<Double> out = new ArrayList<>();
			for (Map<String, Object> op : Json.objects(operations)) {
				Double amount = Json.number(op, "amount");
				if (amount != null && amount > 0) out.add(amount);
			}
			return out;
		}
	}

	private static final String CACHE_COLUMNS = """
			id, "publicId", title, "propertyType", location, operations::text AS operations, bedrooms, bathrooms, "publicUrl", "internalId", "searchText",
			"ebUpdatedAt", published, "syncedAt"
			""";

	private static CacheEntry mapEntry(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new CacheEntry(rs.getString("id"), rs.getString("publicId"), rs.getString("title"), rs.getString("propertyType"), rs.getString("location"),
				Db.json(rs, "operations"), Db.integer(rs, "bedrooms"), Db.dbl(rs, "bathrooms"), rs.getString("publicUrl"), rs.getString("internalId"),
				rs.getString("searchText"), rs.getString("ebUpdatedAt"), rs.getBoolean("published"), Db.instant(rs, "syncedAt"));
	}

	public record SyncSummary(int total, int upserted, int detailsFetched, int unpublished) {
	}

	private final JdbcClient jdbc;
	private final EasyBrokerService easyBroker;
	private final LinkFetcher linkFetcher;
	private final Env env;

	public PropertyCatalogService(JdbcClient jdbc, EasyBrokerService easyBroker, LinkFetcher linkFetcher, Env env) {
		this.jdbc = jdbc;
		this.easyBroker = easyBroker;
		this.linkFetcher = linkFetcher;
		this.env = env;
	}

	static String operationsText(List<AdvisorNotice.Operation> operations) {
		if (operations == null) return "";
		List<String> parts = new ArrayList<>();
		for (AdvisorNotice.Operation op : operations) {
			String type = "rental".equals(op.type()) ? "renta" : "sale".equals(op.type()) ? "venta" : op.type() == null ? "" : op.type();
			parts.add((type + " " + (op.formattedAmount() == null ? "" : op.formattedAmount())).trim());
		}
		return String.join(" · ", parts);
	}

	static List<AdvisorNotice.Operation> operationsOf(Object json) {
		List<AdvisorNotice.Operation> out = new ArrayList<>();
		for (Map<String, Object> op : Json.objects(json)) {
			out.add(new AdvisorNotice.Operation(Json.str(op, "type"), Json.str(op, "formatted_amount"), Json.number(op, "amount")));
		}
		return out;
	}

	static String buildSearchText(EasyBrokerService.Property property) {
		List<String> parts = new ArrayList<>();
		for (String part : new String[] { property.publicId(), property.title(), property.propertyType(), property.location(),
				operationsText(property.operations()) }) {
			if (part != null && !part.isEmpty()) parts.add(part);
		}
		return PropertyReference.normalizeSearchText(String.join(" ", parts));
	}

	/** Operaciones como JSON con la misma forma que guardaba el panel (type, amount, currency, formatted_amount). */
	private static List<Map<String, Object>> operationsJson(List<AdvisorNotice.Operation> operations) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (operations == null) return out;
		for (AdvisorNotice.Operation op : operations) {
			Map<String, Object> json = new LinkedHashMap<>();
			if (op.type() != null) json.put("type", op.type());
			if (op.amount() != null) json.put("amount", op.amount());
			if (op.formattedAmount() != null) json.put("formatted_amount", op.formattedAmount());
			out.add(json);
		}
		return out;
	}

	private <T> List<EasyBrokerService.Page<T>> fetchAllPages(EasyBrokerService.Page<T> first, java.util.function.IntFunction<EasyBrokerService.Page<T>> page) {
		int totalPages = Math.min(first.total() != null ? (int) Math.ceil(first.total() / (double) PAGE_SIZE) : first.hasNext() ? MAX_PAGES : 1, MAX_PAGES);
		List<EasyBrokerService.Page<T>> pages = new ArrayList<>();
		pages.add(first);
		if (totalPages <= 1) return pages;
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<EasyBrokerService.Page<T>>> futures = new ArrayList<>();
			for (int i = 2; i <= totalPages; i++) {
				int number = i;
				futures.add(pool.submit(() -> page.apply(number)));
			}
			for (Future<EasyBrokerService.Page<T>> future : futures) {
				try {
					pages.add(future.get());
				} catch (Exception error) {
					throw new IllegalStateException(error.getCause() != null ? error.getCause() : error);
				}
			}
		}
		return pages;
	}

	/**
	 * Recorre TODAS las páginas publicadas. El listado no trae public_url: se
	 * pide el detalle solo de las propiedades nuevas o modificadas.
	 */
	public SyncSummary syncCatalog(String companyId) {
		EasyBrokerService.Page<EasyBrokerService.Property> first = easyBroker.listPublishedPropertiesPage(1, PAGE_SIZE);
		List<EasyBrokerService.Property> listed = new ArrayList<>();
		for (EasyBrokerService.Page<EasyBrokerService.Property> page : fetchAllPages(first, n -> easyBroker.listPublishedPropertiesPage(n, PAGE_SIZE))) {
			listed.addAll(page.content());
		}
		Map<String, CacheEntry> byId = new HashMap<>();
		for (CacheEntry entry : jdbc.sql("SELECT " + CACHE_COLUMNS + " FROM property_cache WHERE \"companyId\" = :c").param("c", companyId)
				.query(PropertyCatalogService::mapEntry).list()) {
			byId.put(entry.publicId(), entry);
		}
		List<EasyBrokerService.Property> needsDetail = listed.stream().filter(p -> {
			CacheEntry entry = byId.get(p.publicId());
			return entry == null || entry.publicUrl() == null || !Objects.equals(entry.ebUpdatedAt(), p.updatedAt());
		}).toList();
		Map<String, EasyBrokerService.Property> details = new HashMap<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(DETAIL_CONCURRENCY, Thread.ofVirtual().factory())) {
			List<Future<EasyBrokerService.Property>> futures = needsDetail.stream().map(p -> pool.submit(() -> {
				try {
					return easyBroker.getProperty(p.publicId());
				} catch (RuntimeException error) {
					return null;
				}
			})).toList();
			for (Future<EasyBrokerService.Property> future : futures) {
				try {
					EasyBrokerService.Property detail = future.get();
					if (detail != null) details.put(detail.publicId(), detail);
				} catch (Exception ignored) {
					// Sin detalle: se reintenta en la próxima sincronización.
				}
			}
		}

		Instant now = Instant.now();
		for (EasyBrokerService.Property property : listed) {
			EasyBrokerService.Property detail = details.get(property.publicId());
			EasyBrokerService.Property merged = property.mergedWith(detail);
			CacheEntry previous = byId.get(property.publicId());
			String publicUrl = merged.publicUrl() != null ? merged.publicUrl() : previous != null ? previous.publicUrl() : null;
			String internalId = detail != null
					? (detail.internalId() != null && !detail.internalId().trim().isEmpty()
							? detail.internalId().trim().substring(0, Math.min(60, detail.internalId().trim().length())) : null)
					: previous != null ? previous.internalId() : null;
			// Si el detalle falló, no se guarda la marca de tiempo: se reintenta en la próxima sincronización.
			String ebUpdatedAt = detail != null || !needsDetail.contains(property) ? property.updatedAt() : null;
			jdbc.sql("""
					INSERT INTO property_cache (id, "companyId", "publicId", title, "propertyType", location, operations, bedrooms, bathrooms, "publicUrl",
					  "internalId", "searchText", "ebUpdatedAt", published, "syncedAt")
					VALUES (:id, :c, :publicId, :title, :type, :location, CAST(:operations AS jsonb), :bedrooms, :bathrooms, :url, :internalId, :search,
					  :ebUpdatedAt, true, :now)
					ON CONFLICT ("companyId", "publicId") DO UPDATE SET title = EXCLUDED.title, "propertyType" = EXCLUDED."propertyType",
					  location = EXCLUDED.location, operations = EXCLUDED.operations, bedrooms = EXCLUDED.bedrooms, bathrooms = EXCLUDED.bathrooms,
					  "publicUrl" = EXCLUDED."publicUrl", "internalId" = EXCLUDED."internalId", "searchText" = EXCLUDED."searchText",
					  "ebUpdatedAt" = EXCLUDED."ebUpdatedAt", published = true, "syncedAt" = EXCLUDED."syncedAt"
					""").param("id", Ids.cuid()).param("c", companyId).param("publicId", property.publicId())
					.param("title", merged.title() == null ? "" : merged.title()).param("type", merged.propertyType()).param("location", merged.location())
					.param("operations", Db.jsonParam(operationsJson(merged.operations())))
					.param("bedrooms", merged.bedrooms() == null ? null : (int) Math.round(merged.bedrooms())).param("bathrooms", merged.bathrooms())
					.param("url", publicUrl).param("internalId", internalId).param("search", buildSearchText(merged)).param("ebUpdatedAt", ebUpdatedAt)
					.param("now", Db.ts(now)).update();
		}
		List<String> listedIds = listed.stream().map(EasyBrokerService.Property::publicId).toList();
		int unpublished = listedIds.isEmpty()
				? jdbc.sql("UPDATE property_cache SET published = false, \"syncedAt\" = :now WHERE \"companyId\" = :c AND published = true")
						.param("now", Db.ts(now)).param("c", companyId).update()
				: jdbc.sql("UPDATE property_cache SET published = false, \"syncedAt\" = :now WHERE \"companyId\" = :c AND published = true AND \"publicId\" NOT IN (:ids)")
						.param("now", Db.ts(now)).param("c", companyId).param("ids", listedIds).update();
		return new SyncSummary(listed.size(), listed.size(), details.size(), unpublished);
	}

	/** Sincroniza si el índice está vacío o viejo. Nunca lanza: un índice algo viejo es mejor que ninguno. */
	public Map<String, Object> ensureFresh(String companyId) {
		Instant newest = jdbc.sql("SELECT max(\"syncedAt\") FROM property_cache WHERE \"companyId\" = :c").param("c", companyId)
				.query((rs, i) -> Db.instant(rs, "max")).optional().orElse(null);
		if (newest != null && Instant.now().toEpochMilli() - newest.toEpochMilli() < CACHE_MAX_AGE_MS) {
			boolean hasListings = jdbc.sql("SELECT count(*) FROM portal_listings WHERE \"companyId\" = :c").param("c", companyId).query(Integer.class)
					.single() > 0;
			if (!hasListings) {
				try {
					syncPortalListings(companyId);
				} catch (RuntimeException error) {
					log.error("[CATALOG] anuncios de portales no sincronizados: {}", error.getMessage());
				}
			}
			return Json.obj("synced", false);
		}
		try {
			syncCatalog(companyId);
		} catch (RuntimeException error) {
			return Json.obj("synced", false, "error", error.getMessage() != null ? error.getMessage() : "Error desconocido");
		}
		try {
			syncPortalListings(companyId);
		} catch (RuntimeException error) {
			// Sin anuncios de portales solo se pierde la vía rápida de identificación.
			log.error("[CATALOG] anuncios de portales no sincronizados: {}", error.getMessage());
		}
		return Json.obj("synced", true);
	}

	public List<CacheEntry> publishedEntries(String companyId) {
		return jdbc.sql("SELECT " + CACHE_COLUMNS + " FROM property_cache WHERE \"companyId\" = :c AND published = true").param("c", companyId)
				.query(PropertyCatalogService::mapEntry).list();
	}

	public static Map<String, Object> toCandidate(CacheEntry entry) {
		String price = operationsText(operationsOf(entry.operations()));
		return Json.obj("public_id", entry.publicId(), "title", entry.title(), "type", entry.propertyType(), "location", entry.location(), "price",
				price.isEmpty() ? null : price, "url", entry.publicUrl());
	}

	/** ok → candidates + indexSize; si no, error (catalog_unavailable | catalog_empty). */
	public record SearchResult(boolean ok, List<Map<String, Object>> candidates, int indexSize, String error) {
	}

	public SearchResult search(String companyId, String query, String operation) {
		Map<String, Object> fresh = ensureFresh(companyId);
		List<CacheEntry> entries = publishedEntries(companyId);
		if (entries.isEmpty()) return new SearchResult(false, List.of(), 0, fresh.get("error") != null ? "catalog_unavailable" : "catalog_empty");
		// Un código explícito gana a la búsqueda por texto.
		List<String> codes = PropertyReference.extractEasyBrokerCodes(query);
		if (!codes.isEmpty()) {
			List<CacheEntry> exact = entries.stream().filter(e -> codes.contains(e.publicId())).toList();
			if (!exact.isEmpty()) return new SearchResult(true, exact.stream().map(PropertyCatalogService::toCandidate).toList(), entries.size(), null);
		}
		List<CacheEntry> filtered = "any".equals(operation) ? entries
				: entries.stream().filter(e -> Json.objects(e.operations()).stream().anyMatch(op -> operation.equals(op.get("type")))).toList();
		List<PropertyReference.Scored<CacheEntry>> scored = PropertyReference.scoreProperties(query, filtered, CacheEntry::title, CacheEntry::searchText, 5);
		return new SearchResult(true, scored.stream().map(s -> toCandidate(s.property())).toList(), entries.size(), null);
	}

	/**
	 * Solo campos públicos (nunca descripción privada, notas, comisiones ni
	 * dirección exacta). agent_email es solo para el backend.
	 */
	public static Map<String, Object> toPublicView(EasyBrokerService.Property property) {
		boolean hidePrices = Boolean.FALSE.equals(property.showPrices());
		List<Map<String, Object>> operations = new ArrayList<>();
		for (AdvisorNotice.Operation op : property.operations() == null ? List.<AdvisorNotice.Operation>of() : property.operations()) {
			String type = "rental".equals(op.type()) ? "renta" : "sale".equals(op.type()) ? "venta" : op.type() == null ? "" : op.type();
			operations.add(Json.obj("type", type, "price", hidePrices ? null : op.formattedAmount()));
		}
		String description = property.description() == null ? null : property.description().replaceAll("\\s+", " ");
		return Json.obj(
				"public_id", property.publicId(),
				"title", property.title(),
				"type", property.propertyType(),
				"location", property.location(),
				"operations", operations,
				"bedrooms", property.bedrooms(),
				"bathrooms", property.bathrooms(),
				"parking_spaces", property.parkingSpaces(),
				"construction_m2", property.constructionSize(),
				"lot_m2", property.lotSize(),
				"url", property.publicUrl(),
				"description_excerpt", description == null ? null : description.length() > 500 ? description.substring(0, 500) : description,
				"agent_email", property.agentEmail());
	}

	/** ok → property; error: not_found | easybroker_unavailable (nunca confundir un error con inexistencia). */
	public record Lookup(boolean ok, EasyBrokerService.Property property, String error) {
	}

	public Lookup lookup(String publicId) {
		try {
			return new Lookup(true, easyBroker.getProperty(publicId.toUpperCase(Locale.ROOT)), null);
		} catch (EasyBrokerApiException error) {
			return new Lookup(false, null, error.status == 404 ? "not_found" : "easybroker_unavailable");
		} catch (RuntimeException error) {
			return new Lookup(false, null, "easybroker_unavailable");
		}
	}

	// --- Identificación de enlaces -----------------------------------------------------------

	/**
	 * via: code | listing_slug | portal_listing | page_code | internal_code | portal_match.
	 * publicId null con ok=true → candidatas por similitud (requieren confirmación).
	 */
	public record LinkResolution(boolean ok, String publicId, String via, String portal, List<Map<String, Object>> candidates, String confidence,
			Map<String, Object> listing, String reason) {
	}

	private record Direct(String publicId, String via, boolean unpublished) {
	}

	private Direct resolveWithoutFetch(String companyId, String canonicalUrl) {
		PropertyReference.ParsedPropertyUrl parsed = PropertyReference.parsePropertyUrl(canonicalUrl);
		if (parsed == null) return null;
		if ("easybroker_code".equals(parsed.kind())) return new Direct(parsed.code(), "code", false);
		if ("easybroker_listing".equals(parsed.kind())) {
			List<Map<String, Object>> entries = jdbc.sql("""
					SELECT "publicId", "publicUrl", published FROM property_cache WHERE "companyId" = :c AND "publicUrl" IS NOT NULL
					""").param("c", companyId).query((rs, i) -> Json.obj("publicId", rs.getString("publicId"), "publicUrl", rs.getString("publicUrl"),
					"published", rs.getBoolean("published"))).list();
			for (Map<String, Object> entry : entries) {
				if (parsed.slug().equals(PropertyReference.listingSlugFromPublicUrl((String) entry.get("publicUrl")))) {
					return Boolean.TRUE.equals(entry.get("published")) ? new Direct((String) entry.get("publicId"), "listing_slug", false)
							: new Direct(null, null, true);
				}
			}
			return null;
		}
		PropertyReference.ListingKey key = PropertyReference.portalListingKeyFromUrl(canonicalUrl, null);
		if (key != null) {
			String publicId = jdbc.sql("""
					SELECT "publicId" FROM portal_listings WHERE "companyId" = :c AND portal = :portal AND "externalKey" = :key
					""").param("c", companyId).param("portal", key.portal()).param("key", key.externalKey()).query(String.class).optional().orElse(null);
			if (publicId != null) return new Direct(publicId, "portal_listing", false);
		}
		return null;
	}

	private List<String> findInternalCodeMatches(String companyId, String text) {
		String haystack = " " + PropertyReference.normalizeSearchText(text) + " ";
		List<String> out = new ArrayList<>();
		jdbc.sql("SELECT \"publicId\", \"internalId\" FROM property_cache WHERE \"companyId\" = :c AND \"internalId\" IS NOT NULL").param("c", companyId)
				.query((rs, i) -> {
					String code = PropertyReference.normalizeSearchText(rs.getString("internalId"));
					// Claves cortas o solo numéricas cortas coinciden por casualidad: no se usan.
					if (code.length() >= 4 && code.matches(".*\\d.*") && haystack.contains(" " + code + " ")) out.add(rs.getString("publicId"));
					return null;
				}).list();
		return out;
	}

	private static String formatPrice(Double price, String currency) {
		if (price == null || price == 0) return null;
		return "$" + NumberFormat.getIntegerInstance(Locale.US).format(Math.round(price)) + " " + (currency != null ? currency : "MXN");
	}

	private static String hostOf(String url) {
		PropertyReference.ParsedUrl parsed = PropertyReference.parseUrl(url);
		return parsed == null ? "" : parsed.host;
	}

	/**
	 * Identifica la propiedad de un enlace: código en la URL, enlace de
	 * EasyBroker, anuncio conocido del portal, redirecciones cortas, lectura
	 * pública de la página y, si nada es exacto, candidatas por título/zona/precio
	 * que SIEMPRE requieren confirmación del cliente.
	 */
	public LinkResolution resolveLink(String companyId, String rawUrl) {
		String canonical = PropertyReference.canonicalizeUrl(rawUrl);
		if (canonical == null) return new LinkResolution(false, null, null, null, null, null, null, "invalid_url");
		String portal = PropertyReference.portalOfHost(hostOf(canonical));

		PropertyReference.ParsedPropertyUrl parsedCode = PropertyReference.parsePropertyUrl(canonical);
		if (parsedCode != null && "easybroker_code".equals(parsedCode.kind())) {
			return new LinkResolution(true, parsedCode.code(), "code", portal, null, null, null, null);
		}

		ensureFresh(companyId);
		Direct direct = resolveWithoutFetch(companyId, canonical);
		if (direct != null && direct.publicId() != null) return new LinkResolution(true, direct.publicId(), direct.via(), portal, null, null, null, null);
		if (direct != null) return new LinkResolution(false, null, null, portal, null, null, null, "property_unpublished");

		ListingExtract.ListingInfo info = null;
		String source = "portal_page";
		LinkFetcher.Result page = linkFetcher.fetch(canonical, env.assistantLinkDomains());
		String finalPortal = portal;
		if (page.ok()) {
			String finalCanonical = PropertyReference.canonicalizeUrl(page.finalUrl());
			if (finalCanonical != null && !finalCanonical.equals(canonical)) {
				String redirectedPortal = PropertyReference.portalOfHost(hostOf(finalCanonical));
				finalPortal = redirectedPortal != null ? redirectedPortal : portal;
				Direct viaRedirect = resolveWithoutFetch(companyId, finalCanonical);
				if (viaRedirect != null && viaRedirect.publicId() != null) {
					return new LinkResolution(true, viaRedirect.publicId(), viaRedirect.via(), finalPortal, null, null, null, null);
				}
				if (viaRedirect != null) return new LinkResolution(false, null, null, finalPortal, null, null, null, "property_unpublished");
			}
			info = ListingExtract.extract(page.html());
			for (String code : info.codes().stream().limit(2).toList()) {
				Lookup lookup = lookup(code);
				if (lookup.ok()) return new LinkResolution(true, lookup.property().publicId(), "page_code", finalPortal, null, null, null, null);
			}
			List<String> internal = findInternalCodeMatches(companyId,
					(info.title() == null ? "" : info.title()) + " " + (info.description() == null ? "" : info.description()));
			if (internal.size() == 1) return new LinkResolution(true, internal.get(0), "internal_code", finalPortal, null, null, null, null);
			if (info.title() == null && info.description() == null) info = null;
		}
		if (info == null) {
			// Bloqueado, sin acceso o sin datos: solo las palabras del enlace.
			String words = ListingExtract.slugKeywords(canonical);
			if (words.split(" ").length < 2) {
				return new LinkResolution(false, null, null, finalPortal, null, null, null, page.ok() ? "no_property_info" : page.reason());
			}
			info = new ListingExtract.ListingInfo(words, null, null, null, null, null, List.of());
			source = "url_words";
		}

		Map<String, Object> listing = Json.obj("title", info.title(), "price", formatPrice(info.price(), info.currency()), "location", info.location(),
				"bedrooms", info.bedrooms(), "source", source, "portal", hostOf(canonical).replaceFirst("^www\\.", ""));

		List<CacheEntry> entries = publishedEntries(companyId);
		if (entries.isEmpty()) return new LinkResolution(false, null, null, finalPortal, null, null, listing, "inventory_unavailable");
		List<ListingExtract.CatalogListing<CacheEntry>> catalog = entries.stream()
				.map(e -> new ListingExtract.CatalogListing<>(e, e.publicId(), e.title(), e.searchText(), e.prices(), e.bedrooms())).toList();
		ListingExtract.MatchResult<CacheEntry> result = ListingExtract.matchToCatalog(info, catalog, 3);
		// Confianza baja = probablemente anuncio de otra inmobiliaria.
		if (result.matches().isEmpty() || "low".equals(result.confidence())) {
			return new LinkResolution(false, null, null, finalPortal, null, null, listing, "not_in_inventory");
		}
		List<Map<String, Object>> candidates = new ArrayList<>();
		for (ListingExtract.ListingMatch<CacheEntry> match : result.matches()) {
			Map<String, Object> candidate = toCandidate(match.property().entry());
			candidate.put("price_match", match.priceMatch());
			candidates.add(candidate);
		}
		String confidence = "url_words".equals(source) && "high".equals(result.confidence()) ? "medium" : result.confidence();
		return new LinkResolution(true, null, "portal_match", finalPortal, candidates, confidence, listing, null);
	}

	// --- Anuncios en portales reportados por EasyBroker ---------------------------------------

	public Map<String, Object> syncPortalListings(String companyId) {
		EasyBrokerService.Page<EasyBrokerService.PropertyIntegrations> first = easyBroker.listPropertyIntegrationsPage(1, PAGE_SIZE);
		List<EasyBrokerService.PropertyIntegrations> properties = new ArrayList<>();
		for (EasyBrokerService.Page<EasyBrokerService.PropertyIntegrations> page : fetchAllPages(first, n -> easyBroker.listPropertyIntegrationsPage(n, PAGE_SIZE))) {
			properties.addAll(page.content());
		}
		Instant now = Instant.now();
		int saved = 0;
		for (EasyBrokerService.PropertyIntegrations property : properties) {
			for (EasyBrokerService.PortalIntegration integration : property.integrations()) {
				String symbol = integration.partnerSymbol() == null ? null : integration.partnerSymbol().trim();
				if (symbol == null || symbol.isEmpty()) continue;
				String url = integration.listingUrl() == null || integration.listingUrl().trim().isEmpty() ? null : integration.listingUrl().trim();
				String canonical = url != null ? PropertyReference.canonicalizeUrl(url) : null;
				PropertyReference.ListingKey key = canonical != null ? PropertyReference.portalListingKeyFromUrl(canonical, integration.remoteListingId()) : null;
				String externalKey = key != null ? key.externalKey()
						: integration.remoteListingId() != null && !integration.remoteListingId().trim().isEmpty() ? integration.remoteListingId().trim() : null;
				if (externalKey == null) continue;
				String portal = key != null ? key.portal() : symbol;
				String portalName = integration.partnerName() != null
						? integration.partnerName().substring(0, Math.min(80, integration.partnerName().length())) : symbol;
				jdbc.sql("""
						INSERT INTO portal_listings (id, "companyId", "publicId", portal, "portalName", "externalKey", "listingUrl", published, status, source,
						  "syncedAt", "createdAt")
						VALUES (:id, :c, :publicId, :portal, :name, :key, :url, :published, :status, 'easybroker_integration', :now, :now)
						ON CONFLICT ("companyId", portal, "externalKey") DO UPDATE SET "publicId" = EXCLUDED."publicId", "portalName" = EXCLUDED."portalName",
						  "listingUrl" = EXCLUDED."listingUrl", published = EXCLUDED.published, status = EXCLUDED.status, "syncedAt" = EXCLUDED."syncedAt"
						""").param("id", Ids.cuid()).param("c", companyId).param("publicId", property.publicId()).param("portal", portal)
						.param("name", portalName).param("key", externalKey).param("url", canonical).param("published", integration.published())
						.param("status", integration.status()).param("now", Db.ts(now)).update();
				saved++;
			}
		}
		return Json.obj("total", properties.size(), "saved", saved);
	}
}

package com.c21inova.api.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.c21inova.api.common.Json;

/**
 * Lectura de anuncios de portales para cruzarlos con el inventario propio.
 * Puro: recibe HTML/URL ya descargados. Todo lo extraído es DATO del anuncio,
 * nunca instrucción. No se intenta saltar bloqueos de los portales.
 */
public final class ListingExtract {

	private ListingExtract() {
	}

	public record ListingInfo(String title, String description, Double price, String currency, String location, Integer bedrooms, List<String> codes) {
	}

	static String decodeEntities(String text) {
		String out = text.replace("&amp;", "&").replace("&quot;", "\"").replaceAll("&#0?39;|&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&nbsp;", " ");
		Matcher numeric = Pattern.compile("&#(\\d+);").matcher(out);
		StringBuilder buffer = new StringBuilder();
		while (numeric.find()) numeric.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(numeric.group(1)))));
		numeric.appendTail(buffer);
		return buffer.toString().replaceAll("(?i)\\\\u002F", "/").replaceAll("\\s+", " ").trim();
	}

	static String metaContent(String html, String key) {
		String escaped = Pattern.quote(key);
		Matcher a = Pattern.compile("<meta[^>]+(?:property|name)=[\"']" + escaped + "[\"'][^>]*content=[\"']([^\"']{1,2000})[\"']", Pattern.CASE_INSENSITIVE)
				.matcher(html);
		if (a.find()) return decodeEntities(a.group(1));
		Matcher b = Pattern.compile("<meta[^>]+content=[\"']([^\"']{1,2000})[\"'][^>]*(?:property|name)=[\"']" + escaped + "[\"']", Pattern.CASE_INSENSITIVE)
				.matcher(html);
		return b.find() ? decodeEntities(b.group(1)) : null;
	}

	static Double parsePrice(Object raw) {
		if (raw instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
		if (!(raw instanceof String text)) return null;
		String digits = text.replaceAll("[^\\d.]", "");
		try {
			double value = Double.parseDouble(digits);
			return Double.isFinite(value) && value > 0 ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static final class Partial {
		String title;
		String description;
		Double price;
		String currency;
		String location;
		Integer bedrooms;
	}

	private static void visit(Object node, int depth, Partial out) {
		if (node == null || depth > 6) return;
		if (node instanceof List<?> list) {
			for (Object child : list) visit(child, depth + 1, out);
			return;
		}
		Map<String, Object> obj = Json.asMap(node);
		if (obj == null) return;
		String type = String.valueOf(obj.getOrDefault("@type", ""));
		if (type.equals("WebSite") || type.equals("Organization") || type.equals("BreadcrumbList")) return;
		if (out.title == null && obj.get("name") instanceof String name && name.length() > 8) out.title = decodeEntities(name);
		if (out.description == null && obj.get("description") instanceof String description) {
			String decoded = decodeEntities(description);
			out.description = decoded.length() > 600 ? decoded.substring(0, 600) : decoded;
		}
		Map<String, Object> offers = Json.asMap(obj.get("offers"));
		if (offers != null) {
			if (out.price == null) out.price = parsePrice(offers.get("price"));
			if (out.currency == null && offers.get("priceCurrency") instanceof String currency) out.currency = currency;
		}
		if (out.price == null) out.price = parsePrice(obj.get("price"));
		Object rooms = obj.get("numberOfBedrooms") != null ? obj.get("numberOfBedrooms") : obj.get("numberOfRooms");
		if (out.bedrooms == null && (rooms instanceof Number || rooms instanceof String)) {
			try {
				int n = rooms instanceof Number num ? num.intValue() : Integer.parseInt(((String) rooms).replaceAll("\\D", ""));
				if (n > 0 && n < 50) out.bedrooms = n;
			} catch (NumberFormatException ignored) {
				// Sin número utilizable.
			}
		}
		Map<String, Object> address = Json.asMap(obj.get("address"));
		if (out.location == null && address != null) {
			List<String> parts = new ArrayList<>();
			for (String key : List.of("streetAddress", "addressLocality", "addressRegion")) {
				if (address.get(key) instanceof String part && !part.isEmpty()) parts.add(part);
			}
			if (!parts.isEmpty()) out.location = decodeEntities(String.join(", ", parts));
		}
		for (Object value : obj.values()) if (value instanceof Map || value instanceof List) visit(value, depth + 1, out);
	}

	private static Partial readJsonLd(String html) {
		Partial out = new Partial();
		Matcher blocks = Pattern.compile("<script[^>]*application/ld\\+json[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE).matcher(html);
		int count = 0;
		while (blocks.find() && count++ < 10) {
			try {
				visit(Json.parse(blocks.group(1).trim()), 0, out);
			} catch (RuntimeException invalid) {
				// JSON-LD inválido: se ignora.
			}
		}
		return out;
	}

	private static final Pattern PRICE_IN_TEXT = Pattern.compile("\\$\\s?([\\d]{1,3}(?:[,.]\\d{3})+|\\d{5,})");
	private static final Pattern BEDROOMS_IN_TEXT = Pattern.compile("(\\d{1,2})\\s*(?:rec[aá]maras?|habitaciones?|dormitorios?|cuartos?)",
			Pattern.CASE_INSENSITIVE);

	public static ListingInfo extract(String html) {
		Partial ld = readJsonLd(html);
		String ogTitle = metaContent(html, "og:title");
		Matcher titleTag = Pattern.compile("<title[^>]*>([^<]{1,300})</title>", Pattern.CASE_INSENSITIVE).matcher(html);
		String rawTitle = ld.title != null ? ld.title : ogTitle != null ? ogTitle : titleTag.find() ? decodeEntities(titleTag.group(1)) : null;
		String title = null;
		if (rawTitle != null) {
			title = rawTitle.replaceAll("(?i)\\s*[|–-]\\s*(MercadoLibre|Mercado Libre|Vivanuncios|Inmuebles24|Lamudi|Facebook).*$", "");
			if (title.length() > 200) title = title.substring(0, 200);
		}
		String description = ld.description != null ? ld.description : metaContent(html, "og:description");
		if (description == null) description = metaContent(html, "description");
		if (description != null && description.length() > 600) description = description.substring(0, 600);
		String textForNumbers = (ogTitle == null ? "" : ogTitle) + " " + (title == null ? "" : title) + " " + (description == null ? "" : description);
		Matcher priceMatch = PRICE_IN_TEXT.matcher(textForNumbers);
		boolean hasPrice = priceMatch.find();
		Matcher bedroomsMatch = BEDROOMS_IN_TEXT.matcher(textForNumbers);
		boolean hasBedrooms = bedroomsMatch.find();
		// Solo el contenido principal cuenta para códigos (no los anuncios "similares").
		String ogUrl = metaContent(html, "og:url");
		List<String> codes = PropertyReference.extractEasyBrokerCodes((title == null ? "" : title) + " " + (description == null ? "" : description) + " "
				+ (ogUrl == null ? "" : ogUrl));
		List<String> bodyCodes = PropertyReference.extractEasyBrokerCodes(html);
		return new ListingInfo(
				title == null || title.isEmpty() ? null : title,
				description,
				ld.price != null ? ld.price : hasPrice ? parsePrice(priceMatch.group(1)) : null,
				ld.currency != null ? ld.currency : hasPrice ? "MXN" : null,
				ld.location,
				ld.bedrooms != null ? ld.bedrooms : hasBedrooms ? Integer.valueOf(bedroomsMatch.group(1)) : null,
				!codes.isEmpty() ? codes : bodyCodes.size() == 1 ? bodyCodes : List.of());
	}

	private static final Set<String> SLUG_NOISE = Set.of("propiedad", "propiedades", "detalle", "anuncio", "clasificado", "veclcain", "vecl", "jm", "mlm",
			"html", "a", "s", "for", "sale", "item", "listing", "listings", "inmueble", "inmuebles", "marketplace", "www", "com", "mx", "p", "id");

	/** Palabras útiles de la URL cuando el portal no deja leer la página. */
	public static String slugKeywords(String rawUrl) {
		PropertyReference.ParsedUrl url = PropertyReference.parseUrl(rawUrl);
		if (url == null) return "";
		List<String> words = new ArrayList<>();
		for (String word : PropertyReference.normalizeSearchText(PropertyReference.decode(url.path)).split(" ")) {
			if (word.length() > 1 && !SLUG_NOISE.contains(word) && !word.matches(".*\\d.*")) words.add(word);
			if (words.size() >= 20) break;
		}
		return String.join(" ", words);
	}

	// --- Cruce con el inventario propio ----------------------------------------------

	public record CatalogListing<T>(T entry, String publicId, String title, String searchText, List<Double> prices, Integer bedrooms) {
	}

	public record ListingMatch<T>(CatalogListing<T> property, double score, String priceMatch) {
	}

	public record MatchResult<T>(List<ListingMatch<T>> matches, String confidence) {
	}

	static String priceRelation(Double listingPrice, List<Double> prices) {
		if (listingPrice == null || listingPrice == 0 || prices.isEmpty()) return "unknown";
		double best = prices.stream().mapToDouble(p -> Math.abs(p - listingPrice) / Math.max(p, listingPrice)).min().orElse(1);
		if (best <= 0.02) return "exact";
		if (best <= 0.1) return "close";
		return "different";
	}

	/** Palabras del sector que no identifican una propiedad ni una zona. */
	private static final Set<String> GENERIC_WORDS = Set.of("casa", "casas", "venta", "renta", "vende", "rento", "departamento", "depto", "terreno",
			"lote", "lotes", "local", "oficina", "bodega", "loft", "residencia", "residencial", "fraccionamiento", "colonia", "zona", "coto", "privada",
			"condominio", "preventa", "lujo", "nueva", "nuevo", "hermosa", "amplia", "amplio", "excelente", "ubicada", "ubicacion", "frente", "cerca",
			"vista", "alberca", "jardin", "terraza", "zapopan", "guadalajara", "jalisco", "tlajomulco", "tlaquepaque", "tonala", "mexico", "estrenar",
			"oportunidad", "recamaras", "banos", "remodelar", "exclusiva", "exclusivo", "moderna", "moderno", "gran", "precio");

	private static final Map<String, String> PROPERTY_TYPES = Map.ofEntries(Map.entry("casa", "casa"), Map.entry("casas", "casa"),
			Map.entry("residencia", "casa"), Map.entry("departamento", "departamento"), Map.entry("depto", "departamento"),
			Map.entry("loft", "departamento"), Map.entry("terreno", "terreno"), Map.entry("lote", "terreno"), Map.entry("lotes", "terreno"),
			Map.entry("bodega", "bodega"), Map.entry("oficina", "oficina"), Map.entry("local", "local"));

	static String propertyTypeOf(String text) {
		for (String word : PropertyReference.normalizeSearchText(text).split(" ")) {
			if (PROPERTY_TYPES.containsKey(word)) return PROPERTY_TYPES.get(word);
		}
		return null;
	}

	/**
	 * Puntúa cada propiedad del inventario contra el anuncio con pesos por rareza
	 * (IDF). Una candidata necesita al menos una coincidencia distintiva de
	 * nombre/zona: precio y recámaras solo confirman, nunca identifican solos.
	 */
	public static <T> MatchResult<T> matchToCatalog(ListingInfo info, List<CatalogListing<T>> catalog, int limit) {
		String titleAndZone = String.join(" ", java.util.stream.Stream.of(info.title(), info.location()).filter(s -> s != null && !s.isEmpty()).toList());
		List<String> queryTokens = PropertyReference.searchTokens(titleAndZone).stream().filter(t -> !GENERIC_WORDS.contains(t)).toList();
		if (queryTokens.isEmpty() || catalog.isEmpty()) return new MatchResult<>(List.of(), "low");
		String listingType = propertyTypeOf(info.title() == null ? "" : info.title());

		record Doc<T>(CatalogListing<T> property, String title, String haystack) {
		}
		List<Doc<T>> docs = catalog.stream().map(p -> new Doc<>(p, " " + PropertyReference.normalizeSearchText(p.title()) + " ", " " + p.searchText() + " "))
				.toList();
		int n = docs.size();
		Map<String, Integer> df = new HashMap<>();
		Map<String, Double> idf = new HashMap<>();
		for (String token : queryTokens) {
			int count = (int) docs.stream().filter(doc -> doc.haystack().contains(" " + token + " ") || doc.title().contains(" " + token + " ")).count();
			df.put(token, count);
			idf.put(token, count == 0 ? 0 : Math.log((n + 1) / (count + 0.5)));
		}
		int distinctiveLimit = Math.max(2, (int) Math.floor(n * 0.1));

		String[] queryWords = PropertyReference.normalizeSearchText(titleAndZone).split(" ");
		Set<String> bigrams = new LinkedHashSet<>();
		for (int i = 0; i < queryWords.length - 1; i++) {
			String a = queryWords[i];
			String b = queryWords[i + 1];
			if (a.length() >= 4 && b.length() >= 4 && !(GENERIC_WORDS.contains(a) && GENERIC_WORDS.contains(b))) bigrams.add(a + " " + b);
		}

		record Scored<T>(CatalogListing<T> property, double score, String priceMatch, int distinctive) {
		}
		List<Scored<T>> scored = new ArrayList<>();
		for (Doc<T> doc : docs) {
			double text = 0;
			int distinctive = 0;
			for (String bigram : bigrams) {
				if (doc.haystack().contains(" " + bigram + " ") || doc.title().contains(" " + bigram + " ")) {
					text += 3;
					distinctive += 1;
				}
			}
			for (String token : queryTokens) {
				boolean inTitle = doc.title().contains(" " + token + " ");
				boolean inText = inTitle || doc.haystack().contains(" " + token + " ");
				if (!inText) continue;
				boolean isDistinctive = df.get(token) <= distinctiveLimit && token.length() >= 4;
				double weight = idf.getOrDefault(token, 0.0) * (isDistinctive ? 1 : 0.25);
				text += weight * (inTitle ? 1.5 : 1);
				if (isDistinctive) distinctive += 1;
			}
			String priceMatch = priceRelation(info.price(), doc.property().prices());
			double priceScore = switch (priceMatch) {
				case "exact" -> 2;
				case "close" -> 0.5;
				case "different" -> -2;
				default -> 0;
			};
			double bedroomScore = info.bedrooms() != null && info.bedrooms() != 0 && doc.property().bedrooms() != null && doc.property().bedrooms() != 0
					? (info.bedrooms().equals(doc.property().bedrooms()) ? 0.5 : -0.5) : 0;
			String candidateType = propertyTypeOf(doc.property().title());
			if (candidateType == null) candidateType = propertyTypeOf(doc.property().searchText());
			double typeScore = listingType != null && candidateType != null && !listingType.equals(candidateType) ? -4 : 0;
			scored.add(new Scored<>(doc.property(), text + typeScore + (distinctive > 0 ? priceScore + bedroomScore : 0), priceMatch, distinctive));
		}

		List<Scored<T>> ranked = scored.stream().filter(s -> s.distinctive() > 0 && s.score() > 1.5)
				.sorted(Comparator.comparingDouble((Scored<T> s) -> s.score()).reversed()).toList();
		String confidence = "low";
		if (!ranked.isEmpty()) {
			Scored<T> top = ranked.get(0);
			boolean clearLead = ranked.size() < 2 || top.score() - ranked.get(1).score() >= 1.5;
			if ("exact".equals(top.priceMatch()) && clearLead) confidence = "high";
			else if (!"different".equals(top.priceMatch())) confidence = "medium";
		}
		List<ListingMatch<T>> matches = ranked.stream().limit(limit).map(s -> new ListingMatch<>(s.property(), s.score(), s.priceMatch())).toList();
		return new MatchResult<>(matches, confidence);
	}
}

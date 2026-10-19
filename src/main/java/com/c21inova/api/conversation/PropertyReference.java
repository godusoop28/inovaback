package com.c21inova.api.conversation;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detección de referencias a propiedades en texto libre: códigos
 * EasyBroker y enlaces. Puro (sin red): la resolución contra el inventario
 * vive en PropertyCatalogService.
 */
public final class PropertyReference {

	private PropertyReference() {
	}

	// Tolerante a minúsculas y a espacios/guion bajo ("eb wn8585", "EB_WN8585").
	private static final Pattern EB_CODE = Pattern.compile("\\bEB[\\s_-]?([A-Z]{2}\\d{3,6})\\b", Pattern.CASE_INSENSITIVE);

	/** Códigos EasyBroker normalizados ("EB-WN8585"), sin duplicados, en orden de aparición. */
	public static List<String> extractEasyBrokerCodes(String text) {
		Set<String> codes = new LinkedHashSet<>();
		if (text == null) return new ArrayList<>();
		Matcher matcher = EB_CODE.matcher(text);
		while (matcher.find()) codes.add("EB-" + matcher.group(1).toUpperCase(Locale.ROOT));
		return new ArrayList<>(codes);
	}

	private static final Pattern URL_PATTERN = Pattern.compile(
			"\\bhttps?://[^\\s<>\"'`]+|\\bwww\\.[^\\s<>\"'`]+|\\b(?:[a-z0-9-]+\\.)*(?:easybroker\\.com|mercadolibre\\.com\\.mx|meli\\.la|vivanuncios\\.com\\.mx|inmuebles24\\.com|lamudi\\.com\\.mx|propiedades\\.com|casasyterrenos\\.com|century21mexico\\.com|facebook\\.com|fb\\.me|fb\\.com|clasco\\.mx|pincali\\.com|valoresampi\\.mx)/[^\\s<>\"'`]*",
			Pattern.CASE_INSENSITIVE);

	/** URLs del texto, sin puntuación final pegada. Máximo 3. */
	public static List<String> extractUrls(String text) {
		List<String> urls = new ArrayList<>();
		if (text == null) return urls;
		Matcher matcher = URL_PATTERN.matcher(text);
		while (matcher.find()) {
			String raw = matcher.group().replaceAll("[),.;:!?¡¿*_]+$", "");
			if (!raw.toLowerCase(Locale.ROOT).matches("^https?://.*")) raw = "https://" + raw;
			ParsedUrl url = ParsedUrl.parse(raw);
			if (url != null && !urls.contains(url.toString())) urls.add(url.toString());
			if (urls.size() >= 3) break;
		}
		return urls;
	}

	// --- URL mutable mínima (equivalente al URL de WHATWG para lo que se usa aquí) ----

	public static final class ParsedUrl {
		public String scheme;
		public String host;
		public int port;
		public String path;
		/** Pares de query en orden, sin decodificar. */
		public List<String[]> query;
		public String userInfo;

		static ParsedUrl parse(String raw) {
			try {
				URI uri = new URI(raw.trim());
				if (uri.getScheme() == null || uri.getHost() == null) return null;
				ParsedUrl url = new ParsedUrl();
				url.scheme = uri.getScheme().toLowerCase(Locale.ROOT);
				url.host = uri.getHost().toLowerCase(Locale.ROOT);
				url.port = uri.getPort();
				url.path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
				url.userInfo = uri.getRawUserInfo();
				url.query = new ArrayList<>();
				if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
					for (String pair : uri.getRawQuery().split("&")) {
						if (pair.isEmpty()) continue;
						int eq = pair.indexOf('=');
						url.query.add(eq >= 0 ? new String[] { pair.substring(0, eq), pair.substring(eq + 1) } : new String[] { pair, "" });
					}
				}
				return url;
			} catch (Exception invalid) {
				// URL con caracteres no escapados: se intenta codificarlos.
				try {
					String fixed = raw.trim().replace(" ", "%20").replace("|", "%7C").replace("{", "%7B").replace("}", "%7D").replace("^", "%5E");
					if (fixed.equals(raw.trim())) return null;
					return parse(fixed);
				} catch (Exception ignored) {
					return null;
				}
			}
		}

		public String param(String name) {
			for (String[] pair : query) if (decode(pair[0]).equals(name)) return decode(pair[1]);
			return null;
		}

		public String search() {
			if (query.isEmpty()) return "";
			StringBuilder out = new StringBuilder("?");
			for (int i = 0; i < query.size(); i++) {
				if (i > 0) out.append('&');
				out.append(query.get(i)[0]).append('=').append(query.get(i)[1]);
			}
			return out.toString();
		}

		@Override
		public String toString() {
			boolean defaultPort = port < 0 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
			return scheme + "://" + (userInfo != null ? userInfo + "@" : "") + host + (defaultPort ? "" : ":" + port) + path + search();
		}
	}

	public static ParsedUrl parseUrl(String raw) {
		return raw == null ? null : ParsedUrl.parse(raw);
	}

	static String decode(String value) {
		try {
			return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException invalid) {
			return value;
		}
	}

	private static final Pattern TRACKING_PARAMS = Pattern.compile(
			"^(utm_\\w+|fbclid|gclid|gbraid|wbraid|dclid|msclkid|igshid|igsh|mibextid|si|_gl|mc_[a-z]+|ref|ref_src|ref_url|source|s|share_id|rdid|sfnsn|from|tracking_id|searchVariation|position|type|reco_\\w+|c_\\w+|matt_\\w+)$",
			Pattern.CASE_INSENSITIVE);

	private static ParsedUrl unwrapRedirect(ParsedUrl url) {
		String host = url.host;
		String target = null;
		if (Set.of("l.facebook.com", "lm.facebook.com", "l.messenger.com", "l.instagram.com").contains(host) && url.path.startsWith("/l.php")) {
			target = url.param("u");
		} else if (host.matches("^(www\\.)?google\\.[a-z.]+$") && url.path.equals("/url")) {
			target = url.param("q") != null ? url.param("q") : url.param("url");
		}
		if (target == null) return null;
		ParsedUrl inner = ParsedUrl.parse(target);
		return inner != null && ("https".equals(inner.scheme) || "http".equals(inner.scheme)) ? inner : null;
	}

	/**
	 * Forma canónica: desenvuelve redirecciones conocidas, quita rastreo y
	 * fragmento, y pasa http a https.
	 */
	public static String canonicalizeUrl(String rawUrl) {
		if (rawUrl == null) return null;
		String trimmed = rawUrl.trim();
		ParsedUrl url = ParsedUrl.parse(trimmed.toLowerCase(Locale.ROOT).matches("^https?://.*") ? trimmed : "https://" + trimmed);
		if (url == null) return null;
		for (int i = 0; i < 2; i++) {
			ParsedUrl inner = unwrapRedirect(url);
			if (inner == null) break;
			url = inner;
		}
		if ("http".equals(url.scheme)) url.scheme = "https";
		url.query.removeIf(pair -> TRACKING_PARAMS.matcher(decode(pair[0])).matches());
		url.host = url.host.toLowerCase(Locale.ROOT);
		return url.toString();
	}

	/** Símbolo del portal (el mismo que usa EasyBroker en /property_integrations cuando existe). */
	public static String portalOfHost(String hostname) {
		String host = hostname.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
		Function<String, Boolean> is = domain -> host.equals(domain) || host.endsWith("." + domain);
		if (is.apply("easybroker.com")) return "easybroker";
		if (is.apply("inmuebles24.com")) return "inmuebles24";
		if (is.apply("mercadolibre.com.mx") || is.apply("meli.la")) return "mercado_libre";
		if (is.apply("vivanuncios.com.mx")) return "vivanuncios";
		if (is.apply("lamudi.com.mx")) return "proppit_by_lamudi";
		if (is.apply("propiedades.com")) return "propiedades_com";
		if (is.apply("casasyterrenos.com")) return "casas_y_terrenos";
		if (is.apply("century21mexico.com")) return "century21_mexico";
		if (is.apply("facebook.com") || is.apply("fb.me") || is.apply("fb.com")) return "facebook";
		if (is.apply("clasco.mx")) return "clasco";
		if (is.apply("pincali.com")) return "pincali";
		if (is.apply("valoresampi.mx")) return "valores_ampi";
		return null;
	}

	public static final Map<String, String> PORTAL_LABELS = Map.ofEntries(
			Map.entry("easybroker", "EasyBroker"),
			Map.entry("inmuebles24", "Inmuebles24"),
			Map.entry("mercado_libre", "Mercado Libre"),
			Map.entry("vivanuncios", "Vivanuncios"),
			Map.entry("proppit_by_lamudi", "Lamudi"),
			Map.entry("propiedades_com", "Propiedades.com"),
			Map.entry("casas_y_terrenos", "Casas y Terrenos"),
			Map.entry("century21_mexico", "Century 21 México"),
			Map.entry("facebook", "Facebook"),
			Map.entry("clasco", "Clasco"),
			Map.entry("pincali", "Pincali"),
			Map.entry("valores_ampi", "ValoresAMPI"));

	public static String portalLabel(String portal) {
		return portal == null ? null : PORTAL_LABELS.getOrDefault(portal, portal);
	}

	public record ListingKey(String portal, String externalKey) {
	}

	private static String group(Pattern pattern, String text, int group) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(group) : null;
	}

	/** Clave estable de un anuncio a partir de su URL (y el ID remoto de EasyBroker, si lo hay). */
	public static ListingKey portalListingKeyFromUrl(String rawUrl, String remoteListingId) {
		ParsedUrl url = ParsedUrl.parse(rawUrl);
		if (url == null) return null;
		String portal = portalOfHost(url.host);
		if (portal == null || portal.equals("easybroker")) return null;
		String path = decode(url.path);
		String remote = remoteListingId == null ? null : remoteListingId.trim();
		String key = switch (portal) {
			case "inmuebles24" -> remote != null && remote.matches("^\\d+$") ? remote : group(Pattern.compile("-(\\d{6,})\\.html$"), path, 1);
			case "mercado_libre" -> {
				String id = group(Pattern.compile("MLM-?(\\d{6,})", Pattern.CASE_INSENSITIVE), path + url.search(), 1);
				yield id != null ? "MLM" + id : null;
			}
			case "clasco" -> group(Pattern.compile("_(\\d{5,})/?$"), path, 1);
			case "pincali" -> {
				String id = group(Pattern.compile("/inmueble/([a-z0-9-]+)/?$", Pattern.CASE_INSENSITIVE), path, 1);
				yield id == null ? null : id.toLowerCase(Locale.ROOT);
			}
			case "valores_ampi" -> group(Pattern.compile("external-properties/(\\d+)"), path, 1);
			case "proppit_by_lamudi" -> {
				String id = group(Pattern.compile("/detalle/([a-z0-9-]{6,})", Pattern.CASE_INSENSITIVE), path, 1);
				yield id == null ? null : id.toLowerCase(Locale.ROOT);
			}
			case "facebook" -> group(Pattern.compile("/marketplace/item/(\\d+)"), path, 1);
			case "century21_mexico" -> group(Pattern.compile("/propiedad/(\\d{4,})"), path, 1);
			default -> {
				// Vivanuncios, Propiedades.com, Casas y Terrenos: el último número largo de la ruta.
				Matcher m = Pattern.compile("(\\d{6,})").matcher(path);
				String last = null;
				while (m.find()) last = m.group(1);
				yield last;
			}
		};
		if (key == null && remote != null && !remote.isEmpty()) key = remote;
		return key != null ? new ListingKey(portal, key) : null;
	}

	/** kind: easybroker_code (code) | easybroker_listing (slug) | other (hostname). */
	public record ParsedPropertyUrl(String kind, String code, String slug, String hostname) {
	}

	/** Clasifica una URL sin descargarla. Un código EB- en la ruta o query gana siempre. */
	public static ParsedPropertyUrl parsePropertyUrl(String rawUrl) {
		String canonical = canonicalizeUrl(rawUrl);
		if (canonical == null) return null;
		ParsedUrl url = ParsedUrl.parse(canonical);
		if (url == null) return null;
		String decoded = decode(url.path + " " + url.search());
		List<String> codes = extractEasyBrokerCodes(decoded);
		if (!codes.isEmpty()) return new ParsedPropertyUrl("easybroker_code", codes.get(0), null, null);
		String hostname = url.host.replaceFirst("^www\\.", "");
		if (hostname.equals("easybroker.com") || hostname.endsWith(".easybroker.com")) {
			String slug = group(Pattern.compile("/listings/([^/?#]+)"), url.path, 1);
			if (slug != null) return new ParsedPropertyUrl("easybroker_listing", null, slug.toLowerCase(Locale.ROOT), null);
		}
		return new ParsedPropertyUrl("other", null, null, hostname);
	}

	public static String listingSlugFromPublicUrl(String publicUrl) {
		if (publicUrl == null || publicUrl.isEmpty()) return null;
		ParsedPropertyUrl parsed = parsePropertyUrl(publicUrl);
		return parsed != null && "easybroker_listing".equals(parsed.kind()) ? parsed.slug() : null;
	}

	public static String normalizeSearchText(String text) {
		if (text == null) return "";
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "").toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", " ").trim();
	}

	private static final Set<String> STOPWORDS = Set.of("a", "al", "con", "de", "del", "el", "en", "la", "las", "lo", "los", "mi", "me", "por", "que",
			"se", "un", "una", "y", "o", "quiero", "busco", "vi", "esta", "este", "ese", "esa", "informacion", "info", "hola", "para", "sobre", "precio",
			"interesa", "propiedad", "the", "tiene", "hay");

	private static final Map<String, List<String>> SYNONYMS = Map.of(
			"depa", List.of("departamento"), "depto", List.of("departamento"), "dpto", List.of("departamento"),
			"departamentos", List.of("departamento"), "casas", List.of("casa"), "terrenos", List.of("terreno"), "lote", List.of("terreno"),
			"venta", List.of("venta"), "renta", List.of("renta"), "zapo", List.of("zapopan"));

	public static List<String> searchTokens(String query) {
		Set<String> tokens = new LinkedHashSet<>();
		for (String word : normalizeSearchText(query).split(" ")) {
			if (word.length() < 2 || STOPWORDS.contains(word)) continue;
			tokens.add(word);
			if (word.equals("gdl")) tokens.add("guadalajara");
			for (String synonym : SYNONYMS.getOrDefault(word, List.of())) tokens.add(synonym);
		}
		return new ArrayList<>(tokens);
	}

	public record Scored<T>(T property, double score) {
	}

	/** Puntuación léxica simple sobre el índice local (título pesa el doble). */
	public static <T> List<Scored<T>> scoreProperties(String query, List<T> properties, Function<T, String> titleOf, Function<T, String> searchTextOf,
			int limit) {
		List<String> tokens = searchTokens(query);
		if (tokens.isEmpty()) return List.of();
		List<Scored<T>> scored = new ArrayList<>();
		for (T property : properties) {
			String title = " " + normalizeSearchText(titleOf.apply(property)) + " ";
			String haystack = " " + searchTextOf.apply(property) + " ";
			double score = 0;
			for (String token : tokens) {
				if (title.contains(" " + token + " ")) score += 3;
				else if (title.contains(token)) score += 2;
				else if (haystack.contains(" " + token + " ")) score += 1.5;
				else if (token.length() >= 4 && haystack.contains(token)) score += 0.75;
			}
			double normalized = score / Math.sqrt(tokens.size());
			if (normalized > 0) scored.add(new Scored<>(property, normalized));
		}
		scored.sort(Comparator.comparingDouble((Scored<T> s) -> s.score()).reversed());
		return scored.size() > limit ? scored.subList(0, limit) : scored;
	}
}

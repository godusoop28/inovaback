package com.c21inova.api.property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Service;

import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.conversation.PropertyReference;
import com.c21inova.api.integration.EasyBrokerService;
import com.c21inova.api.integration.OpenAiClient;

/**
 * Búsqueda profunda de propiedades para el flujo de ManyChat. Recorre todo
 * el inventario publicado y nunca confía en un id inventado por la IA: cada
 * opción se verifica contra lo que devolvió EasyBroker.
 */
@Service
public class PropertySearchService {

	private static final int MAX_PAGES = 20;
	private static final int PAGE_SIZE = 50;
	// ManyChat se rinde a los ~10 s: OpenAI debe responder bien dentro de esa ventana.
	private static final long AI_TIMEOUT_MS = 6000;

	public record Option(String id, String titulo, String url) {
	}

	/** resultado: ok | sin_coincidencias | sin_resultados; metodo: ai | keyword. */
	public record Result(String resultado, List<Option> opciones, String metodo, String aiError) {
	}

	private final EasyBrokerService easyBroker;
	private final OpenAiClient openAi;
	private final Env env;

	public PropertySearchService(EasyBrokerService easyBroker, OpenAiClient openAi, Env env) {
		this.easyBroker = easyBroker;
		this.openAi = openAi;
		this.env = env;
	}

	static String candidateText(List<EasyBrokerService.Property> properties) {
		List<String> blocks = new ArrayList<>();
		for (EasyBrokerService.Property p : properties) {
			String price = p.operations() != null && !p.operations().isEmpty() && p.operations().get(0).formattedAmount() != null
					? p.operations().get(0).formattedAmount() : "";
			blocks.add("ID: " + p.publicId() + "\nTítulo: " + p.title() + "\nTipo: " + (p.propertyType() == null ? "" : p.propertyType()) + "\nUbicación: "
					+ (p.location() == null ? "" : p.location()) + "\nPrecio: " + price);
		}
		return String.join("\n\n---\n\n", blocks);
	}

	static String prompt(String query, String candidates) {
		return "Analiza la búsqueda del cliente y la lista de propiedades disponibles.\n\nBúsqueda del cliente:\n" + query + "\n\nLista de propiedades:\n"
				+ candidates + "\n\nDevuelve únicamente un JSON válido con esta forma exacta, sin markdown ni texto adicional:\n"
				+ "{\"resultado\":\"ok\",\"opciones\":[{\"id\":\"...\",\"titulo\":\"...\"}]}\n\nReglas:\n"
				+ "- Máximo 3 opciones.\n"
				+ "- La prioridad máxima es el título de la propiedad; si el cliente escribe el nombre exacto o parcial de una propiedad, prioriza esas coincidencias.\n"
				+ "- Si el cliente escribe una zona, torre, fraccionamiento o referencia, búscala en título y ubicación.\n"
				+ "- Ignora mayúsculas, minúsculas y acentos.\n"
				+ "- Si escribe \"departamento\" o \"casa\", prioriza ese tipo de propiedad.\n"
				+ "- No elijas propiedades sin relación clara con la búsqueda.\n"
				+ "- No repitas el mismo id en más de una opción.\n"
				+ "- Usa ÚNICAMENTE ids que aparezcan en la lista de propiedades de arriba. No inventes ids ni títulos.\n"
				+ "- Si no hay ninguna propiedad relacionada, responde {\"resultado\":\"sin_coincidencias\",\"opciones\":[]}.";
	}

	private static final Set<String> STOPWORDS = Set.of("a", "al", "con", "de", "del", "el", "en", "la", "las", "lo", "los", "mi", "me", "por", "que", "se", "un",
			"una", "y", "quiero", "busco", "vi", "esta", "ese", "esa");

	/** Plan B sin IA: puntúa por palabras en título (doble) y ubicación. */
	static List<Option> keywordMatch(String query, List<EasyBrokerService.Property> candidates) {
		List<String> words = new ArrayList<>();
		for (String word : PropertyReference.normalizeSearchText(query).split(" ")) if (word.length() > 1 && !STOPWORDS.contains(word)) words.add(word);
		if (words.isEmpty()) return List.of();
		record Scored(EasyBrokerService.Property property, int score) {
		}
		List<Scored> scored = new ArrayList<>();
		for (EasyBrokerService.Property candidate : candidates) {
			String title = " " + PropertyReference.normalizeSearchText(candidate.title()) + " ";
			String location = " " + PropertyReference.normalizeSearchText(candidate.location()) + " ";
			int score = 0;
			for (String word : words) {
				if (title.contains(" " + word + " ")) score += 2;
				else if (location.contains(" " + word + " ")) score += 1;
			}
			if (score > 0) scored.add(new Scored(candidate, score));
		}
		return scored.stream().sorted(Comparator.comparingInt(Scored::score).reversed()).limit(3)
				.map(s -> new Option(s.property().publicId(), s.property().title(), s.property().publicUrl())).toList();
	}

	public Result search(String query) {
		EasyBrokerService.Page<EasyBrokerService.Property> first = easyBroker.listPublishedPropertiesPage(1, PAGE_SIZE);
		int totalPages = first.total() != null ? (int) Math.ceil(first.total() / (double) PAGE_SIZE) : first.hasNext() ? MAX_PAGES : 1;
		List<EasyBrokerService.Property> candidates = new ArrayList<>(first.content());
		RuntimeException failedPage = null;
		// Una página fallida no debe vaciar toda la búsqueda.
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<EasyBrokerService.Page<EasyBrokerService.Property>>> futures = new ArrayList<>();
			for (int i = 2; i <= Math.min(totalPages, MAX_PAGES); i++) {
				int number = i;
				futures.add(pool.submit(() -> easyBroker.listPublishedPropertiesPage(number, PAGE_SIZE)));
			}
			for (Future<EasyBrokerService.Page<EasyBrokerService.Property>> future : futures) {
				try {
					candidates.addAll(future.get().content());
				} catch (Exception error) {
					if (failedPage == null) failedPage = new IllegalStateException(error.getCause() != null ? error.getCause() : error);
				}
			}
		}
		Map<String, EasyBrokerService.Property> byId = new HashMap<>();
		for (EasyBrokerService.Property p : candidates) byId.put(p.publicId(), p);
		if (candidates.isEmpty()) {
			if (failedPage != null) throw failedPage;
			return new Result("sin_resultados", List.of(), null, null);
		}

		List<String> aiIds = new ArrayList<>();
		try {
			String raw = openAi.jsonCompletion(env.propertySearchModel(), null, prompt(query, candidateText(candidates)), AI_TIMEOUT_MS);
			Map<String, Object> parsed = Json.asMap(Json.parse(raw));
			if (parsed == null || !(parsed.get("resultado") instanceof String) || !(parsed.get("opciones") instanceof List<?> options) || options.size() > 3) {
				throw new IllegalStateException("Respuesta de OpenAI con forma inválida.");
			}
			for (Map<String, Object> option : Json.objects(parsed.get("opciones"))) {
				String id = Json.str(option, "id");
				if (id == null || id.trim().isEmpty() || !(option.get("titulo") instanceof String)) throw new IllegalStateException("Opción inválida.");
				aiIds.add(id.trim());
			}
		} catch (RuntimeException error) {
			List<Option> options = keywordMatch(query, candidates);
			return new Result(options.isEmpty() ? "sin_coincidencias" : "ok", options, "keyword",
					error.getMessage() != null ? error.getMessage() : "Error desconocido de OpenAI");
		}

		List<String> verified = new LinkedHashSet<>(aiIds).stream().filter(byId::containsKey).limit(3).toList();
		if (verified.isEmpty()) return new Result("sin_coincidencias", List.of(), "ai", null);

		// Detalle de cada opción verificada para título/enlace exactos.
		List<Option> options = new ArrayList<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<EasyBrokerService.Property>> futures = verified.stream().map(id -> pool.submit(() -> {
				try {
					return easyBroker.getProperty(id);
				} catch (RuntimeException error) {
					return null;
				}
			})).toList();
			for (int i = 0; i < verified.size(); i++) {
				EasyBrokerService.Property candidate = byId.get(verified.get(i));
				EasyBrokerService.Property detail = null;
				try {
					detail = futures.get(i).get();
				} catch (Exception ignored) {
					// Se usa el dato del listado.
				}
				options.add(new Option(verified.get(i), detail != null && detail.title() != null ? detail.title() : candidate.title(),
						detail != null && detail.publicUrl() != null ? detail.publicUrl() : candidate.publicUrl()));
			}
		}
		return new Result("ok", options, "ai", null);
	}
}

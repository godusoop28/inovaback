package com.c21inova.api.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Year;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.c21inova.api.common.Json;

/**
 * Traducciones puras (sin red ni base) del detalle de EasyBroker: a la fila
 * del inventario local, al formato RESO de la API Externa de 21Online y al
 * cuerpo para volver a crear una propiedad eliminada en EasyBroker.
 */
public final class InventoryMapping {

	private InventoryMapping() {
	}

	/** Campos planos que el panel muestra sin abrir el JSON completo. */
	public record Summary(String publicId, String title, String propertyType, String status, String operationType, Double price, String currency,
			String location, Double bedrooms, Double bathrooms, Double parkingSpaces, Double constructionSize, Double lotSize, String agentName,
			String agentEmail, String coverImageUrl, int imageCount, String publicUrl, String ebCreatedAt, String ebUpdatedAt) {
	}

	/** Operación principal: venta antes que renta, como la muestra EasyBroker. */
	static Map<String, Object> mainOperation(Map<String, Object> raw) {
		List<Map<String, Object>> operations = Json.objects(raw.get("operations"));
		for (Map<String, Object> op : operations) {
			if ("sale".equals(Json.str(op, "type"))) return op;
		}
		return operations.isEmpty() ? null : operations.get(0);
	}

	static List<Map<String, Object>> images(Map<String, Object> raw) {
		List<Map<String, Object>> images = Json.objects(raw.get("property_images"));
		return images.isEmpty() ? Json.objects(raw.get("images")) : images;
	}

	static String locationName(Map<String, Object> raw) {
		Object location = raw.get("location");
		return location instanceof String s ? s : Json.str(Json.asMap(location), "name");
	}

	public static Summary summarize(Map<String, Object> raw) {
		Map<String, Object> op = mainOperation(raw);
		Map<String, Object> agent = Json.asMap(raw.get("agent"));
		List<Map<String, Object>> images = images(raw);
		String cover = Json.str(raw, "title_image_full");
		if (cover == null) cover = Json.str(raw, "title_image_thumb");
		if (cover == null && !images.isEmpty()) cover = Json.str(images.get(0), "url");
		String title = Json.str(raw, "title");
		return new Summary(Json.str(raw, "public_id"), title == null ? "" : title, Json.str(raw, "property_type"), statusOf(raw),
				op == null ? null : Json.str(op, "type"), op == null ? null : Json.number(op, "amount"), op == null ? null : Json.str(op, "currency"),
				locationName(raw), Json.number(raw, "bedrooms"), Json.number(raw, "bathrooms"), Json.number(raw, "parking_spaces"),
				Json.number(raw, "construction_size"), Json.number(raw, "lot_size"), agent == null ? null : Json.str(agent, "full_name") != null
						? Json.str(agent, "full_name") : Json.str(agent, "name"),
				agent == null ? null : Json.str(agent, "email"), cover, images.size(), Json.str(raw, "public_url"), Json.str(raw, "created_at"),
				Json.str(raw, "updated_at"));
	}

	/** El listado no siempre trae status; sin dato, una propiedad listada se considera publicada. */
	static String statusOf(Map<String, Object> raw) {
		String status = Json.str(raw, "status");
		return status == null || status.isBlank() ? "published" : status;
	}

	// --- 21Online (RESO) ---------------------------------------------------------

	/** Estado RESO a partir del estado de EasyBroker; una propiedad eliminada en EasyBroker se retira. */
	public static String resoStatus(String ebStatus, boolean deleted) {
		if (deleted) return "Withdrawn";
		if (ebStatus == null) return "Active";
		return switch (ebStatus) {
			case "published" -> "Active";
			case "reserved" -> "Pending";
			case "sold", "rented" -> "Closed";
			default -> "Withdrawn"; // not_published, suspended
		};
	}

	static String fold(String text) {
		if (text == null) return "";
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
	}

	/** [PropertyType, PropertySubType] RESO para los tipos de EasyBroker. */
	static String[] resoType(String ebType) {
		String type = fold(ebType);
		if (type.startsWith("departamento") || type.contains("loft") || type.contains("penthouse")) return new String[] { "Residential", "Apartment" };
		if (type.contains("condominio")) return new String[] { "Residential", "Townhouse" };
		if (type.startsWith("casa") || type.startsWith("villa") || type.contains("duplex")) return new String[] { "Residential", "SingleFamilyResidence" };
		if (type.startsWith("terreno") || type.startsWith("lote")) {
			if (type.contains("comercial") || type.contains("industrial")) return new String[] { "Land", "Commercial" };
			return new String[] { "Land", "UnimprovedLand" };
		}
		if (type.startsWith("oficina") || type.contains("consultorio")) return new String[] { "Commercial", "Office" };
		if (type.startsWith("local")) return new String[] { "Commercial", "Retail" };
		if (type.startsWith("bodega")) return new String[] { "Commercial", "Warehouse" };
		if (type.startsWith("nave") || type.contains("industrial")) return new String[] { "Commercial", "Industrial" };
		if (type.startsWith("edificio")) return new String[] { "Commercial", "MixedUse" };
		if (type.startsWith("hotel")) return new String[] { "Commercial", "Hotel" };
		if (type.startsWith("rancho") || type.startsWith("quinta") || type.startsWith("huerta") || type.startsWith("granja"))
			return new String[] { "Farm", "Ranch" };
		return new String[] { "Residential", null };
	}

	private static Integer intOrNull(Double value) {
		return value == null ? null : (int) Math.round(value);
	}

	/**
	 * Cuerpo para POST /api/v1/external/Property (upsert por ExternalId =
	 * código de EasyBroker) o PATCH por ListingKey. Solo datos públicos: nunca
	 * comisiones, descripción privada ni notas.
	 */
	public static Map<String, Object> toReso(Map<String, Object> raw, boolean deleted) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ExternalId", Json.str(raw, "public_id"));
		out.put("StandardStatus", resoStatus(statusOf(raw), deleted));
		String[] type = resoType(Json.str(raw, "property_type"));
		out.put("PropertyType", type[0]);
		if (type[1] != null) out.put("PropertySubType", type[1]);
		Map<String, Object> op = mainOperation(raw);
		if (op != null) {
			out.put("OperationType", "rental".equals(Json.str(op, "type")) || "temporary_rental".equals(Json.str(op, "type")) ? "rental" : "sale");
			boolean hidePrices = Boolean.FALSE.equals(Json.bool(raw, "show_prices"));
			if (!hidePrices && Json.number(op, "amount") != null) {
				out.put("ListPrice", Json.number(op, "amount"));
				out.put("ListPriceCurrency", Json.str(op, "currency") != null ? Json.str(op, "currency") : "MXN");
			}
		}
		out.put("BedroomsTotal", intOrNull(Json.number(raw, "bedrooms")));
		out.put("BathroomsFull", intOrNull(Json.number(raw, "bathrooms")));
		out.put("BathroomsHalf", intOrNull(Json.number(raw, "half_bathrooms")));
		Double construction = Json.number(raw, "construction_size");
		if (construction != null && construction > 0) {
			out.put("LivingArea", construction);
			out.put("LivingAreaUnits", "Square Meters");
		}
		Double lot = Json.number(raw, "lot_size");
		if (lot != null && lot > 0) {
			out.put("LotSizeArea", lot);
			out.put("LotSizeUnits", "Square Meters");
		}
		Integer age = Json.integer(raw, "age");
		if (age != null && age >= 0 && age < 200) out.put("YearBuilt", Year.now().getValue() - age);

		Map<String, Object> location = Json.asMap(raw.get("location"));
		String name = locationName(raw);
		if (name != null) {
			String[] parts = name.split("\\s*,\\s*");
			if (parts.length >= 3) out.put("SubdivisionName", parts[0]);
			if (parts.length >= 2) out.put("City", parts[parts.length - 2]);
			out.put("StateOrProvince", parts[parts.length - 1]);
		}
		boolean hideAddress = location != null && (Boolean.TRUE.equals(Json.bool(location, "hide_exact_location"))
				|| Boolean.FALSE.equals(Json.bool(location, "show_exact_location")));
		if (location != null) {
			if (Json.str(location, "postal_code") != null) out.put("PostalCode", Json.str(location, "postal_code"));
			String street = Json.str(location, "street");
			String number = Json.str(location, "exterior_number");
			String address = hideAddress || street == null ? name : (street + (number != null ? " " + number : "") + (name != null ? ", " + name : ""));
			if (address != null) out.put("UnparsedAddress", address);
			if (!hideAddress) {
				out.put("Latitude", Json.number(location, "latitude"));
				out.put("Longitude", Json.number(location, "longitude"));
			}
		}
		out.put("Country", "MX");
		Double maintenance = Json.number(raw, "maintenance_amount");
		if (maintenance != null && maintenance > 0) {
			out.put("AssociationFee", maintenance);
			out.put("AssociationFeeFrequency", "Monthly");
			out.put("AssociationFeeCurrency", Json.str(raw, "maintenance_currency") != null ? Json.str(raw, "maintenance_currency") : "MXN");
		}
		String description = Json.str(raw, "description");
		if (description != null) out.put("PublicRemarks", description);
		String publishedAt = Json.str(raw, "published_at");
		if (publishedAt != null && publishedAt.length() >= 10) out.put("OnMarketDate", publishedAt.substring(0, 10));
		if (Json.str(raw, "updated_at") != null) out.put("ModificationTimestamp", Json.str(raw, "updated_at"));
		Map<String, Object> agent = Json.asMap(raw.get("agent"));
		if (agent != null) {
			if (Json.str(agent, "email") != null) out.put("ListAgentEmail", Json.str(agent, "email"));
			String agentName = Json.str(agent, "full_name") != null ? Json.str(agent, "full_name") : Json.str(agent, "name");
			if (agentName != null) out.put("ListAgentFullName", agentName);
		}
		List<Map<String, Object>> media = new ArrayList<>();
		List<Map<String, Object>> images = images(raw);
		for (int i = 0; i < images.size() && i < 50; i++) {
			String url = Json.str(images.get(i), "url");
			if (url != null) media.add(Json.obj("MediaURL", url, "Order", i, "MediaCategory", "Photo"));
		}
		out.put("Media", media);
		out.values().removeIf(value -> value == null);
		return out;
	}

	/** Huella del contenido enviado a 21Online: si no cambia, no se vuelve a enviar. */
	public static String hash(Map<String, Object> payload) {
		Map<String, Object> copy = new LinkedHashMap<>(payload);
		copy.remove("ModificationTimestamp");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(Json.write(copy).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Título normalizado para emparejar con propiedades que ya existían en 21Online. */
	public static String matchKey(String title) {
		return fold(title).replaceAll("[^a-z0-9]+", " ").trim();
	}

	// --- Restaurar en EasyBroker ---------------------------------------------------

	/**
	 * Cuerpo para POST /properties de EasyBroker a partir de la copia guardada.
	 * withImages=false sirve para reintentar si EasyBroker rechaza las imágenes.
	 */
	public static Map<String, Object> toEasyBrokerCreate(Map<String, Object> raw, boolean withImages) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("title", Json.str(raw, "title"));
		out.put("description", Json.str(raw, "description") != null ? Json.str(raw, "description") : Json.str(raw, "title"));
		out.put("property_type", Json.str(raw, "property_type"));
		String status = statusOf(raw);
		out.put("status", status);
		Map<String, Object> location = Json.asMap(raw.get("location"));
		Map<String, Object> loc = new LinkedHashMap<>();
		loc.put("name", locationName(raw));
		if (location != null) {
			for (String key : List.of("latitude", "longitude", "street", "postal_code", "exterior_number", "interior_number", "show_exact_location")) {
				if (location.get(key) != null) loc.put(key, location.get(key));
			}
		}
		out.put("location", loc);
		List<Map<String, Object>> operations = new ArrayList<>();
		for (Map<String, Object> op : Json.objects(raw.get("operations"))) {
			Map<String, Object> copy = new LinkedHashMap<>();
			copy.put("type", Json.str(op, "type"));
			copy.put("amount", Json.number(op, "amount"));
			copy.put("currency", Json.str(op, "currency") != null ? Json.str(op, "currency") : "MXN");
			copy.put("active", true);
			if (op.get("commission") != null) copy.put("commission", op.get("commission"));
			copy.values().removeIf(value -> value == null);
			operations.add(copy);
		}
		out.put("operations", operations);
		for (String key : List.of("bedrooms", "bathrooms", "half_bathrooms", "parking_spaces", "lot_size", "construction_size", "lot_length",
				"lot_width", "floors", "floor", "age", "internal_id", "expenses", "maintenance_amount", "maintenance_currency", "show_prices",
				"exclusive", "share_commission", "shared_commission_percentage", "private_description", "collaboration_notes", "virtual_tour")) {
			if (raw.get(key) != null) out.put(key, raw.get(key));
		}
		List<String> features = new ArrayList<>();
		for (Map<String, Object> feature : Json.objects(raw.get("features"))) {
			if (Json.str(feature, "name") != null) features.add(Json.str(feature, "name"));
		}
		if (!features.isEmpty()) out.put("features", features);
		if (withImages) {
			List<Map<String, Object>> images = new ArrayList<>();
			for (Map<String, Object> image : images(raw)) {
				if (Json.str(image, "url") != null) images.add(Json.obj("url", Json.str(image, "url")));
				if (images.size() == 50) break;
			}
			if (!images.isEmpty()) out.put("images", images);
		}
		out.values().removeIf(value -> value == null);
		return out;
	}
}

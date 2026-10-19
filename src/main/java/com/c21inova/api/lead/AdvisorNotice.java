package com.c21inova.api.lead;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.c21inova.api.common.MexicoTime;

/**
 * Texto del aviso de nuevo lead que recibe el asesor por WhatsApp (campo
 * relatedInfo del flujo de ManyChat): ficha de la propiedad con su enlace de
 * EasyBroker y lo que busca el cliente, sin datos privados.
 */
public final class AdvisorNotice {

	/** Tope por defecto; el aviso real usa budget(): el límite es del cuerpo completo de la plantilla. */
	public static final int ADVISOR_NOTICE_MAX = 1000;

	/** Campos del flujo de ManyChat "Aviso asesor nuevo lead" (cuenta actual de Century 21 Inova). */
	public static final int FIELD_NAME = 14780313;
	public static final int FIELD_PHONE = 14780314;
	public static final int FIELD_REQUEST_TYPE = 14780316;
	public static final int FIELD_RELATED_INFO = 14780317;
	public static final int FIELD_REFERENCE = 14780318;
	public static final int FIELD_CONTACT_URL = 14780319;

	/**
	 * WhatsApp rechaza una plantilla cuyo cuerpo, con variables sustituidas,
	 * pasa de 1024 caracteres; se reserva margen para el texto fijo del flujo.
	 */
	public static final int TEMPLATE_BODY_LIMIT = 1024;
	public static final int TEMPLATE_STATIC_RESERVE = 300;
	private static final int MIN_RELATED_INFO = 250;

	private AdvisorNotice() {
	}

	public record Field(int fieldId, String value) {
	}

	public record Operation(String type, String formattedAmount, Double amount) {
	}

	/** Ficha pública de una propiedad (de EasyBroker o del índice local). */
	public record NoticeProperty(
			String publicId,
			String title,
			String propertyType,
			String location,
			String publicUrl,
			List<Operation> operations,
			Double bedrooms,
			Double bathrooms,
			Double halfBathrooms,
			Double parkingSpaces,
			Double constructionSize,
			Double lotSize,
			List<String> features,
			String description,
			Boolean showPrices) {
	}

	public record OtherProperty(String publicId, String title, String url) {
	}

	public record ClientContext(String reason, String operation, String zone, String budget, List<String> additionalNeeds, String summary,
			List<OtherProperty> otherProperties) {
	}

	public static int budget(List<String> otherValues) {
		int used = otherValues.stream().mapToInt(value -> value == null ? 0 : value.length()).sum();
		return Math.max(MIN_RELATED_INFO, TEMPLATE_BODY_LIMIT - TEMPLATE_STATIC_RESERVE - used);
	}

	/** Recorta un aviso ya armado: primero quita la descripción, luego recorta el final. */
	public static String shrink(String text, int max) {
		if (text.length() <= max) return text;
		String withoutDescription = java.util.Arrays.stream(text.split("\n", -1)).filter(line -> !line.startsWith("Descripción: "))
				.collect(Collectors.joining("\n"));
		return withoutDescription.length() <= max ? withoutDescription : truncate(withoutDescription, max);
	}

	/** Ajusta relatedInfo al presupuesto de la plantilla (también para trabajos viejos de la cola). */
	public static List<Field> fitFields(List<Field> fields) {
		Field related = fields.stream().filter(field -> field.fieldId() == FIELD_RELATED_INFO).findFirst().orElse(null);
		if (related == null) return fields;
		int budget = budget(fields.stream().filter(field -> field != related).map(Field::value).toList());
		if (related.value().length() <= budget) return fields;
		return fields.stream().map(field -> field == related ? new Field(field.fieldId(), shrink(field.value(), budget)) : field).toList();
	}

	private static final Map<String, String> OPERATION_LABELS = Map.of("sale", "Venta", "rental", "Renta", "temporary_rental", "Renta temporal");

	static String formatNumber(double value) {
		if (value == Math.rint(value) && !Double.isInfinite(value)) return String.valueOf((long) value);
		DecimalFormat format = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(MexicoTime.ES_MX));
		return format.format(value);
	}

	static String cleanText(String text) {
		return text.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
	}

	static String truncate(String text, int max) {
		if (text.length() <= max) return text;
		if (max <= 1) return "";
		String cut = text.substring(0, max - 1);
		int lastSpace = cut.lastIndexOf(' ');
		return (lastSpace > max * 0.6 ? cut.substring(0, lastSpace) : cut).stripTrailing() + "…";
	}

	public static String operationsLine(NoticeProperty property) {
		List<Operation> operations = (property.operations() == null ? List.<Operation>of() : property.operations()).stream()
				.filter(op -> op.type() != null || op.formattedAmount() != null)
				.toList();
		if (operations.isEmpty()) return null;
		boolean hidePrices = Boolean.FALSE.equals(property.showPrices());
		return operations.stream().map(op -> {
			String label = OPERATION_LABELS.getOrDefault(op.type() == null ? "" : op.type(), op.type() != null ? op.type() : "Operación");
			return hidePrices || op.formattedAmount() == null || op.formattedAmount().isEmpty() ? label + " (precio a consultar)"
					: label + " " + op.formattedAmount();
		}).collect(Collectors.joining(" · "));
	}

	public static List<String> propertySheetLines(NoticeProperty property) {
		String baths = property.bathrooms() != null
				? formatNumber(property.bathrooms())
						+ (property.halfBathrooms() != null && property.halfBathrooms() != 0 ? " y " + formatNumber(property.halfBathrooms()) + " medio(s)" : "")
						+ " baño(s)"
				: null;
		List<String> rooms = new ArrayList<>();
		if (property.bedrooms() != null) rooms.add(formatNumber(property.bedrooms()) + " recámara(s)");
		if (baths != null) rooms.add(baths);
		if (property.parkingSpaces() != null) rooms.add(formatNumber(property.parkingSpaces()) + " estacionamiento(s)");
		List<String> sizes = new ArrayList<>();
		if (property.constructionSize() != null && property.constructionSize() != 0) sizes.add("Construcción " + formatNumber(property.constructionSize()) + " m²");
		if (property.lotSize() != null && property.lotSize() != 0) sizes.add("Terreno " + formatNumber(property.lotSize()) + " m²");
		List<String> features = (property.features() == null ? List.<String>of() : property.features()).stream()
				.filter(Objects::nonNull).map(String::trim).filter(name -> !name.isEmpty()).limit(12).toList();
		String operations = operationsLine(property);

		List<String> lines = new ArrayList<>();
		lines.add("*🏠 PROPIEDAD DE INTERÉS*");
		if (notEmpty(property.title())) lines.add("*" + cleanText(property.title()) + "*");
		lines.add("Código: " + property.publicId());
		if (notEmpty(property.propertyType())) lines.add("Tipo: " + property.propertyType());
		if (operations != null) lines.add("Precio: " + operations);
		if (notEmpty(property.location())) lines.add("Ubicación: " + property.location());
		if (!rooms.isEmpty()) lines.add(String.join(" · ", rooms));
		if (!sizes.isEmpty()) lines.add(String.join(" · ", sizes));
		if (!features.isEmpty()) lines.add("Amenidades: " + String.join(", ", features));
		return lines;
	}

	private static boolean notEmpty(String value) {
		return value != null && !value.isEmpty();
	}

	private static List<String> clientLines(ClientContext context) {
		List<String> lines = new ArrayList<>();
		if (notEmpty(context.reason())) lines.add("Motivo: " + context.reason());
		if (notEmpty(context.operation())) lines.add("Busca: " + context.operation());
		if (notEmpty(context.zone())) lines.add("Zona: " + context.zone());
		if (notEmpty(context.budget())) lines.add("Presupuesto: " + context.budget());
		if (context.additionalNeeds() != null && !context.additionalNeeds().isEmpty()) lines.add("También le interesa: " + String.join(", ", context.additionalNeeds()));
		if (notEmpty(context.summary())) lines.add("Resumen: " + cleanText(context.summary()));
		List<String> others = (context.otherProperties() == null ? List.<OtherProperty>of() : context.otherProperties()).stream().limit(4)
				.map(p -> "• " + p.publicId() + (notEmpty(p.title()) ? " " + cleanText(p.title()) : "") + (notEmpty(p.url()) ? "\n  " + p.url() : ""))
				.toList();
		if (!others.isEmpty()) {
			lines.add("Otras propiedades que mencionó:");
			lines.addAll(others);
		}
		if (lines.isEmpty()) return List.of();
		List<String> out = new ArrayList<>();
		out.add("*👤 LO QUE BUSCA EL CLIENTE*");
		out.addAll(lines);
		return out;
	}

	/**
	 * Texto completo. Si no cabe: ficha y enlace siempre; luego lo que busca el
	 * cliente; la descripción se recorta con el espacio que sobre.
	 */
	public static String build(NoticeProperty property, String propertyData, ClientContext client, Integer maxOrNull) {
		int max = maxOrNull != null ? maxOrNull : ADVISOR_NOTICE_MAX;
		List<String> blocks = new ArrayList<>();

		String sheet = property != null ? String.join("\n", propertySheetLines(property)) : null;
		String link = property != null && notEmpty(property.publicUrl()) ? "🔗 Ficha en EasyBroker:\n" + property.publicUrl()
				: property != null ? "Ficha en EasyBroker: " + property.publicId() + " (sin enlace público)" : null;
		String clientText = client != null ? String.join("\n", clientLines(client)) : "";
		String fallback = property == null && propertyData != null && !propertyData.trim().isEmpty()
				? "Referencia que envió el cliente: " + cleanText(propertyData) : null;

		List<String> fixed = new ArrayList<>();
		for (String block : new String[] { sheet, link, clientText.isEmpty() ? null : clientText, fallback }) if (notEmpty(block)) fixed.add(block);
		int fixedLength = String.join("\n\n", fixed).length();

		String description = property != null && notEmpty(property.description()) ? cleanText(property.description()) : "";
		int room = max - fixedLength - "\n\nDescripción: ".length() - 2;
		String descriptionBlock = !description.isEmpty() && room > 80 ? "Descripción: " + truncate(description, room) : null;

		if (sheet != null) blocks.add(descriptionBlock != null ? sheet + "\n" + descriptionBlock : sheet);
		if (link != null) blocks.add(link);
		if (!clientText.isEmpty()) blocks.add(clientText);
		if (fallback != null) blocks.add(fallback);
		if (blocks.isEmpty()) blocks.add("Sin información adicional");

		String text = String.join("\n\n", blocks);
		return text.length() <= max ? text : truncate(text, max);
	}
}

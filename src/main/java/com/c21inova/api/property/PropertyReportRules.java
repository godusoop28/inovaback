package com.c21inova.api.property;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.c21inova.api.common.Brand;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.MexicoTime;
import com.c21inova.api.conversation.PropertyReference;

/**
 * Reportes por propiedad: periodos, conteos y textos. Puro. Los conteos
 * siempre se calculan aquí (la IA no interviene en los números).
 * Lead único por propiedad y periodo = contactos distintos; el acumulado
 * cuenta contactos distintos en todo el histórico hasta el fin del periodo.
 */
public final class PropertyReportRules {

	private PropertyReportRules() {
	}

	public static final String NOT_IDENTIFIED = "No identificado";

	// --- Periodo semanal (viernes a jueves, CDMX) ---------------------------------------

	/** start inclusivo; endExclusive = 00:00 del día de envío. */
	public record WeeklyPeriod(Instant start, Instant endExclusive, String startKey, String endKey, String label) {
	}

	private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE, d 'de' MMM 'de' yyyy", MexicoTime.ES_MX);

	public static String formatDayKey(String key) {
		return SHORT_DATE.format(LocalDate.parse(key)).replace(".", "");
	}

	public static String formatDayKeyNumeric(String key) {
		String[] parts = key.split("-");
		return parts[2] + "/" + parts[1] + "/" + parts[0];
	}

	public static WeeklyPeriod weeklyPeriodFor(Instant reference, int weekday) {
		LocalDate today = MexicoTime.date(reference);
		int back = (MexicoTime.isoWeekday(today) - weekday + 7) % 7;
		LocalDate send = today.minusDays(back);
		LocalDate start = send.minusDays(7);
		LocalDate end = send.minusDays(1);
		return new WeeklyPeriod(MexicoTime.startOfDay(start), MexicoTime.startOfDay(send), start.toString(), end.toString(),
				formatDayKey(start.toString()) + " al " + formatDayKey(end.toString()));
	}

	/** ¿Ya toca enviar el reporte de esta semana? (día y hora locales configurados). */
	public static boolean isWeeklyDue(Instant now, int weekday, Integer hour, int minute) {
		if (hour == null) return false;
		LocalDate today = MexicoTime.date(now);
		if (MexicoTime.isoWeekday(today) != weekday) return false;
		return !now.isBefore(MexicoTime.wallTimeToUtc(today.getYear(), today.getMonthValue(), today.getDayOfMonth(), hour, minute));
	}

	// --- Conteos --------------------------------------------------------------------------------

	public record InquiryRow(String publicId, String contactKey, String day, int messageCount, String channel, String linkPortal, String acquisitionSource,
			String declaredSource, boolean isTest, Instant lastAt) {
	}

	public record SourceCount(String label, int contacts) {
	}

	public record Metrics(String publicId, int periodLeads, int cumulativeLeads, int periodInquiries, int periodMessages, Instant lastInquiryAt,
			List<SourceCount> channels, List<SourceCount> linkPortals, List<SourceCount> acquisition, List<SourceCount> declared) {

		public Map<String, Object> toJson() {
			return Json.obj("publicId", publicId, "periodLeads", periodLeads, "cumulativeLeads", cumulativeLeads, "periodInquiries", periodInquiries,
					"periodMessages", periodMessages, "lastInquiryAt", lastInquiryAt, "channels", channels, "linkPortals", linkPortals, "acquisition",
					acquisition, "declared", declared);
		}
	}

	/** Un contacto sin dato en ninguna fila cuenta como "No identificado"; con dato, en ese valor. */
	static List<SourceCount> countDistinct(List<InquiryRow> rows, Function<InquiryRow, String> labelOf) {
		Map<String, Set<String>> byContact = new LinkedHashMap<>();
		for (InquiryRow row : rows) {
			Set<String> labels = byContact.computeIfAbsent(row.contactKey(), k -> new LinkedHashSet<>());
			String label = labelOf.apply(row);
			if (label != null && !label.isEmpty()) labels.add(label);
		}
		Map<String, Set<String>> byLabel = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : byContact.entrySet()) {
			Set<String> labels = entry.getValue().isEmpty() ? Set.of(NOT_IDENTIFIED) : entry.getValue();
			for (String label : labels) byLabel.computeIfAbsent(label, k -> new HashSet<>()).add(entry.getKey());
		}
		List<SourceCount> out = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : byLabel.entrySet()) out.add(new SourceCount(entry.getKey(), entry.getValue().size()));
		out.sort((a, b) -> a.label().equals(NOT_IDENTIFIED) ? 1 : b.label().equals(NOT_IDENTIFIED) ? -1 : b.contacts() - a.contacts());
		return out;
	}

	public static String channelLabel(String channel) {
		return "whatsapp".equals(channel) ? "WhatsApp" : channel;
	}

	/** Métricas por propiedad para [startKey, endKey] (días inclusivos), sin pruebas. */
	public static List<Metrics> aggregate(List<InquiryRow> rows, String startKey, String endKey) {
		Map<String, List<InquiryRow>> byProperty = new LinkedHashMap<>();
		for (InquiryRow row : rows) {
			if (row.isTest() || row.day().compareTo(endKey) > 0) continue;
			byProperty.computeIfAbsent(row.publicId(), k -> new ArrayList<>()).add(row);
		}
		List<Metrics> metrics = new ArrayList<>();
		for (Map.Entry<String, List<InquiryRow>> entry : byProperty.entrySet()) {
			List<InquiryRow> all = entry.getValue();
			List<InquiryRow> inPeriod = all.stream().filter(r -> r.day().compareTo(startKey) >= 0).toList();
			Instant last = all.stream().map(InquiryRow::lastAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
			metrics.add(new Metrics(entry.getKey(), (int) inPeriod.stream().map(InquiryRow::contactKey).distinct().count(),
					(int) all.stream().map(InquiryRow::contactKey).distinct().count(), inPeriod.size(),
					inPeriod.stream().mapToInt(InquiryRow::messageCount).sum(), last, countDistinct(inPeriod, r -> channelLabel(r.channel())),
					countDistinct(inPeriod, r -> PropertyReference.portalLabel(r.linkPortal())), countDistinct(inPeriod, InquiryRow::acquisitionSource),
					countDistinct(inPeriod, InquiryRow::declaredSource)));
		}
		metrics.sort(Comparator.comparingInt(Metrics::periodLeads).reversed().thenComparing(Comparator.comparingInt(Metrics::cumulativeLeads).reversed())
				.thenComparing(Metrics::publicId));
		return metrics;
	}

	// --- Actividades ----------------------------------------------------------------------------

	public static final Map<String, String> EVENT_TYPE_LABELS = Map.of("SHOWING", "Visita", "OPEN_HOUSE", "Open House", "APPOINTMENT", "Cita", "OFFER",
			"Oferta recibida", "PRICE_UPDATE", "Actualización de precio", "MARKETING", "Acción de promoción", "OTHER", "Actividad");

	public static final Map<String, String> EVENT_STATUS_LABELS = Map.of("SCHEDULED", "Programada", "DONE", "Realizada", "CANCELLED", "Cancelada");

	public record EventView(String type, String status, String title, String description, Instant scheduledAt, Instant completedAt, String outcome, int version) {
	}

	private static final DateTimeFormatter EVENT_DATE = DateTimeFormatter.ofPattern("EEE d 'de' MMM, HH:mm", MexicoTime.ES_MX);

	public static String formatEventDate(Instant date) {
		return date == null ? "fecha por confirmar" : EVENT_DATE.format(date.atZone(MexicoTime.ZONE)).replace(".", "");
	}

	static String oneLine(String text, int max) {
		String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
		return value.length() > max ? value.substring(0, max) : value;
	}

	/** Programada ≠ realizada; lo realizado usa solo lo registrado. */
	public static String describeEvent(EventView event) {
		String type = EVENT_TYPE_LABELS.get(event.type());
		if ("DONE".equals(event.status())) {
			String when = formatEventDate(event.completedAt() != null ? event.completedAt() : event.scheduledAt());
			return type + " realizada (" + when + ")" + (event.outcome() != null && !event.outcome().isEmpty() ? ": " + oneLine(event.outcome(), 160) : "");
		}
		if ("CANCELLED".equals(event.status())) return type + " cancelada (estaba para " + formatEventDate(event.scheduledAt()) + ")";
		return type + " programada para " + formatEventDate(event.scheduledAt());
	}

	// --- Textos para el propietario (sin datos de prospectos) ---------------------------------------

	public record WeeklyReport(Map<String, String> variables, String text, int periodLeads, int cumulativeLeads) {
	}

	static String sourcesText(Metrics metrics) {
		if (metrics == null || metrics.periodLeads() == 0) return "Sin personas interesadas en el periodo";
		List<String> parts = new ArrayList<>();
		List<SourceCount> declared = metrics.declared().stream().filter(s -> !s.label().equals(NOT_IDENTIFIED)).toList();
		List<SourceCount> portals = metrics.linkPortals().stream().filter(s -> !s.label().equals(NOT_IDENTIFIED)).toList();
		List<SourceCount> acquisition = metrics.acquisition().stream().filter(s -> !s.label().equals(NOT_IDENTIFIED)).toList();
		Function<List<SourceCount>, String> join = list -> String.join(", ", list.stream().map(s -> s.label() + " (" + s.contacts() + ")").toList());
		if (!acquisition.isEmpty()) parts.add("campaña: " + join.apply(acquisition));
		if (!declared.isEmpty()) parts.add("dicho por el interesado: " + join.apply(declared));
		if (!portals.isEmpty()) parts.add("enlace compartido de " + join.apply(portals));
		return !parts.isEmpty() ? "Contacto por WhatsApp; " + String.join("; ", parts) : "Contacto por WhatsApp; origen " + NOT_IDENTIFIED.toLowerCase();
	}

	public static WeeklyReport buildWeekly(String publicId, String title, WeeklyPeriod period, Metrics metrics, String dataSinceKey, List<EventView> events) {
		int periodLeads = metrics != null ? metrics.periodLeads() : 0;
		int cumulativeLeads = metrics != null ? metrics.cumulativeLeads() : 0;
		String property = oneLine((title != null ? title : "Propiedad") + " (" + publicId + ")", 160);
		String weekLeads = periodLeads == 0 ? "Esta semana no se registraron personas interesadas"
				: periodLeads + " " + (periodLeads == 1 ? "persona interesada" : "personas interesadas");
		String cumulative = cumulativeLeads + " " + (cumulativeLeads == 1 ? "persona" : "personas")
				+ (dataSinceKey != null ? " desde el " + formatDayKeyNumeric(dataSinceKey) : "");
		String activity = !events.isEmpty() ? oneLine(String.join("; ", events.stream().map(PropertyReportRules::describeEvent).toList()), 600)
				: "Sin actividades registradas en el periodo";
		Map<String, String> variables = new LinkedHashMap<>();
		variables.put("property", property);
		variables.put("period", period.label());
		variables.put("weekLeads", weekLeads);
		variables.put("cumulative", cumulative);
		variables.put("sources", oneLine(sourcesText(metrics), 400));
		variables.put("activity", activity);
		String text = String.join("\n", "*" + Brand.OWNER_REPORT_BRAND + " | " + property + "*", "Periodo: " + period.label(), "Interesados en la semana: " + weekLeads,
				"Acumulado: " + cumulative, "Procedencia: " + variables.get("sources"), "Actividad: " + activity, Brand.BRAND_NAME);
		return new WeeklyReport(variables, text, periodLeads, cumulativeLeads);
	}

	public record EventNotice(Map<String, String> variables, String text) {
	}

	/** Programada: fecha y descripción. Realizada: solo lo registrado. Reprogramada/cancelada: la actualización. */
	public static EventNotice buildEventNotice(String publicId, String title, EventView event) {
		String type = EVENT_TYPE_LABELS.get(event.type());
		String property = oneLine((title != null ? title : "Propiedad") + " (" + publicId + ")", 160);
		String headline;
		String detail;
		if ("DONE".equals(event.status())) {
			headline = type + " realizada el " + formatEventDate(event.completedAt() != null ? event.completedAt() : event.scheduledAt());
			detail = event.outcome() != null && !event.outcome().isEmpty() ? "Registro del equipo: " + oneLine(event.outcome(), 500)
					: "El equipo registró la actividad como realizada.";
		} else if ("CANCELLED".equals(event.status())) {
			headline = type + " cancelada";
			detail = "Estaba programada para " + formatEventDate(event.scheduledAt()) + "."
					+ (event.description() != null && !event.description().isEmpty() ? " " + oneLine(event.description(), 300) : "");
		} else {
			headline = type + " " + (event.version() > 1 ? "reprogramada" : "programada") + " para " + formatEventDate(event.scheduledAt());
			detail = oneLine(event.description() != null && !event.description().isEmpty() ? event.description() : event.title(), 500);
		}
		List<String> lines = new ArrayList<>(List.of("*" + Brand.OWNER_REPORT_BRAND + " | " + property + "*", headline));
		if (!detail.isEmpty()) lines.add(detail);
		lines.add(Brand.BRAND_NAME);
		Map<String, String> variables = new LinkedHashMap<>();
		variables.put("property", property);
		variables.put("headline", oneLine(headline, 300));
		variables.put("detail", oneLine(detail, 600));
		return new EventNotice(variables, String.join("\n", lines));
	}

	/** Nunca sale un mensaje sin destinatario verificado y autorización. purpose: weekly | event | test. */
	public static List<String> recipientBlockers(boolean active, String consentStatus, Instant verifiedAt, String subscriberId, boolean weeklyReport,
			boolean eventNotifications, String purpose) {
		List<String> blockers = new ArrayList<>();
		if (!active) blockers.add("destinatario desactivado");
		if (!"GRANTED".equals(consentStatus)) blockers.add("sin autorización registrada");
		if (verifiedAt == null) blockers.add("sin verificar en ManyChat");
		if (subscriberId == null || subscriberId.isEmpty()) blockers.add("sin contacto de ManyChat");
		if ("weekly".equals(purpose) && !weeklyReport) blockers.add("reporte semanal no habilitado");
		if ("event".equals(purpose) && !eventNotifications) blockers.add("avisos de actividad no habilitados");
		return blockers;
	}

	/** Plantillas de WhatsApp para propietarios (deben aprobarse en Meta vía ManyChat). */
	public static final List<Map<String, Object>> OWNER_TEMPLATES = List.of(
			Json.obj("name", "pulso_inova_semanal", "purpose", "reporte semanal (viernes)", "body",
					String.join("\n", "*PULSO INOVA | {{1}}*", "Periodo: {{2}}", "Interesados en la semana: {{3}}", "Acumulado: {{4}}", "Procedencia: {{5}}",
							"Actividad: {{6}}", "Century 21 Inova"),
					"fields", List.of("reporte_propiedad", "reporte_periodo", "reporte_interesados_semana", "reporte_acumulado", "reporte_procedencia",
							"reporte_actividad")),
			Json.obj("name", "pulso_inova_actividad", "purpose", "aviso de actividad (programada, realizada, reprogramada o cancelada)", "body",
					String.join("\n", "*PULSO INOVA | {{1}}*", "{{2}}", "{{3}}", "Century 21 Inova"), "fields",
					List.of("evento_propiedad", "evento_encabezado", "evento_detalle")));
}

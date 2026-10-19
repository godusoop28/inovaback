package com.c21inova.api.property;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.MexicoTime;
import com.c21inova.api.common.Phone;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.ManyChatErrors.ManyChatApiException;
import com.c21inova.api.integration.ManyChatErrors.ManyChatTimeoutException;
import com.c21inova.api.integration.ManyChatService;
import com.c21inova.api.lead.AdvisorNotice;
import com.c21inova.api.property.PropertyReportRules.EventView;
import com.c21inova.api.property.PropertyReportRules.InquiryRow;
import com.c21inova.api.property.PropertyReportRules.Metrics;
import com.c21inova.api.property.PropertyReportRules.WeeklyPeriod;

/**
 * Reportes por propiedad, destinatarios (propietarios), actividades y envíos.
 * Envíos reales solo con: función habilitada, horario/flujo/campos
 * configurados, destinatario verificado y autorizado, y AUTOMATION_MODE=live.
 */
@Service
public class PropertyReportService {

	private static final int[] RETRY_MINUTES = { 5, 30, 120 };
	private static final int MAX_ATTEMPTS = 3;
	public static final List<String> RECIPIENT_RELATIONS = List.of("propietario", "copropietario", "representante", "administrador", "otro");

	public record Settings(String companyId, boolean weeklyEnabled, int weeklyWeekday, Integer weeklyHour, int weeklyMinute, String timeZone,
			boolean eventNotificationsEnabled, String weeklyFlowNs, String eventFlowNs, Object fieldIds, String templateNote, String updatedBy,
			Instant updatedAt) {

		public Map<String, Object> toView() {
			return Json.obj("weeklyEnabled", weeklyEnabled, "weeklyWeekday", weeklyWeekday, "weeklyHour", weeklyHour, "weeklyMinute", weeklyMinute, "timeZone",
					timeZone, "eventNotificationsEnabled", eventNotificationsEnabled, "manyChatWeeklyFlowNs", weeklyFlowNs, "manyChatEventFlowNs", eventFlowNs,
					"manyChatFieldIds", fieldIds, "templateNote", templateNote, "updatedBy", updatedBy, "updatedAt", updatedAt);
		}
	}

	public record Recipient(String id, String companyId, String publicId, String name, String phoneE164, String relation, boolean weeklyReport,
			boolean eventNotifications, String consentStatus, String consentEvidence, Instant consentAt, String consentRecordedBy, String subscriberId,
			Instant verifiedAt, String verifiedBy, String verificationNote, Instant lastSentAt, String lastSendStatus, String lastError, boolean active,
			String notes, String createdBy, Instant createdAt) {

		static Recipient map(ResultSet rs, int i) throws SQLException {
			return new Recipient(rs.getString("id"), rs.getString("companyId"), rs.getString("publicId"), rs.getString("name"), rs.getString("phoneE164"),
					rs.getString("relation"), rs.getBoolean("weeklyReport"), rs.getBoolean("eventNotifications"), rs.getString("consentStatus"),
					rs.getString("consentEvidence"), Db.instant(rs, "consentAt"), rs.getString("consentRecordedBy"), rs.getString("manyChatSubscriberId"),
					Db.instant(rs, "verifiedAt"), rs.getString("verifiedBy"), rs.getString("verificationNote"), Db.instant(rs, "lastSentAt"),
					rs.getString("lastSendStatus"), rs.getString("lastError"), rs.getBoolean("active"), rs.getString("notes"), rs.getString("createdBy"),
					Db.instant(rs, "createdAt"));
		}

		List<String> blockers(String purpose) {
			return PropertyReportRules.recipientBlockers(active, consentStatus, verifiedAt, subscriberId, weeklyReport, eventNotifications, purpose);
		}
	}

	private static final String RECIPIENT_COLUMNS = """
			id, "companyId", "publicId", name, "phoneE164", relation, "weeklyReport", "eventNotifications", "consentStatus"::text AS "consentStatus",
			"consentEvidence", "consentAt", "consentRecordedBy", "manyChatSubscriberId", "verifiedAt", "verifiedBy", "verificationNote", "lastSentAt",
			"lastSendStatus", "lastError", active, notes, "createdBy", "createdAt"
			""";

	public record Event(String id, String companyId, String publicId, String type, String status, String title, String description, Instant scheduledAt,
			Instant completedAt, String outcome, String origin, String externalId, int version, boolean notifyRecipients, String createdBy, String updatedBy,
			Instant createdAt, Instant updatedAt) {

		static Event map(ResultSet rs, int i) throws SQLException {
			return new Event(rs.getString("id"), rs.getString("companyId"), rs.getString("publicId"), rs.getString("type"), rs.getString("status"),
					rs.getString("title"), rs.getString("description"), Db.instant(rs, "scheduledAt"), Db.instant(rs, "completedAt"), rs.getString("outcome"),
					rs.getString("origin"), rs.getString("externalId"), rs.getInt("version"), rs.getBoolean("notifyRecipients"), rs.getString("createdBy"),
					rs.getString("updatedBy"), Db.instant(rs, "createdAt"), Db.instant(rs, "updatedAt"));
		}

		EventView view() {
			return new EventView(type, status, title, description, scheduledAt, completedAt, outcome, version);
		}
	}

	private static final String EVENT_COLUMNS = """
			id, "companyId", "publicId", type::text AS type, status::text AS status, title, description, "scheduledAt", "completedAt", outcome, origin,
			"externalId", version, "notifyRecipients", "createdBy", "updatedBy", "createdAt", "updatedAt"
			""";

	public record Delivery(String id, String companyId, String recipientId, String publicId, String kind, String dedupeKey, String status, int attempts,
			Object payload, String text) {
	}

	private final JdbcClient jdbc;
	private final ManyChatService manyChat;
	private final AuditService audit;
	private final Env env;

	public PropertyReportService(JdbcClient jdbc, ManyChatService manyChat, AuditService audit, Env env) {
		this.jdbc = jdbc;
		this.manyChat = manyChat;
		this.audit = audit;
		this.env = env;
	}

	// --- Configuración -------------------------------------------------------------------------

	public Settings settings(String companyId) {
		Settings existing = findSettings(companyId);
		if (existing != null) return existing;
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO property_report_settings (id, "companyId", "createdAt", "updatedAt") VALUES (:id, :c, :now, :now) ON CONFLICT ("companyId") DO NOTHING
				""").param("id", Ids.cuid()).param("c", companyId).param("now", Db.ts(now)).update();
		return findSettings(companyId);
	}

	private Settings findSettings(String companyId) {
		return jdbc.sql("""
				SELECT "companyId", "weeklyEnabled", "weeklyWeekday", "weeklyHour", "weeklyMinute", "timeZone", "eventNotificationsEnabled",
				  "manyChatWeeklyFlowNs", "manyChatEventFlowNs", "manyChatFieldIds"::text AS "manyChatFieldIds", "templateNote", "updatedBy", "updatedAt"
				FROM property_report_settings WHERE "companyId" = :c
				""").param("c", companyId).query((rs, i) -> new Settings(rs.getString("companyId"), rs.getBoolean("weeklyEnabled"), rs.getInt("weeklyWeekday"),
				Db.integer(rs, "weeklyHour"), rs.getInt("weeklyMinute"), rs.getString("timeZone"), rs.getBoolean("eventNotificationsEnabled"),
				rs.getString("manyChatWeeklyFlowNs"), rs.getString("manyChatEventFlowNs"), Db.json(rs, "manyChatFieldIds"), rs.getString("templateNote"),
				rs.getString("updatedBy"), Db.instant(rs, "updatedAt"))).optional().orElse(null);
	}

	private static Map<String, Integer> fieldGroup(Object raw, List<String> keys) {
		Map<String, Object> map = Json.asMap(raw);
		if (map == null) return null;
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String key : keys) {
			Object value = map.get(key);
			if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) return null;
			out.put(key, n.intValue());
		}
		return out;
	}

	public static final List<String> WEEKLY_FIELDS = List.of("property", "period", "weekLeads", "cumulative", "sources", "activity");
	public static final List<String> EVENT_FIELDS = List.of("property", "headline", "detail");

	static Map<String, Integer> weeklyIds(Settings settings) {
		return fieldGroup(Json.asMap(settings.fieldIds()) == null ? null : Json.asMap(settings.fieldIds()).get("weekly"), WEEKLY_FIELDS);
	}

	static Map<String, Integer> eventIds(Settings settings) {
		return fieldGroup(Json.asMap(settings.fieldIds()) == null ? null : Json.asMap(settings.fieldIds()).get("event"), EVENT_FIELDS);
	}

	/** Lo que falta para poder enviar. purpose: weekly | event | test. */
	public List<String> settingsBlockers(Settings settings, String purpose) {
		List<String> blockers = new ArrayList<>();
		if ("weekly".equals(purpose) && !settings.weeklyEnabled()) blockers.add("reporte semanal deshabilitado");
		if ("weekly".equals(purpose) && settings.weeklyHour() == null) blockers.add("hora de envío sin confirmar");
		if ("event".equals(purpose) && !settings.eventNotificationsEnabled()) blockers.add("avisos de actividad deshabilitados");
		if (!"event".equals(purpose) && (settings.weeklyFlowNs() == null || weeklyIds(settings) == null)) {
			blockers.add("falta el flujo de ManyChat con la plantilla semanal aprobada y sus campos");
		}
		if ("event".equals(purpose) && (settings.eventFlowNs() == null || eventIds(settings) == null)) {
			blockers.add("falta el flujo de ManyChat con la plantilla de actividad aprobada y sus campos");
		}
		if (!env.isLiveAutomation()) blockers.add("AUTOMATION_MODE no está en live");
		return blockers;
	}

	public record SettingsUpdate(boolean weeklyEnabled, Integer weeklyHour, int weeklyMinute, boolean eventNotificationsEnabled, String weeklyFlowNs,
			String eventFlowNs, Map<String, Integer> weeklyFieldIds, Map<String, Integer> eventFieldIds, String templateNote) {
	}

	public Settings updateSettings(String companyId, SettingsUpdate update, String by) {
		if (update.weeklyEnabled() && (update.weeklyHour() == null || update.weeklyFlowNs() == null || update.weeklyFieldIds() == null)) {
			throw ApiException.badRequest("Para habilitar el reporte semanal se necesitan la hora confirmada, el flujo con la plantilla aprobada y los campos.");
		}
		if (update.eventNotificationsEnabled() && (update.eventFlowNs() == null || update.eventFieldIds() == null)) {
			throw ApiException.badRequest("Para habilitar los avisos de actividad se necesitan el flujo con la plantilla aprobada y los campos.");
		}
		settings(companyId);
		Map<String, Object> fieldIds = new LinkedHashMap<>();
		fieldIds.put("weekly", update.weeklyFieldIds());
		fieldIds.put("event", update.eventFieldIds());
		jdbc.sql("""
				UPDATE property_report_settings SET "weeklyEnabled" = :we, "weeklyHour" = :wh, "weeklyMinute" = :wm, "eventNotificationsEnabled" = :ee,
				  "manyChatWeeklyFlowNs" = :wf, "manyChatEventFlowNs" = :ef, "manyChatFieldIds" = CAST(:ids AS jsonb), "templateNote" = :note,
				  "updatedBy" = :by, "updatedAt" = :now WHERE "companyId" = :c
				""").param("we", update.weeklyEnabled()).param("wh", update.weeklyHour()).param("wm", update.weeklyMinute())
				.param("ee", update.eventNotificationsEnabled()).param("wf", update.weeklyFlowNs()).param("ef", update.eventFlowNs())
				.param("ids", Db.jsonParam(fieldIds)).param("note", update.templateNote()).param("by", by).param("now", Db.ts(Instant.now())).param("c", companyId)
				.update();
		return settings(companyId);
	}

	// --- Lecturas para el panel ---------------------------------------------------------------------

	private List<InquiryRow> inquiryRows(String companyId, String endKey, String publicId) {
		return jdbc.sql("""
				SELECT "publicId", "contactKey", day, "messageCount", channel, "linkPortal", "acquisitionSource", "declaredSource", "isTest", "lastAt"
				FROM property_inquiries WHERE "companyId" = :c AND "isTest" = false AND day <= :end
				""" + (publicId != null ? " AND \"publicId\" = :p" : "")).param("c", companyId).param("end", endKey).param("p", publicId)
				.query((rs, i) -> new InquiryRow(rs.getString("publicId"), rs.getString("contactKey"), rs.getString("day"), rs.getInt("messageCount"),
						rs.getString("channel"), rs.getString("linkPortal"), rs.getString("acquisitionSource"), rs.getString("declaredSource"),
						rs.getBoolean("isTest"), Db.instant(rs, "lastAt")))
				.list();
	}

	/** Primer día con datos: los reportes cubren desde aquí, no el histórico completo de la empresa. */
	public String dataSinceKey(String companyId) {
		return jdbc.sql("SELECT min(day) FROM property_inquiries WHERE \"companyId\" = :c AND \"isTest\" = false").param("c", companyId).query(String.class)
				.optional().orElse(null);
	}

	private static Instant dayStart(String key) {
		return MexicoTime.startOfDay(LocalDate.parse(key));
	}

	private static Instant dayAfter(String key) {
		return MexicoTime.startOfDay(LocalDate.parse(key).plusDays(1));
	}

	public List<Map<String, Object>> listReport(String companyId, String startKey, String endKey, String search) {
		List<Metrics> metrics = PropertyReportRules.aggregate(inquiryRows(companyId, endKey, null), startKey, endKey);
		if (metrics.isEmpty()) return List.of();
		List<String> ids = metrics.stream().map(Metrics::publicId).toList();
		Map<String, Map<String, Object>> cache = new HashMap<>();
		jdbc.sql("""
				SELECT "publicId", title, published, "publicUrl", "propertyType", location, bedrooms FROM property_cache WHERE "companyId" = :c AND "publicId" IN (:ids)
				""").param("c", companyId).param("ids", ids).query((rs, i) -> cache.put(rs.getString("publicId"), Json.obj("title", rs.getString("title"), "published",
				rs.getBoolean("published"), "publicUrl", rs.getString("publicUrl"), "propertyType", rs.getString("propertyType"), "location",
				rs.getString("location"), "bedrooms", Db.integer(rs, "bedrooms")))).list();
		Map<String, int[]> eventCounts = new HashMap<>();
		Map<String, Instant> eventUpdated = new HashMap<>();
		jdbc.sql("""
				SELECT "publicId", count(*)::int AS n, max("updatedAt") AS updated FROM property_events
				WHERE "companyId" = :c AND "publicId" IN (:ids) AND (("scheduledAt" >= :start AND "scheduledAt" < :end) OR ("completedAt" >= :start AND "completedAt" < :end))
				GROUP BY "publicId"
				""").param("c", companyId).param("ids", ids).param("start", Db.ts(dayStart(startKey))).param("end", Db.ts(dayAfter(endKey))).query((rs, i) -> {
			eventCounts.put(rs.getString("publicId"), new int[] { rs.getInt("n") });
			eventUpdated.put(rs.getString("publicId"), Db.instant(rs, "updated"));
			return null;
		}).list();
		Map<String, Integer> recipients = new HashMap<>();
		jdbc.sql("""
				SELECT "publicId", count(*)::int AS n FROM property_recipients WHERE "companyId" = :c AND active = true AND "publicId" IN (:ids) GROUP BY "publicId"
				""").param("c", companyId).param("ids", ids).query((rs, i) -> recipients.put(rs.getString("publicId"), rs.getInt("n"))).list();
		String term = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Metrics m : metrics) {
			Map<String, Object> c = cache.get(m.publicId());
			Map<String, Object> row = m.toJson();
			row.put("title", c == null ? null : c.get("title"));
			row.put("published", c == null ? null : c.get("published"));
			row.put("publicUrl", c == null ? null : c.get("publicUrl"));
			row.put("propertyType", c == null ? null : c.get("propertyType"));
			row.put("location", c == null ? null : c.get("location"));
			row.put("bedrooms", c == null ? null : c.get("bedrooms"));
			row.put("periodEvents", eventCounts.containsKey(m.publicId()) ? eventCounts.get(m.publicId())[0] : 0);
			row.put("recipients", recipients.getOrDefault(m.publicId(), 0));
			Instant updated = eventUpdated.get(m.publicId());
			Instant last = m.lastInquiryAt();
			row.put("lastActivityAt", last == null ? updated : updated == null ? last : updated.isAfter(last) ? updated : last);
			String title = c == null || c.get("title") == null ? "" : ((String) c.get("title")).toLowerCase(Locale.ROOT);
			if (term == null || term.isEmpty() || m.publicId().toLowerCase(Locale.ROOT).contains(term) || title.contains(term)) rows.add(row);
		}
		return rows;
	}

	public static String maskContact(String contactKey) {
		if (contactKey.startsWith("mc:")) return "Contacto de WhatsApp";
		String digits = contactKey.replaceAll("\\D", "");
		return digits.length() >= 4 ? "•••• " + digits.substring(digits.length() - 4) : "••••";
	}

	public Map<String, Object> detail(String companyId, String rawPublicId, String startKey, String endKey) {
		String publicId = rawPublicId.toUpperCase(Locale.ROOT);
		List<Metrics> metrics = PropertyReportRules.aggregate(inquiryRows(companyId, endKey, publicId), startKey, endKey);
		Map<String, Object> property = jdbc.sql("""
				SELECT "publicId", title, "propertyType", location, operations::text AS operations, bedrooms, bathrooms, "publicUrl", published, "syncedAt"
				FROM property_cache WHERE "companyId" = :c AND "publicId" = :p
				""").param("c", companyId).param("p", publicId).query((rs, i) -> Json.obj("publicId", rs.getString("publicId"), "title", rs.getString("title"),
				"propertyType", rs.getString("propertyType"), "location", rs.getString("location"), "operations", Db.json(rs, "operations"), "bedrooms",
				Db.integer(rs, "bedrooms"), "bathrooms", Db.dbl(rs, "bathrooms"), "publicUrl", rs.getString("publicUrl"), "published",
				rs.getBoolean("published"), "syncedAt", Db.instant(rs, "syncedAt"))).optional().orElse(null);
		List<Event> events = jdbc.sql("SELECT " + EVENT_COLUMNS
				+ " FROM property_events WHERE \"companyId\" = :c AND \"publicId\" = :p ORDER BY \"scheduledAt\" DESC NULLS LAST, \"createdAt\" DESC LIMIT 100")
				.param("c", companyId).param("p", publicId).query(Event::map).list();
		List<Recipient> recipients = jdbc.sql("SELECT " + RECIPIENT_COLUMNS
				+ " FROM property_recipients WHERE \"companyId\" = :c AND \"publicId\" = :p ORDER BY \"createdAt\" ASC").param("c", companyId).param("p", publicId)
				.query(Recipient::map).list();
		List<Map<String, Object>> deliveries = jdbc.sql("""
				SELECT d.id, d.kind::text AS kind, d.status::text AS status, d.attempts, d."lastError", d.text, d."sentAt", d."createdAt", d."periodStart",
				  d."periodEnd", r.name AS "recipientName"
				FROM property_report_deliveries d JOIN property_recipients r ON r.id = d."recipientId"
				WHERE d."companyId" = :c AND d."publicId" = :p ORDER BY d."createdAt" DESC LIMIT 40
				""").param("c", companyId).param("p", publicId).query((rs, i) -> Json.obj("id", rs.getString("id"), "kind", rs.getString("kind"), "status",
				rs.getString("status"), "attempts", rs.getInt("attempts"), "lastError", rs.getString("lastError"), "text", rs.getString("text"), "sentAt",
				Db.instant(rs, "sentAt"), "createdAt", Db.instant(rs, "createdAt"), "periodStart", Db.instant(rs, "periodStart"), "periodEnd",
				Db.instant(rs, "periodEnd"), "recipient", Json.obj("name", rs.getString("recipientName")))).list();
		List<Map<String, Object>> inquiries = jdbc.sql("""
				SELECT i.id, i."contactKey", i.day, i.method, i.evidence, i."linkPortal", i."acquisitionSource", i."declaredSource", i."messageCount", i.source,
				  i."isTest", i."firstAt", i."lastAt", i."conversationId", l.id AS "leadId", a.name AS "advisorName"
				FROM property_inquiries i LEFT JOIN leads l ON l.id = i."leadId" LEFT JOIN advisors a ON a.id = l."assignedAdvisorId"
				WHERE i."companyId" = :c AND i."publicId" = :p AND i.day >= :start AND i.day <= :end ORDER BY i."lastAt" DESC LIMIT 200
				""").param("c", companyId).param("p", publicId).param("start", startKey).param("end", endKey).query((rs, i) -> Json.obj("id", rs.getString("id"),
				"contact", maskContact(rs.getString("contactKey")), "day", rs.getString("day"), "method", rs.getString("method"), "evidence",
				rs.getString("evidence"), "linkPortal", rs.getString("linkPortal"), "acquisitionSource", rs.getString("acquisitionSource"), "declaredSource",
				rs.getString("declaredSource"), "messageCount", rs.getInt("messageCount"), "source", rs.getString("source"), "isTest", rs.getBoolean("isTest"),
				"firstAt", Db.instant(rs, "firstAt"), "lastAt", Db.instant(rs, "lastAt"), "conversationId", rs.getString("conversationId"), "lead",
				rs.getString("leadId") == null ? null : Json.obj("id", rs.getString("leadId"), "advisorName", rs.getString("advisorName")))).list();
		List<Map<String, Object>> portalListings = jdbc.sql("""
				SELECT portal, "portalName", "externalKey", "listingUrl", published, status, source, "syncedAt" FROM portal_listings
				WHERE "companyId" = :c AND "publicId" = :p ORDER BY portal ASC
				""").param("c", companyId).param("p", publicId).query((rs, i) -> Json.obj("portal", rs.getString("portal"), "portalName", rs.getString("portalName"),
				"externalKey", rs.getString("externalKey"), "listingUrl", rs.getString("listingUrl"), "published", rs.getBoolean("published"), "status",
				rs.getString("status"), "source", rs.getString("source"), "syncedAt", Db.instant(rs, "syncedAt"))).list();
		return Json.obj("publicId", publicId, "property", property, "metrics", metrics.isEmpty() ? null : metrics.get(0).toJson(), "events",
				events.stream().map(PropertyReportService::eventView).toList(), "recipients",
				recipients.stream().map(r -> recipientView(r, settings(companyId))).toList(), "deliveries", deliveries, "inquiries", inquiries, "portalListings",
				portalListings);
	}

	static Map<String, Object> eventView(Event e) {
		return Json.obj("id", e.id(), "publicId", e.publicId(), "type", e.type(), "typeLabel", PropertyReportRules.EVENT_TYPE_LABELS.get(e.type()), "status",
				e.status(), "statusLabel", PropertyReportRules.EVENT_STATUS_LABELS.get(e.status()), "title", e.title(), "description", e.description(),
				"scheduledAt", e.scheduledAt(), "completedAt", e.completedAt(), "outcome", e.outcome(), "origin", e.origin(), "version", e.version(),
				"notifyRecipients", e.notifyRecipients(), "createdBy", e.createdBy(), "updatedBy", e.updatedBy(), "createdAt", e.createdAt(), "updatedAt",
				e.updatedAt());
	}

	Map<String, Object> recipientView(Recipient r, Settings settings) {
		return Json.obj("id", r.id(), "publicId", r.publicId(), "name", r.name(), "phoneE164", r.phoneE164(), "relation", r.relation(), "weeklyReport",
				r.weeklyReport(), "eventNotifications", r.eventNotifications(), "consentStatus", r.consentStatus(), "consentEvidence", r.consentEvidence(),
				"consentAt", r.consentAt(), "consentRecordedBy", r.consentRecordedBy(), "manyChatSubscriberId", r.subscriberId(), "verifiedAt", r.verifiedAt(),
				"verifiedBy", r.verifiedBy(), "verificationNote", r.verificationNote(), "lastSentAt", r.lastSentAt(), "lastSendStatus", r.lastSendStatus(),
				"lastError", r.lastError(), "active", r.active(), "notes", r.notes(), "createdAt", r.createdAt(), "weeklyBlockers", r.blockers("weekly"),
				"eventBlockers", r.blockers("event"), "testBlockers", r.blockers("test"));
	}

	// --- Reporte semanal: vista previa y cola -------------------------------------------------------

	public PropertyReportRules.WeeklyReport buildWeekly(String companyId, String publicId, WeeklyPeriod period) {
		List<Metrics> metrics = PropertyReportRules.aggregate(inquiryRows(companyId, period.endKey(), publicId), period.startKey(), period.endKey());
		String title = jdbc.sql("SELECT title FROM property_cache WHERE \"companyId\" = :c AND \"publicId\" = :p").param("c", companyId).param("p", publicId)
				.query(String.class).optional().orElse(null);
		List<EventView> events = jdbc.sql("SELECT " + EVENT_COLUMNS + """
				 FROM property_events WHERE "companyId" = :c AND "publicId" = :p AND (
				   ("scheduledAt" >= :start AND "scheduledAt" < :end) OR ("completedAt" >= :start AND "completedAt" < :end)
				   OR (status = 'SCHEDULED' AND "scheduledAt" >= :end AND "scheduledAt" < :upcoming))
				 ORDER BY "scheduledAt" ASC NULLS LAST
				""").param("c", companyId).param("p", publicId).param("start", Db.ts(period.start())).param("end", Db.ts(period.endExclusive()))
				.param("upcoming", Db.ts(period.endExclusive().plusSeconds(14 * 86_400L))).query(Event::map).list().stream().map(Event::view).toList();
		return PropertyReportRules.buildWeekly(publicId, title, period, metrics.isEmpty() ? null : metrics.get(0), dataSinceKey(companyId), events);
	}

	private static Map<String, Object> reportPayload(PropertyReportRules.WeeklyReport report, WeeklyPeriod period) {
		return Json.obj("variables", report.variables(), "metrics", Json.obj("periodLeads", report.periodLeads(), "cumulativeLeads", report.cumulativeLeads()),
				"periodKey", period.startKey());
	}

	private boolean insertDelivery(String companyId, String recipientId, String publicId, String kind, String dedupeKey, Instant periodStart, Instant periodEnd,
			String eventId, Object payload, String text, String createdBy, String id) {
		Instant now = Instant.now();
		try {
			jdbc.sql("""
					INSERT INTO property_report_deliveries (id, "companyId", "recipientId", "publicId", kind, "dedupeKey", "periodStart", "periodEnd", "eventId",
					  status, attempts, "nextAttemptAt", payload, text, "createdBy", "createdAt", "updatedAt")
					VALUES (:id, :c, :r, :p, CAST(:kind AS "DeliveryKind"), :key, :ps, :pe, :event, 'PENDING', 0, :now, CAST(:payload AS jsonb), :text, :by, :now, :now)
					""").param("id", id).param("c", companyId).param("r", recipientId).param("p", publicId).param("kind", kind).param("key", dedupeKey)
					.param("ps", Db.ts(periodStart)).param("pe", Db.ts(periodEnd)).param("event", eventId).param("payload", Db.jsonParam(payload))
					.param("text", text).param("by", createdBy).param("now", Db.ts(now)).update();
			return true;
		} catch (DuplicateKeyException duplicate) {
			// Otra ejecución ya lo creó (clave única): no se duplica.
			return false;
		}
	}

	private List<Recipient> recipients(String where, Map<String, Object> params) {
		JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + RECIPIENT_COLUMNS + " FROM property_recipients WHERE " + where);
		for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), entry.getValue());
		return spec.query(Recipient::map).list();
	}

	/** Encola el reporte semanal de cada destinatario habilitado. Idempotente por weekly:<destinatario>:<propiedad>:<inicio>. */
	public Map<String, Object> enqueueWeekly(String companyId, Instant now) {
		Settings settings = settings(companyId);
		WeeklyPeriod period = PropertyReportRules.weeklyPeriodFor(now, settings.weeklyWeekday());
		int created = 0;
		int skipped = 0;
		for (Recipient recipient : recipients("\"companyId\" = :c AND active = true AND \"weeklyReport\" = true", Map.of("c", companyId))) {
			if (!recipient.blockers("weekly").isEmpty()) {
				skipped++;
				continue;
			}
			String dedupeKey = "weekly:" + recipient.id() + ":" + recipient.publicId() + ":" + period.startKey();
			boolean exists = jdbc.sql("SELECT count(*) FROM property_report_deliveries WHERE \"dedupeKey\" = :k").param("k", dedupeKey).query(Integer.class).single() > 0;
			if (exists) continue;
			PropertyReportRules.WeeklyReport report = buildWeekly(companyId, recipient.publicId(), period);
			if (insertDelivery(companyId, recipient.id(), recipient.publicId(), "WEEKLY_REPORT", dedupeKey, period.start(), period.endExclusive(), null,
					reportPayload(report, period), report.text(), null, Ids.cuid())) {
				created++;
			}
		}
		return Json.obj("periodKey", period.startKey(), "created", created, "skipped", skipped);
	}

	// --- Envío ------------------------------------------------------------------------------------

	private void markRecipient(String recipientId, String status, String error, Instant sentAt) {
		jdbc.sql("UPDATE property_recipients SET \"lastSendStatus\" = :s, \"lastError\" = :e, \"lastSentAt\" = COALESCE(:sent, \"lastSentAt\"), \"updatedAt\" = :now WHERE id = :id")
				.param("s", status).param("e", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error).param("sent", Db.ts(sentAt))
				.param("now", Db.ts(Instant.now())).param("id", recipientId).update();
	}

	private void updateDelivery(String id, String set, Map<String, Object> params) {
		JdbcClient.StatementSpec spec = jdbc.sql("UPDATE property_report_deliveries SET " + set + ", \"updatedAt\" = :now WHERE id = :id").param("id", id)
				.param("now", Db.ts(Instant.now()));
		for (Map.Entry<String, Object> entry : params.entrySet()) spec = spec.param(entry.getKey(), entry.getValue());
		spec.update();
	}

	private Delivery findDelivery(String id) {
		return jdbc.sql("""
				SELECT id, "companyId", "recipientId", "publicId", kind::text AS kind, "dedupeKey", status::text AS status, attempts, payload::text AS payload, text
				FROM property_report_deliveries WHERE id = :id
				""").param("id", id).query((rs, i) -> new Delivery(rs.getString("id"), rs.getString("companyId"), rs.getString("recipientId"),
				rs.getString("publicId"), rs.getString("kind"), rs.getString("dedupeKey"), rs.getString("status"), rs.getInt("attempts"), Db.json(rs, "payload"),
				rs.getString("text"))).single();
	}

	/**
	 * Intenta un envío revalidando todo en el momento (una autorización revocada
	 * después de encolar gana). SENT solo si ManyChat aceptó; timeout = UNCERTAIN.
	 */
	public String attemptDelivery(Delivery delivery) {
		Settings settings = settings(delivery.companyId());
		Recipient recipient = recipients("id = :id", Map.of("id", delivery.recipientId())).get(0);
		String purpose = "EVENT_NOTICE".equals(delivery.kind()) ? "event" : "TEST".equals(delivery.kind()) ? "test" : "weekly";
		List<String> blockers = new ArrayList<>(recipient.blockers(purpose));
		blockers.addAll(settingsBlockers(settings, purpose));
		if (!blockers.isEmpty()) {
			String reason = "No enviado: " + String.join("; ", blockers) + ".";
			updateDelivery(delivery.id(), "status = 'SKIPPED', \"lastError\" = :e", Map.of("e", reason));
			markRecipient(recipient.id(), "SKIPPED", reason, null);
			return "SKIPPED";
		}
		int claimed = jdbc.sql("""
				UPDATE property_report_deliveries SET status = 'SENDING', attempts = attempts + 1, "updatedAt" = :now WHERE id = :id AND status = 'PENDING'
				""").param("now", Db.ts(Instant.now())).param("id", delivery.id()).update();
		if (claimed == 0) return delivery.status();

		Map<String, Object> variables = Json.asMap(Json.asMap(delivery.payload()).get("variables"));
		List<AdvisorNotice.Field> fields = new ArrayList<>();
		Map<String, Integer> ids = "event".equals(purpose) ? eventIds(settings) : weeklyIds(settings);
		for (String key : "event".equals(purpose) ? EVENT_FIELDS : WEEKLY_FIELDS) {
			String value = Json.str(variables, key);
			fields.add(new AdvisorNotice.Field(ids.get(key), value == null ? "" : value));
		}
		String flowNs = "event".equals(purpose) ? settings.eventFlowNs() : settings.weeklyFlowNs();
		try {
			// Los campos del contacto se sobrescriben con ESTE reporte antes de lanzar el flujo.
			manyChat.setCustomFields(recipient.subscriberId(), fields);
			manyChat.sendFlowOnce(recipient.subscriberId(), flowNs);
			Instant sentAt = Instant.now();
			updateDelivery(delivery.id(), "status = 'SENT', \"sentAt\" = :sent, \"lastError\" = NULL", Map.of("sent", Db.ts(sentAt)));
			markRecipient(recipient.id(), "SENT", null, sentAt);
			audit.log(delivery.companyId(), null, null, "OWNER_REPORT_SENT", "ok",
					delivery.kind() + " " + delivery.publicId() + " enviado a destinatario " + recipient.id() + ".");
			return "SENT";
		} catch (ManyChatTimeoutException timeout) {
			String reason = "Timeout de ManyChat: el mensaje pudo haberse entregado. Revisar antes de reenviar.";
			updateDelivery(delivery.id(), "status = 'UNCERTAIN', \"lastError\" = :e", Map.of("e", reason));
			markRecipient(recipient.id(), "UNCERTAIN", reason, null);
			return "UNCERTAIN";
		} catch (RuntimeException error) {
			int attempts = delivery.attempts() + 1;
			String detail = error instanceof ManyChatApiException api ? api.getMessage() + " " + truncate(Json.write(api.body == null ? "null" : api.body), 300)
					: error.getMessage() != null ? error.getMessage() : "Error desconocido";
			boolean finalFailure = attempts >= MAX_ATTEMPTS
					|| (error instanceof ManyChatApiException api && api.status >= 400 && api.status < 500 && api.status != 429);
			Instant next = Instant.now().plusSeconds(RETRY_MINUTES[Math.min(attempts - 1, RETRY_MINUTES.length - 1)] * 60L);
			updateDelivery(delivery.id(), "status = CAST(:s AS \"DeliveryStatus\"), \"lastError\" = :e, \"nextAttemptAt\" = :next",
					Map.of("s", finalFailure ? "FAILED" : "PENDING", "e", truncate(detail, 1000), "next", Db.ts(next)));
			markRecipient(recipient.id(), finalFailure ? "FAILED" : "PENDING", detail, null);
			return finalFailure ? "FAILED" : "PENDING";
		}
	}

	private static String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

	public Map<String, Integer> processDue(int limit, long deadline) {
		List<String> due = jdbc.sql("""
				SELECT id FROM property_report_deliveries WHERE status = 'PENDING' AND "nextAttemptAt" <= :now ORDER BY "nextAttemptAt" ASC LIMIT :limit
				""").param("now", Db.ts(Instant.now())).param("limit", limit).query(String.class).list();
		Map<String, Integer> summary = new LinkedHashMap<>();
		for (String id : due) {
			if (System.currentTimeMillis() + 8_000 > deadline) break;
			summary.merge(attemptDelivery(findDelivery(id)), 1, Integer::sum);
		}
		// Un proceso que murió entre SENDING y el resultado: incierto, nunca reenviado solo.
		jdbc.sql("""
				UPDATE property_report_deliveries SET status = 'UNCERTAIN', "lastError" = 'El proceso se interrumpió durante el envío; pudo haberse entregado.',
				  "updatedAt" = :now WHERE status = 'SENDING' AND "updatedAt" < :cutoff
				""").param("now", Db.ts(Instant.now())).param("cutoff", Db.ts(Instant.now().minusSeconds(300))).update();
		return summary;
	}

	/** Cron: encola el reporte semanal cuando ya toca y procesa la cola. */
	public Map<String, Object> runCron(String companyId, Instant now) {
		Settings settings = settings(companyId);
		Map<String, Object> weekly = null;
		if (settings.weeklyEnabled() && PropertyReportRules.isWeeklyDue(now, settings.weeklyWeekday(), settings.weeklyHour(), settings.weeklyMinute())) {
			weekly = enqueueWeekly(companyId, now);
		}
		return Json.obj("weekly", weekly, "deliveries", processDue(20, System.currentTimeMillis() + 50_000));
	}

	/** Prueba a UN destinatario autorizado (ADMIN), con los mismos candados. */
	public String sendTest(String companyId, String recipientId, String by) {
		List<Recipient> found = recipients("id = :id AND \"companyId\" = :c", Map.of("id", recipientId, "c", companyId));
		if (found.isEmpty()) throw ApiException.notFound("Destinatario no encontrado.");
		Recipient recipient = found.get(0);
		List<String> blockers = recipient.blockers("test");
		if (!blockers.isEmpty()) throw ApiException.badRequest("No se puede enviar la prueba: " + String.join("; ", blockers) + ".");
		Settings settings = settings(companyId);
		List<String> issues = settingsBlockers(settings, "test");
		if (!issues.isEmpty()) throw ApiException.badRequest("No se puede enviar la prueba: " + String.join("; ", issues) + ".");
		WeeklyPeriod period = PropertyReportRules.weeklyPeriodFor(Instant.now(), settings.weeklyWeekday());
		PropertyReportRules.WeeklyReport report = buildWeekly(companyId, recipient.publicId(), period);
		String id = Ids.cuid();
		insertDelivery(companyId, recipient.id(), recipient.publicId(), "TEST", "test:" + UUID.randomUUID(), period.start(), period.endExclusive(), null,
				reportPayload(report, period), report.text(), by, id);
		return attemptDelivery(findDelivery(id));
	}

	// --- Destinatarios ------------------------------------------------------------------------------

	public boolean phoneIsProspect(String companyId, String phoneE164) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM leads WHERE "companyId" = :c AND phone = :p) OR EXISTS (SELECT 1 FROM property_inquiries WHERE "companyId" = :c AND "contactKey" = :p)
				""").param("c", companyId).param("p", phoneE164).query(Boolean.class).single();
	}

	public void createRecipient(String companyId, String publicId, String name, String phone, String relation, String subscriberId, String notes,
			boolean confirmNotProspect, String by) {
		String phoneE164 = Phone.normalizeE164(phone);
		if (phoneE164.isEmpty()) throw ApiException.badRequest("Teléfono inválido: usa 10 dígitos o formato internacional.");
		if (!confirmNotProspect && phoneIsProspect(companyId, phoneE164)) {
			throw ApiException.badRequest(
					"Ese teléfono aparece como prospecto (lead o consulta). Confirma explícitamente que es el propietario/destinatario antes de guardarlo.");
		}
		Instant now = Instant.now();
		try {
			// Las casillas de envío quedan apagadas hasta registrar la autorización y verificar.
			jdbc.sql("""
					INSERT INTO property_recipients (id, "companyId", "publicId", name, "phoneE164", relation, "weeklyReport", "eventNotifications",
					  "consentStatus", "manyChatSubscriberId", active, notes, "createdBy", "createdAt", "updatedAt")
					VALUES (:id, :c, :p, :name, :phone, :relation, false, false, 'PENDING', :s, true, :notes, :by, :now, :now)
					""").param("id", Ids.cuid()).param("c", companyId).param("p", publicId.toUpperCase(Locale.ROOT))
					.param("name", name.trim().length() > 120 ? name.trim().substring(0, 120) : name.trim()).param("phone", phoneE164).param("relation", relation)
					.param("s", subscriberId).param("notes", notes == null ? null : notes.length() > 500 ? notes.substring(0, 500) : notes).param("by", by)
					.param("now", Db.ts(now)).update();
		} catch (DuplicateKeyException duplicate) {
			throw ApiException.badRequest("Ese teléfono ya está registrado para esta propiedad.");
		}
	}

	private Recipient requireRecipient(String companyId, String recipientId) {
		List<Recipient> found = recipients("id = :id AND \"companyId\" = :c", Map.of("id", recipientId, "c", companyId));
		if (found.isEmpty()) throw ApiException.notFound("Destinatario no encontrado.");
		return found.get(0);
	}

	public void updateRecipientFlags(String companyId, String recipientId, boolean weeklyReport, boolean eventNotifications, boolean active, String by) {
		Recipient recipient = requireRecipient(companyId, recipientId);
		boolean enabling = (weeklyReport && !recipient.weeklyReport()) || (eventNotifications && !recipient.eventNotifications());
		if (enabling && (!"GRANTED".equals(recipient.consentStatus()) || recipient.verifiedAt() == null)) {
			throw ApiException.badRequest("Primero registra la autorización del destinatario y verifica su contacto de ManyChat.");
		}
		jdbc.sql("""
				UPDATE property_recipients SET "weeklyReport" = :w, "eventNotifications" = :e, active = :a, "updatedAt" = :now WHERE id = :id
				""").param("w", weeklyReport).param("e", eventNotifications).param("a", active).param("now", Db.ts(Instant.now())).param("id", recipientId).update();
	}

	public void recordConsent(String companyId, String recipientId, boolean granted, String evidence, String by) {
		Recipient recipient = requireRecipient(companyId, recipientId);
		String text = evidence == null ? "" : evidence;
		if (granted && text.trim().length() < 10) throw ApiException.badRequest("Describe cómo y cuándo autorizó (mínimo 10 caracteres).");
		if (granted) {
			jdbc.sql("""
					UPDATE property_recipients SET "consentStatus" = 'GRANTED', "consentEvidence" = :ev, "consentAt" = :now, "consentRecordedBy" = :by, "updatedAt" = :now
					WHERE id = :id
					""").param("ev", truncate(text.trim(), 1000)).param("now", Db.ts(Instant.now())).param("by", by).param("id", recipientId).update();
		} else {
			String combined = ((recipient.consentEvidence() == null ? "" : recipient.consentEvidence()) + "\nRevocada por " + by + ": " + text).trim();
			jdbc.sql("""
					UPDATE property_recipients SET "consentStatus" = 'REVOKED', "consentEvidence" = :ev, "weeklyReport" = false, "eventNotifications" = false,
					  "updatedAt" = :now WHERE id = :id
					""").param("ev", truncate(combined, 1000)).param("now", Db.ts(Instant.now())).param("id", recipientId).update();
		}
	}

	/** Comprueba en ManyChat (solo lectura) que el contacto corresponde al teléfono del destinatario. */
	public Map<String, Object> verifyRecipient(String companyId, String recipientId, String by) {
		Recipient recipient = requireRecipient(companyId, recipientId);
		String subscriberId = recipient.subscriberId();
		boolean ok = false;
		String note;
		try {
			ManyChatService.SubscriberInfo info = subscriberId != null ? manyChat.getSubscriberInfo(subscriberId) : manyChat.findSubscriberByPhone(recipient.phoneE164());
			if (info == null) {
				note = subscriberId != null ? "ManyChat no encontró ese contacto."
						: "No hay un contacto de ManyChat con ese teléfono. El destinatario debe escribir primero al WhatsApp de la inmobiliaria.";
			} else {
				subscriberId = info.id();
				ok = List.of(Phone.normalizeE164(info.whatsappPhone()), Phone.normalizeE164(info.phone())).contains(recipient.phoneE164());
				note = ok ? "Verificado: el contacto " + subscriberId + " de ManyChat tiene el teléfono " + recipient.phoneE164() + "."
						: "El contacto " + subscriberId + " de ManyChat tiene otro teléfono; no se verificó.";
			}
		} catch (RuntimeException error) {
			note = "No se pudo consultar ManyChat: " + (error.getMessage() != null ? error.getMessage() : "error desconocido") + ".";
		}
		if (ok) {
			jdbc.sql("""
					UPDATE property_recipients SET "manyChatSubscriberId" = :s, "verifiedAt" = :now, "verifiedBy" = :by, "verificationNote" = :note, "updatedAt" = :now
					WHERE id = :id
					""").param("s", subscriberId).param("now", Db.ts(Instant.now())).param("by", by).param("note", note).param("id", recipientId).update();
		} else {
			jdbc.sql("UPDATE property_recipients SET \"verifiedAt\" = NULL, \"verificationNote\" = :note, \"updatedAt\" = :now WHERE id = :id")
					.param("note", note).param("now", Db.ts(Instant.now())).param("id", recipientId).update();
		}
		return Json.obj("ok", ok, "note", note);
	}

	// --- Actividades -----------------------------------------------------------------------------------

	public record EventInput(String publicId, String type, String status, String title, String description, Instant scheduledAt, Instant completedAt,
			String outcome, boolean notifyRecipients) {
	}

	private static void validateEvent(EventInput input) {
		if ("SCHEDULED".equals(input.status()) && input.scheduledAt() == null) throw ApiException.badRequest("Una actividad programada necesita fecha y hora.");
		if ("DONE".equals(input.status()) && input.completedAt() == null && input.scheduledAt() == null) throw ApiException.badRequest("Indica cuándo se realizó.");
		if (!"DONE".equals(input.status()) && input.outcome() != null) {
			throw ApiException.badRequest("El resumen de lo realizado solo aplica a actividades realizadas.");
		}
	}

	private Event findEvent(String id) {
		return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM property_events WHERE id = :id").param("id", id).query(Event::map).single();
	}

	public Map<String, Object> createEvent(String companyId, EventInput input, String by) {
		validateEvent(input);
		String id = Ids.cuid();
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO property_events (id, "companyId", "publicId", type, status, title, description, "scheduledAt", "completedAt", outcome, origin, version,
				  "notifyRecipients", "createdBy", "updatedBy", "createdAt", "updatedAt")
				VALUES (:id, :c, :p, CAST(:type AS "PropertyEventType"), CAST(:status AS "PropertyEventStatus"), :title, :description, :scheduled, :completed,
				  :outcome, 'panel', 1, :notify, :by, :by, :now, :now)
				""").param("id", id).param("c", companyId).param("p", input.publicId().toUpperCase(Locale.ROOT)).param("type", input.type())
				.param("status", input.status()).param("title", input.title()).param("description", input.description())
				.param("scheduled", Db.ts(input.scheduledAt())).param("completed", Db.ts(input.completedAt())).param("outcome", input.outcome())
				.param("notify", input.notifyRecipients()).param("by", by).param("now", Db.ts(now)).update();
		Event event = findEvent(id);
		return Json.obj("event", eventView(event), "notices", enqueueEventNotices(event));
	}

	/** Sube la versión solo con cambios relevantes (estado, fecha, resumen): una notificación por versión. */
	public Map<String, Object> updateEvent(String companyId, String eventId, EventInput input, String by) {
		validateEvent(input);
		Event current = jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM property_events WHERE id = :id AND \"companyId\" = :c").param("id", eventId)
				.param("c", companyId).query(Event::map).optional().orElseThrow(() -> ApiException.notFound("Actividad no encontrada."));
		boolean relevant = !current.status().equals(input.status()) || !java.util.Objects.equals(current.scheduledAt(), input.scheduledAt())
				|| !java.util.Objects.equals(current.completedAt(), input.completedAt()) || !java.util.Objects.equals(current.outcome(), input.outcome());
		jdbc.sql("""
				UPDATE property_events SET type = CAST(:type AS "PropertyEventType"), status = CAST(:status AS "PropertyEventStatus"), title = :title,
				  description = :description, "scheduledAt" = :scheduled, "completedAt" = :completed, outcome = :outcome, "notifyRecipients" = :notify,
				  "updatedBy" = :by, version = version + :inc, "updatedAt" = :now WHERE id = :id
				""").param("type", input.type()).param("status", input.status()).param("title", input.title()).param("description", input.description())
				.param("scheduled", Db.ts(input.scheduledAt())).param("completed", Db.ts(input.completedAt())).param("outcome", input.outcome())
				.param("notify", input.notifyRecipients()).param("by", by).param("inc", relevant ? 1 : 0).param("now", Db.ts(Instant.now()))
				.param("id", eventId).update();
		Event event = findEvent(eventId);
		return Json.obj("event", eventView(event), "notices", relevant ? enqueueEventNotices(event) : 0);
	}

	/** Avisos de una versión del evento para destinatarios habilitados. Clave event:<id>:v<versión>:<destinatario>. */
	public int enqueueEventNotices(Event event) {
		if (!event.notifyRecipients()) return 0;
		Settings settings = settings(event.companyId());
		if (!settings.eventNotificationsEnabled()) return 0;
		String title = jdbc.sql("SELECT title FROM property_cache WHERE \"companyId\" = :c AND \"publicId\" = :p").param("c", event.companyId())
				.param("p", event.publicId()).query(String.class).optional().orElse(null);
		PropertyReportRules.EventNotice notice = PropertyReportRules.buildEventNotice(event.publicId(), title, event.view());
		int created = 0;
		for (Recipient recipient : recipients("\"companyId\" = :c AND \"publicId\" = :p AND active = true AND \"eventNotifications\" = true",
				Map.of("c", event.companyId(), "p", event.publicId()))) {
			if (!recipient.blockers("event").isEmpty()) continue;
			if (insertDelivery(event.companyId(), recipient.id(), event.publicId(), "EVENT_NOTICE",
					"event:" + event.id() + ":v" + event.version() + ":" + recipient.id(), null, null, event.id(),
					Json.obj("variables", notice.variables(), "eventVersion", event.version()), notice.text(), null, Ids.cuid())) {
				created++;
			}
		}
		return created;
	}
}

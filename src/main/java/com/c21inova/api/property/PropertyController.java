package com.c21inova.api.property;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.MexicoTime;
import com.c21inova.api.report.DateRange;

/**
 * Propiedades: métricas para ADMIN, DIRECCION y CONSULTA; destinatarios y
 * actividades para ADMIN y DIRECCION; configuración y envíos de prueba solo ADMIN.
 */
@RestController
@RequestMapping("/api/properties")
public class PropertyController {

	private static final Pattern PUBLIC_ID = Pattern.compile("^EB-[A-Z]{2}\\d{3,6}$", Pattern.CASE_INSENSITIVE);
	private static final Pattern LOCAL_DATE_TIME = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})$");
	private static final List<String> EVENT_TYPES = List.of("SHOWING", "OPEN_HOUSE", "APPOINTMENT", "OFFER", "PRICE_UPDATE", "MARKETING", "OTHER");
	private static final List<String> EVENT_STATUSES = List.of("SCHEDULED", "DONE", "CANCELLED");

	private final PropertyReportService reports;

	public PropertyController(PropertyReportService reports) {
		this.reports = reports;
	}

	private static SessionUser viewer() {
		return SessionUser.requireRole("ADMIN", "DIRECCION", "CONSULTA");
	}

	private static SessionUser manager() {
		return SessionUser.requireRole("ADMIN", "DIRECCION");
	}

	private static String[] keys(String range, String from, String to) {
		try {
			DateRange.Resolved resolved = DateRange.fromParams(range == null || range.isEmpty() ? "this_week" : range, from, to);
			return new String[] { MexicoTime.dateKey(resolved.startDate()), MexicoTime.dateKey(resolved.endDate()), resolved.label() };
		} catch (DateRange.InvalidDateRangeException error) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", error.getMessage());
		}
	}

	private static String publicId(String raw) {
		if (raw == null || !PUBLIC_ID.matcher(raw.trim()).matches()) throw ApiException.unprocessable("Código de propiedad inválido.");
		return raw.trim().toUpperCase(Locale.ROOT);
	}

	@GetMapping
	public Map<String, Object> list(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String q) {
		SessionUser user = viewer();
		String[] k = keys(range, from, to);
		return Json.obj("ok", true, "startKey", k[0], "endKey", k[1], "label", k[2], "dataSince", reports.dataSinceKey(user.companyId()), "properties",
				reports.listReport(user.companyId(), k[0], k[1], q));
	}

	@GetMapping("/{publicId}")
	public Map<String, Object> detail(@PathVariable String publicId, @RequestParam(required = false) String range,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
		SessionUser user = viewer();
		String[] k = keys(range, from, to);
		Map<String, Object> detail = reports.detail(user.companyId(), publicId(publicId), k[0], k[1]);
		// CONSULTA solo ve métricas: sin destinatarios (datos del propietario).
		if ("CONSULTA".equals(user.role())) detail.put("recipients", List.of());
		Map<String, Object> out = Json.obj("ok", true, "startKey", k[0], "endKey", k[1], "label", k[2], "dataSince", reports.dataSinceKey(user.companyId()));
		out.putAll(detail);
		return out;
	}

	/** Vista previa del reporte semanal tal como se enviaría (periodo vigente). */
	@GetMapping("/{publicId}/weekly-preview")
	public Map<String, Object> weeklyPreview(@PathVariable String publicId) {
		SessionUser user = manager();
		PropertyReportService.Settings settings = reports.settings(user.companyId());
		PropertyReportRules.WeeklyPeriod period = PropertyReportRules.weeklyPeriodFor(Instant.now(), settings.weeklyWeekday());
		PropertyReportRules.WeeklyReport report = reports.buildWeekly(user.companyId(), publicId(publicId), period);
		return Json.obj("ok", true, "period", period, "variables", report.variables(), "text", report.text(), "blockers", reports.settingsBlockers(settings, "weekly"));
	}

	// --- Destinatarios ---------------------------------------------------------------------------

	public record RecipientRequest(String publicId, String name, String phone, String relation, String manyChatSubscriberId, String notes,
			Boolean confirmNotProspect) {
	}

	@PostMapping("/recipients")
	public ResponseEntity<Map<String, Object>> createRecipient(@RequestBody RecipientRequest body) {
		SessionUser user = manager();
		if (body == null) throw ApiException.badRequest("Cuerpo JSON inválido.");
		String name = body.name() == null ? "" : body.name().trim();
		if (name.length() < 2 || name.length() > 120) throw ApiException.unprocessable("Indica el nombre.");
		if (body.phone() == null || body.phone().trim().length() < 8) throw ApiException.unprocessable("Indica el WhatsApp.");
		if (body.relation() == null || !PropertyReportService.RECIPIENT_RELATIONS.contains(body.relation())) throw ApiException.unprocessable("Relación inválida.");
		String subscriber = body.manyChatSubscriberId() == null ? "" : body.manyChatSubscriberId().trim();
		if (!subscriber.matches("^\\d{0,20}$")) throw ApiException.unprocessable("El ID de ManyChat son solo dígitos.");
		String notes = body.notes() == null || body.notes().trim().isEmpty() ? null : body.notes().trim();
		if (notes != null && notes.length() > 500) throw ApiException.unprocessable("Máximo 500 caracteres.");
		reports.createRecipient(user.companyId(), publicId(body.publicId()), name, body.phone().trim(), body.relation(), subscriber.isEmpty() ? null : subscriber,
				notes, Boolean.TRUE.equals(body.confirmNotProspect()), user.email());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Json.obj("ok", true, "message", "Destinatario agregado. Registra su autorización y verifícalo antes de habilitar envíos."));
	}

	public record ConsentRequest(String decision, String evidence) {
	}

	@PostMapping("/recipients/{recipientId}/consent")
	public Map<String, Object> consent(@PathVariable String recipientId, @RequestBody ConsentRequest body) {
		SessionUser user = manager();
		boolean granted = body == null || !"revoke".equals(body.decision());
		reports.recordConsent(user.companyId(), recipientId, granted, body == null ? "" : body.evidence(), user.email());
		return Json.obj("ok", true, "message", granted ? "Autorización registrada." : "Autorización revocada; envíos desactivados.");
	}

	@PostMapping("/recipients/{recipientId}/verify")
	public Map<String, Object> verify(@PathVariable String recipientId) {
		SessionUser user = manager();
		return reports.verifyRecipient(user.companyId(), recipientId, user.email());
	}

	public record FlagsRequest(Boolean weeklyReport, Boolean eventNotifications, Boolean active) {
	}

	@PatchMapping("/recipients/{recipientId}")
	public Map<String, Object> flags(@PathVariable String recipientId, @RequestBody FlagsRequest body) {
		SessionUser user = manager();
		if (body == null) throw ApiException.badRequest("Cuerpo JSON inválido.");
		reports.updateRecipientFlags(user.companyId(), recipientId, Boolean.TRUE.equals(body.weeklyReport()), Boolean.TRUE.equals(body.eventNotifications()),
				Boolean.TRUE.equals(body.active()), user.email());
		return Json.obj("ok", true, "message", "Preferencias guardadas.");
	}

	@PostMapping("/recipients/{recipientId}/test")
	public Map<String, Object> sendTest(@PathVariable String recipientId) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		String status = reports.sendTest(user.companyId(), recipientId, user.email());
		return "SENT".equals(status) ? Json.obj("ok", true, "status", status, "message", "Prueba enviada (ManyChat la aceptó).")
				: Json.obj("ok", false, "status", status, "message", "La prueba quedó como " + status + ". Revisa el historial de envíos.");
	}

	// --- Actividades ---------------------------------------------------------------------------------

	public record EventRequest(String publicId, String type, String status, String title, String description, String scheduledAt, String completedAt,
			String outcome, Boolean notifyRecipients) {
	}

	/** "2026-09-27T11:00" capturado en el panel = hora de Ciudad de México. */
	private static Instant localDateTime(String value) {
		if (value == null || value.trim().isEmpty()) return null;
		Matcher m = LOCAL_DATE_TIME.matcher(value.trim());
		if (!m.matches()) throw ApiException.unprocessable("Fecha y hora inválidas.");
		return MexicoTime.wallTimeToUtc(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
				Integer.parseInt(m.group(5)));
	}

	private static String optional(String value, int max) {
		if (value == null || value.trim().isEmpty()) return null;
		String trimmed = value.trim();
		if (trimmed.length() > max) throw ApiException.unprocessable("Máximo " + max + " caracteres.");
		return trimmed;
	}

	private static PropertyReportService.EventInput eventInput(EventRequest body) {
		if (body == null) throw ApiException.badRequest("Cuerpo JSON inválido.");
		if (body.type() == null || !EVENT_TYPES.contains(body.type())) throw ApiException.unprocessable("Tipo de actividad inválido.");
		if (body.status() == null || !EVENT_STATUSES.contains(body.status())) throw ApiException.unprocessable("Estado de actividad inválido.");
		String title = body.title() == null ? "" : body.title().trim();
		if (title.length() < 3 || title.length() > 160) throw ApiException.unprocessable("Indica un título.");
		return new PropertyReportService.EventInput(publicId(body.publicId()), body.type(), body.status(), title, optional(body.description(), 1000),
				localDateTime(body.scheduledAt()), localDateTime(body.completedAt()), "DONE".equals(body.status()) ? optional(body.outcome(), 1500) : null,
				Boolean.TRUE.equals(body.notifyRecipients()));
	}

	private static Map<String, Object> eventResult(Map<String, Object> result) {
		int notices = (Integer) result.get("notices");
		Map<String, Object> out = new LinkedHashMap<>(Json.obj("ok", true, "message",
				notices > 0 ? "Actividad guardada; " + notices + " aviso(s) en cola." : "Actividad guardada (sin avisos en cola)."));
		out.putAll(result);
		return out;
	}

	@PostMapping("/events")
	public ResponseEntity<Map<String, Object>> createEvent(@RequestBody EventRequest body) {
		SessionUser user = manager();
		return ResponseEntity.status(HttpStatus.CREATED).body(eventResult(reports.createEvent(user.companyId(), eventInput(body), user.email())));
	}

	@PutMapping("/events/{eventId}")
	public Map<String, Object> updateEvent(@PathVariable String eventId, @RequestBody EventRequest body) {
		SessionUser user = manager();
		return eventResult(reports.updateEvent(user.companyId(), eventId, eventInput(body), user.email()));
	}

	// --- Configuración (ADMIN) ---------------------------------------------------------------------------

	@GetMapping("/settings")
	public Map<String, Object> settings() {
		SessionUser user = SessionUser.requireRole("ADMIN");
		PropertyReportService.Settings settings = reports.settings(user.companyId());
		return Json.obj("ok", true, "settings", settings.toView(), "blockers", Json.obj("weekly", reports.settingsBlockers(settings, "weekly"), "event",
				reports.settingsBlockers(settings, "event"), "test", reports.settingsBlockers(settings, "test")), "templates", PropertyReportRules.OWNER_TEMPLATES,
				"nextPeriod", PropertyReportRules.weeklyPeriodFor(Instant.now(), settings.weeklyWeekday()));
	}

	public record SettingsRequest(Boolean weeklyEnabled, Integer weeklyHour, Integer weeklyMinute, Boolean eventNotificationsEnabled,
			String manyChatWeeklyFlowNs, String manyChatEventFlowNs, Map<String, Object> weeklyFieldIds, Map<String, Object> eventFieldIds,
			String templateNote) {
	}

	private static Map<String, Integer> fieldIds(Map<String, Object> raw, List<String> keys, String prefix) {
		if (raw == null || raw.values().stream().allMatch(v -> v == null || String.valueOf(v).trim().isEmpty())) return null;
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String key : keys) {
			Object value = raw.get(key);
			try {
				int id = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
				if (id <= 0) throw new NumberFormatException();
				out.put(key, id);
			} catch (NumberFormatException e) {
				throw ApiException.unprocessable("Campo de ManyChat inválido: " + prefix + "." + key + " (ID numérico).");
			}
		}
		return out;
	}

	@PutMapping("/settings")
	public Map<String, Object> saveSettings(@RequestBody SettingsRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		if (body == null) throw ApiException.badRequest("Cuerpo JSON inválido.");
		if (body.weeklyHour() != null && (body.weeklyHour() < 0 || body.weeklyHour() > 23)) throw ApiException.unprocessable("Hora inválida.");
		int minute = body.weeklyMinute() == null ? 0 : body.weeklyMinute();
		if (minute < 0 || minute > 59) throw ApiException.unprocessable("Minuto inválido.");
		PropertyReportService.Settings saved = reports.updateSettings(user.companyId(),
				new PropertyReportService.SettingsUpdate(Boolean.TRUE.equals(body.weeklyEnabled()), body.weeklyHour(), minute,
						Boolean.TRUE.equals(body.eventNotificationsEnabled()), optional(body.manyChatWeeklyFlowNs(), 80), optional(body.manyChatEventFlowNs(), 80),
						fieldIds(body.weeklyFieldIds(), PropertyReportService.WEEKLY_FIELDS, "weekly"),
						fieldIds(body.eventFieldIds(), PropertyReportService.EVENT_FIELDS, "event"), optional(body.templateNote(), 500)),
				user.email());
		return Json.obj("ok", true, "message", "Configuración guardada.", "settings", saved.toView());
	}
}

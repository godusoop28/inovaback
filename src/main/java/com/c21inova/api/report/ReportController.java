package com.c21inova.api.report;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Csv;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.MexicoTime;
import com.c21inova.api.lead.LeadViewService;
import com.c21inova.api.property.PropertyReportRules;
import com.c21inova.api.property.PropertyReportService;

/**
 * Tablero, reportes y exportaciones: los mismos roles que pueden ver
 * /reportes (CONSULTA incluido). Todos comparten el mismo cálculo.
 */
@RestController
public class ReportController {

	/** Tope de filas en una exportación (la paginación en pantalla no tiene tope). */
	public static final int EXPORT_LEAD_CAP = 2000;
	private static final List<String> HEADER = List.of("Fecha", "Cliente", "Teléfono", "Ruta", "Origen", "Asesor", "Estado", "EasyBroker", "ManyChat");

	private final ReportDataService reports;
	private final LeadViewService leads;
	private final PropertyReportService propertyReports;

	public ReportController(ReportDataService reports, LeadViewService leads, PropertyReportService propertyReports) {
		this.reports = reports;
		this.leads = leads;
		this.propertyReports = propertyReports;
	}

	private static SessionUser access() {
		return SessionUser.requireRole("ADMIN", "DIRECCION", "CONSULTA");
	}

	private static DateRange.Resolved range(String range, String from, String to) {
		try {
			return DateRange.fromParams(range, from, to);
		} catch (DateRange.InvalidDateRangeException error) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", error.getMessage());
		}
	}

	private static ReportDataService.Filters filters(String ruta, String origen, String asesor) {
		String route = ruta != null && ReportDataService.ALL_CANONICAL_ROUTES.contains(ruta) ? ruta : null;
		return new ReportDataService.Filters(route == null ? null : ReportDataService.canonicalRouteToInterestTypes(route), blank(origen), blank(asesor));
	}

	private static String blank(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	/** Indicadores del periodo (y del periodo anterior para comparar). Filtros: ruta, origen, asesor. */
	@GetMapping("/api/reports")
	public Map<String, Object> report(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String ruta, @RequestParam(required = false) String origen,
			@RequestParam(required = false) String asesor, @RequestParam(defaultValue = "false") boolean compare) {
		SessionUser user = access();
		DateRange.Resolved resolved = range(range, from, to);
		ReportDataService.Filters f = filters(ruta, origen, asesor);
		Map<String, Object> current = reports.reportData(user.companyId(), resolved.startDate(), resolved.endDate(), f);
		Map<String, Object> out = Json.obj("ok", true, "range", Json.obj("preset", resolved.preset(), "label", resolved.label(), "startDate",
				resolved.startDate(), "endDate", resolved.endDate()), "report", current);
		if (compare) {
			Instant[] previous = DateRange.previous(resolved.startDate(), resolved.endDate());
			out.put("previous", reports.reportData(user.companyId(), previous[0], previous[1], f));
		}
		return out;
	}

	/** Tablero: indicadores del periodo + leads recientes. */
	@GetMapping("/api/dashboard")
	public Map<String, Object> dashboard(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(defaultValue = "8") int recent) {
		SessionUser user = access();
		DateRange.Resolved resolved = range(range, from, to);
		Map<String, Object> report = reports.reportData(user.companyId(), resolved.startDate(), resolved.endDate(), ReportDataService.Filters.NONE);
		LeadViewService.Page page = leads.list(new LeadViewService.Filters(user.companyId(), null, null, null, null, resolved.startDate(),
				resolved.endDate(), null), 1, Math.min(Math.max(recent, 1), 50));
		return Json.obj("ok", true, "range", Json.obj("preset", resolved.preset(), "label", resolved.label(), "startDate", resolved.startDate(), "endDate",
				resolved.endDate()), "report", report, "recentLeads", page.leads());
	}

	private static ResponseEntity<byte[]> csv(String body, String filename) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.body(body.getBytes(StandardCharsets.UTF_8));
	}

	/** CSV de leads del periodo con los mismos filtros de /reportes (más estado y búsqueda). */
	@GetMapping("/api/reports/csv")
	public ResponseEntity<byte[]> leadsCsv(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String ruta, @RequestParam(required = false) String origen,
			@RequestParam(required = false) String asesor, @RequestParam(required = false) String estado, @RequestParam(required = false) String q) {
		SessionUser user = access();
		DateRange.Resolved resolved = range(range, from, to);
		ReportDataService.Filters f = filters(ruta, origen, asesor);
		LeadViewService.Page page = leads.list(new LeadViewService.Filters(user.companyId(), blank(estado), f.advisorId(), f.interestTypes(), f.origin(),
				resolved.startDate(), resolved.endDate(), blank(q)), 1, EXPORT_LEAD_CAP);
		List<List<String>> rows = new ArrayList<>();
		rows.add(HEADER);
		for (Map<String, Object> lead : page.leads()) {
			String asesorName = (String) lead.get("asesorAsignado");
			rows.add(List.of(MexicoTime.formatDateTime(Instant.parse((String) lead.get("fechaHora"))), (String) lead.get("nombre"), (String) lead.get("telefono"),
					ReportDataService.ROUTE_LABELS.get(ReportDataService.toCanonicalRoute((String) lead.get("interestType"))), (String) lead.get("origen"),
					asesorName == null || asesorName.isEmpty() ? "Sin asignar" : asesorName, (String) lead.get("estado"),
					Boolean.TRUE.equals(lead.get("easyBrokerConfirmado")) ? "Confirmado" : "Pendiente",
					Boolean.TRUE.equals(lead.get("manyChatNotificado")) ? "Enviada" : "Pendiente"));
		}
		String filename = "inova-reporte-" + MexicoTime.dateKey(resolved.startDate()) + "-a-" + MexicoTime.dateKey(resolved.endDate()) + ".csv";
		return csv(Csv.UTF8_BOM + Csv.toCsv(rows), filename);
	}

	private static String sources(Object value) {
		@SuppressWarnings("unchecked")
		List<PropertyReportRules.SourceCount> list = (List<PropertyReportRules.SourceCount>) value;
		String joined = String.join("; ", list.stream().map(s -> s.label() + ": " + s.contacts()).toList());
		return joined.isEmpty() ? PropertyReportRules.NOT_IDENTIFIED : joined;
	}

	/** CSV por propiedad, sin datos personales (por defecto esta semana). */
	@GetMapping("/api/reports/properties/csv")
	public ResponseEntity<byte[]> propertiesCsv(@RequestParam(required = false) String range, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String q) {
		SessionUser user = access();
		DateRange.Resolved resolved = range(range == null || range.isEmpty() ? "this_week" : range, from, to);
		String startKey = MexicoTime.dateKey(resolved.startDate());
		String endKey = MexicoTime.dateKey(resolved.endDate());
		String since = propertyReports.dataSinceKey(user.companyId());
		List<List<String>> rows = new ArrayList<>();
		rows.add(List.of("Periodo " + startKey + " a " + endKey + (since != null ? "; datos desde " + since : "")));
		rows.add(List.of("Código", "Propiedad", "Leads únicos del periodo", "Acumulado", "Consultas", "Mensajes", "Portal del enlace", "Fuente declarada",
				"Fuente de adquisición", "Actividades del periodo", "Última actualización"));
		for (Map<String, Object> row : propertyReports.listReport(user.companyId(), startKey, endKey, q)) {
			Instant last = (Instant) row.get("lastActivityAt");
			rows.add(List.of((String) row.get("publicId"), row.get("title") == null ? "" : (String) row.get("title"), String.valueOf(row.get("periodLeads")),
					String.valueOf(row.get("cumulativeLeads")), String.valueOf(row.get("periodInquiries")), String.valueOf(row.get("periodMessages")),
					sources(row.get("linkPortals")), sources(row.get("declared")), sources(row.get("acquisition")), String.valueOf(row.get("periodEvents")),
					last == null ? "" : MexicoTime.formatDateTime(last)));
		}
		return csv(Csv.UTF8_BOM + Csv.toCsv(rows), "propiedades_" + startKey + "_" + endKey + ".csv");
	}
}

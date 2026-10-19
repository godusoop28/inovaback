package com.c21inova.api.lead;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Json;
import com.c21inova.api.report.DateRange;
import com.c21inova.api.report.ReportDataService;

/** Leads: ADMIN y DIRECCION. */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

	private static final List<String> STATUSES = List.of("RECEIVED", "PROCESSING", "CREATED_IN_EASYBROKER", "ASSIGNED", "NOTIFIED", "COMPLETED", "FAILED");

	private final LeadViewService views;
	private final LeadManagementService management;
	private final LeadRepository leads;

	public LeadController(LeadViewService views, LeadManagementService management, LeadRepository leads) {
		this.views = views;
		this.management = management;
		this.leads = leads;
	}

	private static SessionUser access() {
		return SessionUser.requireRole("ADMIN", "DIRECCION");
	}

	/**
	 * Filtros: estado (o "pending"), asesor, ruta canónica (PROPERTY…OTHER),
	 * origen, q (nombre/teléfono) y periodo (range/from/to; sin range = sin límite de fecha).
	 */
	@GetMapping
	public Map<String, Object> list(@RequestParam(required = false) String estado, @RequestParam(required = false) String asesor,
			@RequestParam(required = false) String ruta, @RequestParam(required = false) String origen, @RequestParam(required = false) String q,
			@RequestParam(required = false) String range, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int limit) {
		SessionUser user = access();
		Instant start = null;
		Instant end = null;
		if (range != null && !range.isEmpty()) {
			try {
				DateRange.Resolved resolved = DateRange.fromParams(range, from, to);
				start = resolved.startDate();
				end = resolved.endDate();
			} catch (DateRange.InvalidDateRangeException error) {
				throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", error.getMessage());
			}
		}
		List<String> interestTypes = ruta != null && ReportDataService.ALL_CANONICAL_ROUTES.contains(ruta) ? ReportDataService.canonicalRouteToInterestTypes(ruta)
				: null;
		LeadViewService.Page result = views.list(new LeadViewService.Filters(user.companyId(), blankToNull(estado), blankToNull(asesor), interestTypes,
				blankToNull(origen), start, end, blankToNull(q)), Math.max(1, page), Math.min(Math.max(1, limit), 200));
		return Json.obj("ok", true, "leads", result.leads(), "total", result.total(), "page", result.page(), "limit", result.limit());
	}

	private static String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	@GetMapping("/counts")
	public Map<String, Object> counts() {
		SessionUser user = access();
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(views.statusCounts(user.companyId()));
		return out;
	}

	@GetMapping("/filters")
	public Map<String, Object> filters() {
		SessionUser user = access();
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(views.filterOptions(user.companyId()));
		return out;
	}

	@GetMapping("/property-titles")
	public Map<String, Object> propertyTitles(@RequestParam List<String> ids) {
		SessionUser user = access();
		return Json.obj("ok", true, "titles", views.propertyTitles(user.companyId(), ids));
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable String id) {
		SessionUser user = access();
		Map<String, Object> detail = views.detail(user.companyId(), id);
		if (detail == null) throw ApiException.notFound("Lead no encontrado.");
		Map<String, Object> out = Json.obj("ok", true);
		out.putAll(detail);
		return out;
	}

	public record StatusRequest(String status) {
	}

	@PatchMapping("/{id}")
	public Map<String, Object> changeStatus(@PathVariable String id, @RequestBody StatusRequest body) {
		SessionUser user = access();
		if (body == null || body.status() == null) throw ApiException.unprocessable("status es obligatorio.");
		if (!STATUSES.contains(body.status())) throw ApiException.unprocessable("Estado inválido.");
		requireOwned(user, id);
		Lead lead = management.updateStatus(id, body.status());
		return Json.obj("ok", true, "lead", Json.obj("id", lead.id(), "status", lead.status(), "statusLabel", LeadRules.statusLabel(lead.status())));
	}

	public record ReassignRequest(String advisorId, String reason) {
	}

	@PostMapping("/{id}/reassign")
	public Map<String, Object> reassign(@PathVariable String id, @RequestBody ReassignRequest body) {
		SessionUser user = access();
		if (body == null || body.advisorId() == null || body.advisorId().trim().isEmpty()) throw ApiException.unprocessable("Selecciona un asesor.");
		if (body.reason() != null && body.reason().length() > 500) throw ApiException.unprocessable("Máximo 500 caracteres.");
		requireOwned(user, id);
		LeadManagementService.ReassignResult result = management.reassign(id, body.advisorId().trim(),
				body.reason() == null || body.reason().trim().isEmpty() ? null : body.reason().trim());
		if (!result.ok()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "REASSIGN_FAILED", result.error());
		return Json.obj("ok", true, "easyBrokerUpdated", result.easyBrokerUpdated(), "manyChatNotified", result.manyChatNotified());
	}

	private void requireOwned(SessionUser user, String id) {
		Lead lead = leads.findById(id).orElse(null);
		if (lead == null || !lead.companyId().equals(user.companyId())) throw ApiException.notFound("Lead no encontrado.");
	}
}

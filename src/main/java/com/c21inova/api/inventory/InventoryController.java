package com.c21inova.api.inventory;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.Json;
import com.c21inova.api.webhook.IntegrationSecrets;

import jakarta.servlet.http.HttpServletRequest;

/** Inventario EasyBroker → CRM → 21Online. */
@RestController
public class InventoryController {

	private final InventorySyncService sync;
	private final InventoryJobs jobs;
	private final IntegrationSecrets secrets;

	public InventoryController(InventorySyncService sync, InventoryJobs jobs, IntegrationSecrets secrets) {
		this.sync = sync;
		this.jobs = jobs;
		this.secrets = secrets;
	}

	@GetMapping("/api/inventory")
	public Map<String, Object> list(@RequestParam(defaultValue = "false") boolean deleted) {
		SessionUser user = SessionUser.requireRole("ADMIN", "DIRECCION", "CONSULTA");
		return Json.obj("ok", true, "properties", sync.list(user.companyId(), deleted));
	}

	@GetMapping("/api/inventory/status")
	public Map<String, Object> status() {
		SessionUser user = SessionUser.requireRole("ADMIN", "DIRECCION", "CONSULTA");
		Map<String, Object> state = sync.state(user.companyId());
		state.put("enabled", jobs.enabled());
		state.put("ok", true);
		return state;
	}

	/** Pide una corrida; el proceso la toma en menos de un minuto. */
	@PostMapping("/api/inventory/sync")
	public Map<String, Object> requestSync() {
		SessionUser user = SessionUser.requireRole("ADMIN");
		sync.request(user.companyId(), user.email());
		return Json.obj("ok", true, "requested", true);
	}

	/** Vuelve a crear en EasyBroker una propiedad eliminada, con la copia guardada. */
	@PostMapping("/api/inventory/{publicId}/restore")
	public Map<String, Object> restore(@PathVariable String publicId) {
		SessionUser user = SessionUser.requireRole("ADMIN", "DIRECCION");
		sync.requestRestore(user.companyId(), publicId, user.email());
		return Json.obj("ok", true, "requested", true);
	}

	@RequestMapping(path = "/api/cron/property-sync", method = { RequestMethod.GET, RequestMethod.POST })
	public ResponseEntity<Map<String, Object>> cron(HttpServletRequest request) {
		ResponseEntity<Map<String, Object>> denied = secrets.checkCron(request);
		if (denied != null) return denied;
		return ResponseEntity.ok(jobs.runNow("cron"));
	}
}

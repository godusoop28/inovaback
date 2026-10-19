package com.c21inova.api.cron;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.webhook.IntegrationSecrets;

import jakarta.servlet.http.HttpServletRequest;

/** Mismas rutas que los crons de Vercel; GET o POST con CRON_SECRET. */
@RestController
@RequestMapping("/api/cron")
public class CronController {

	private final CronJobs jobs;
	private final IntegrationSecrets secrets;

	public CronController(CronJobs jobs, IntegrationSecrets secrets) {
		this.jobs = jobs;
		this.secrets = secrets;
	}

	private ResponseEntity<Map<String, Object>> run(HttpServletRequest request, Supplier<Map<String, Object>> task) {
		ResponseEntity<Map<String, Object>> denied = secrets.checkCron(request);
		if (denied != null) return denied;
		return ResponseEntity.ok(task.get());
	}

	@RequestMapping(path = "/retry-integrations", method = { RequestMethod.GET, RequestMethod.POST })
	public ResponseEntity<Map<String, Object>> retryIntegrations(HttpServletRequest request) {
		return run(request, jobs::retryIntegrations);
	}

	@RequestMapping(path = "/conversations", method = { RequestMethod.GET, RequestMethod.POST })
	public ResponseEntity<Map<String, Object>> conversations(HttpServletRequest request) {
		return run(request, jobs::conversations);
	}

	@RequestMapping(path = "/property-reports", method = { RequestMethod.GET, RequestMethod.POST })
	public ResponseEntity<Map<String, Object>> propertyReports(HttpServletRequest request) {
		return run(request, jobs::propertyReports);
	}
}

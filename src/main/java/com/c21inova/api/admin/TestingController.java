package com.c21inova.api.admin;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.lead.LeadRules;
import com.c21inova.api.lead.LeadService;

/**
 * Diagnóstico interno (ENABLE_INTERNAL_TESTING=true): corre el mismo pipeline
 * del webhook, siempre en "shadow": nunca llama a EasyBroker ni ManyChat.
 */
@RestController
public class TestingController {

	public record SimulateLeadRequest(String nombre, String telefono, String interes, String datosPropiedad, String origen) {
	}

	private final Env env;
	private final LeadService leads;

	public TestingController(Env env, LeadService leads) {
		this.env = env;
		this.leads = leads;
	}

	@PostMapping("/api/testing/simulate-lead")
	public Map<String, Object> simulateLead(@RequestBody SimulateLeadRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		if (!env.internalTestingEnabled()) throw ApiException.forbidden("El diagnóstico interno está desactivado en este entorno.");
		String nombre = body == null || body.nombre() == null ? "" : body.nombre().trim();
		String telefono = body == null || body.telefono() == null ? "" : body.telefono().trim();
		String interes = body == null || body.interes() == null ? "" : body.interes().trim();
		if (nombre.isEmpty() || telefono.isEmpty() || interes.isEmpty()) throw ApiException.unprocessable("Nombre, teléfono e interés son obligatorios.");
		String datos = body.datosPropiedad() == null ? "" : body.datosPropiedad().trim();
		String origen = body.origen() == null || body.origen().trim().isEmpty() ? "Prueba manual" : body.origen().trim();
		// Único por envío: quien prueba quiere una simulación nueva cada vez.
		String requestId = "test:" + LeadRules.fingerprint(telefono, interes, datos, origen, Instant.now()) + ":" + System.currentTimeMillis();
		LeadService.Result result = leads.processIncomingLead(user.companyId(),
				new LeadService.IncomingLead(nombre, telefono, interes, datos, origen, null, requestId, null, null, null), "testing_ui", requestId, "shadow", null);
		return Json.obj("ok", true, "result", result.toJson());
	}
}

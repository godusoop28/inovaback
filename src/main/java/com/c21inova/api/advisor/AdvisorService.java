package com.c21inova.api.advisor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.AuditService;

@Service
public class AdvisorService {

	/** Datos del formulario del panel (mismos nombres que AdvisorInputSchema). */
	public record AdvisorInput(String nombre, String whatsapp, String emailEasyBroker, String manyChatId, boolean activo, int peso,
			List<String> rutasPermitidas, Integer limiteDiario, String observaciones) {
	}

	private final AdvisorRepository repository;
	private final AuditService audit;

	public AdvisorService(AdvisorRepository repository, AuditService audit) {
		this.repository = repository;
		this.audit = audit;
	}

	public List<Map<String, Object>> listViews(String companyId) {
		return repository.findAll(companyId).stream().map(AdvisorRules::toView).toList();
	}

	public Advisor require(String companyId, String id) {
		Advisor advisor = repository.findById(id).orElse(null);
		if (advisor == null || !advisor.companyId().equals(companyId)) throw ApiException.notFound("Asesor no encontrado.");
		return advisor;
	}

	private static AdvisorRepository.Upsert toUpsert(AdvisorInput input) {
		AdvisorRules.AllowedFlags flags = AdvisorRules.routeLabelsToAllowedFlags(input.rutasPermitidas());
		return new AdvisorRepository.Upsert(input.nombre(), input.whatsapp(),
				input.emailEasyBroker() == null || input.emailEasyBroker().isEmpty() ? null : input.emailEasyBroker(),
				input.manyChatId() == null || input.manyChatId().isEmpty() ? null : input.manyChatId(), input.activo(), input.peso(), input.limiteDiario(),
				input.observaciones() == null || input.observaciones().isEmpty() ? null : input.observaciones(), flags.allowedProperty(),
				flags.allowedExplore(), flags.allowedCampaign(), flags.allowedTimeout());
	}

	public Advisor create(String companyId, AdvisorInput input) {
		Advisor advisor = repository.create(companyId, toUpsert(input));
		audit.log(companyId, null, advisor.id(), "ADVISOR_CREATED", "ok", "Asesor creado: " + advisor.name() + ".");
		return advisor;
	}

	public Advisor update(String companyId, String id, AdvisorInput input) {
		require(companyId, id);
		Advisor advisor = repository.update(id, toUpsert(input));
		audit.log(null, null, advisor.id(), "ADVISOR_UPDATED", "ok", "Asesor actualizado: " + advisor.name() + ".");
		return advisor;
	}

	public Advisor setActive(String companyId, String id, boolean active) {
		require(companyId, id);
		Advisor advisor = repository.setActive(id, active);
		audit.log(null, null, advisor.id(), "ADVISOR_UPDATED", "ok", "Asesor " + advisor.name() + " " + (active ? "activado" : "desactivado") + ".");
		return advisor;
	}

	/** until = null significa pausa indefinida. */
	public Advisor pause(String companyId, String id, Instant until) {
		require(companyId, id);
		Advisor advisor = repository.setPausedUntil(id, until != null ? until : AdvisorRules.indefinitePauseDate());
		audit.log(null, null, advisor.id(), "ADVISOR_PAUSED", "ok", "Asesor " + advisor.name() + " pausado.");
		return advisor;
	}

	public Advisor resume(String companyId, String id) {
		require(companyId, id);
		Advisor advisor = repository.setPausedUntil(id, null);
		audit.log(null, null, advisor.id(), "ADVISOR_RESUMED", "ok", "Asesor " + advisor.name() + " reanudado.");
		return advisor;
	}
}

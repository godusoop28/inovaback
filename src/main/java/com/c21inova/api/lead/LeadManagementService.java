package com.c21inova.api.lead;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.c21inova.api.advisor.Advisor;
import com.c21inova.api.advisor.AdvisorRepository;
import com.c21inova.api.assignment.AssignmentRepository;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.integration.EasyBrokerService;
import com.c21inova.api.integration.ManyChatService;

@Service
public class LeadManagementService {

	public record ReassignResult(boolean ok, boolean easyBrokerUpdated, boolean manyChatNotified, String error) {
	}

	private final LeadRepository leads;
	private final AdvisorRepository advisors;
	private final AssignmentRepository assignments;
	private final EasyBrokerService easyBroker;
	private final ManyChatService manyChat;
	private final AuditService audit;
	private final LeadService leadService;
	private final Env env;

	public LeadManagementService(LeadRepository leads, AdvisorRepository advisors, AssignmentRepository assignments, EasyBrokerService easyBroker,
			ManyChatService manyChat, AuditService audit, LeadService leadService, Env env) {
		this.leads = leads;
		this.advisors = advisors;
		this.assignments = assignments;
		this.easyBroker = easyBroker;
		this.manyChat = manyChat;
		this.audit = audit;
		this.leadService = leadService;
		this.env = env;
	}

	public Lead updateStatus(String leadId, String status) {
		Lead lead = leads.findById(leadId).orElseThrow(() -> ApiException.notFound("Lead no encontrado."));
		leads.update(leadId, "status", status);
		audit.log(lead.companyId(), lead.id(), null, "LEAD_STATUS_CHANGED", "ok", "Estado cambiado a " + status + ".");
		return leads.findById(leadId).orElseThrow();
	}

	/** Mismos campos que escribe el webhook, reconstruidos desde el Lead guardado (ficha desde el índice local). */
	private List<AdvisorNotice.Field> reassignFields(Lead lead) {
		Map<String, Object> raw = Json.asMap(lead.rawPayload());
		String propertyPublicId = null;
		if ("PROPERTY".equals(lead.interestType())) {
			propertyPublicId = lead.propertyData() == null || lead.propertyData().trim().isEmpty() ? null : lead.propertyData().trim();
		} else if ("CAMPAIGN".equals(lead.interestType())) {
			propertyPublicId = LeadRules.extractCampaignPropertyCode(lead.propertyData() == null ? "" : lead.propertyData());
		}
		String interest = Json.str(raw, "interesCliente");
		return LeadService.buildAdvisorLeadFields(lead.name(), lead.phone(), interest == null ? "" : interest, lead.route() == null ? "" : lead.route(),
				propertyPublicId, propertyPublicId != null ? leadService.propertyNoticeFromCache(lead.companyId(), propertyPublicId) : null,
				lead.propertyData(), null);
	}

	/**
	 * Reasignación manual desde el panel: registra la asignación nueva y, en
	 * modo live, intenta actualizar EasyBroker y avisar al nuevo asesor. Nunca
	 * lanza por esas integraciones: se reportan sin bloquear la reasignación.
	 */
	public ReassignResult reassign(String leadId, String advisorId, String reason) {
		Lead lead = leads.findById(leadId).orElseThrow(() -> ApiException.notFound("Lead no encontrado."));
		Advisor advisor = advisors.findById(advisorId).orElse(null);
		if (advisor == null || !advisor.companyId().equals(lead.companyId())) return new ReassignResult(false, false, false, "Asesor no encontrado.");
		if (!advisor.active()) return new ReassignResult(false, false, false, "El asesor está inactivo.");

		assignments.create(leadId, advisorId, "MANUAL", advisor.weight(), "ASSIGNED",
				reason != null && !reason.isEmpty() ? reason : "Reasignación manual desde el panel.");
		leads.update(leadId, Map.of("assignedAdvisorId", advisorId, "assignmentStatus", "ASSIGNED"));
		audit.log(lead.companyId(), leadId, advisorId, "LEAD_REASSIGNED", "ok", "Lead reasignado manualmente a " + advisor.name() + ".");

		boolean easyBrokerUpdated = false;
		boolean manyChatNotified = false;
		if (env.isLiveAutomation()) {
			if (lead.easyBrokerContactId() != null && advisor.easyBrokerEmail() != null) {
				try {
					easyBroker.assignContactToAdvisor(lead.easyBrokerContactId(), advisor.easyBrokerEmail());
					easyBrokerUpdated = true;
					audit.log(lead.companyId(), leadId, advisorId, "EASYBROKER_CONTACT_ASSIGNED", "ok", "EasyBroker actualizado tras reasignación manual.");
				} catch (RuntimeException error) {
					audit.log(lead.companyId(), leadId, advisorId, "EASYBROKER_ASSIGNMENT_FAILED", "error",
							error instanceof com.c21inova.api.integration.EasyBrokerClient.EasyBrokerApiException ? error.getMessage()
									: "Error desconocido al actualizar EasyBroker.");
				}
			}
			if (advisor.manyChatSubscriberId() != null) {
				try {
					// El flujo lee los Custom Fields del asesor: se reescriben con ESTE lead.
					manyChat.notifyAdvisor(advisor.manyChatSubscriberId(), null, reassignFields(lead));
					manyChatNotified = true;
					audit.log(lead.companyId(), leadId, advisorId, "MANYCHAT_NOTIFICATION_SENT", "ok", "Asesor notificado tras reasignación manual.");
				} catch (RuntimeException error) {
					audit.log(lead.companyId(), leadId, advisorId, "MANYCHAT_NOTIFICATION_FAILED", "error",
							error instanceof com.c21inova.api.integration.ManyChatErrors.ManyChatApiException ? error.getMessage()
									: "Error desconocido al notificar por ManyChat.");
				}
			}
		}
		return new ReassignResult(true, easyBrokerUpdated, manyChatNotified, null);
	}
}

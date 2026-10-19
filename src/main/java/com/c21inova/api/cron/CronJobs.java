package com.c21inova.api.cron;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.c21inova.api.common.CompanyService;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;
import com.c21inova.api.conversation.ConversationProcessor;
import com.c21inova.api.integration.RetryService;
import com.c21inova.api.property.PropertyCatalogService;
import com.c21inova.api.property.PropertyReportService;

/**
 * Las tres tareas periódicas del panel (mismo calendario que vercel.json).
 * Se ejecutan por HTTP con CRON_SECRET (cron externo) y, si
 * SCHEDULER_ENABLED=true, también dentro del propio proceso. Un candado por
 * tarea evita dos corridas simultáneas en esta instancia; además cada tarea
 * es segura ante ejecuciones paralelas por sus propios leases en la base.
 */
@Component
public class CronJobs {

	private static final Logger log = LoggerFactory.getLogger(CronJobs.class);

	private final RetryService retry;
	private final ConversationProcessor processor;
	private final PropertyCatalogService catalog;
	private final PropertyReportService reports;
	private final CompanyService companies;
	private final Env env;

	private final ReentrantLock retryLock = new ReentrantLock();
	private final ReentrantLock conversationsLock = new ReentrantLock();
	private final ReentrantLock reportsLock = new ReentrantLock();

	public CronJobs(RetryService retry, ConversationProcessor processor, PropertyCatalogService catalog, PropertyReportService reports,
			CompanyService companies, Env env) {
		this.retry = retry;
		this.processor = processor;
		this.catalog = catalog;
		this.reports = reports;
		this.companies = companies;
		this.env = env;
	}

	/** Reintentos de EasyBroker/ManyChat con espera progresiva (1 min, 5 min, 15 min, 1 h). */
	public Map<String, Object> retryIntegrations() {
		if (!retryLock.tryLock()) return Json.obj("ok", true, "skipped", "already_running");
		try {
			RetryService.RunSummary summary = retry.processDue(Instant.now());
			return Json.obj("ok", true, "processed", summary.processed(), "succeeded", summary.succeeded(), "failed", summary.failed(), "rescheduled",
					summary.rescheduled(), "uncertain", summary.uncertain(), "cancelled", summary.cancelled());
		} finally {
			retryLock.unlock();
		}
	}

	/** Retoma conversaciones interrumpidas, reintenta envíos, aplica abandono y refresca el inventario. */
	public Map<String, Object> conversations() {
		if (!conversationsLock.tryLock()) return Json.obj("ok", true, "skipped", "already_running");
		try {
			ConversationProcessor.SweepSummary summary = processor.sweep(90_000);
			Map<String, Object> catalogResult = catalog.ensureFresh(companies.defaultCompanyId());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("conversations", summary.conversations());
			out.put("outboxes", summary.outboxes());
			out.put("abandoned", summary.abandoned());
			out.put("staleSending", summary.staleSending());
			out.put("catalogSynced", catalogResult.get("synced"));
			out.put("catalogError", catalogResult.get("error"));
			return out;
		} finally {
			conversationsLock.unlock();
		}
	}

	/** Reporte semanal a propietarios (si ya toca) y cola de envíos. */
	public Map<String, Object> propertyReports() {
		if (!reportsLock.tryLock()) return Json.obj("ok", true, "skipped", "already_running");
		try {
			Map<String, Object> result = new LinkedHashMap<>(reports.runCron(companies.defaultCompanyId(), Instant.now()));
			result.put("ok", true);
			return result;
		} finally {
			reportsLock.unlock();
		}
	}

	private void scheduled(String name, Runnable task) {
		if (!env.schedulerEnabled()) return;
		try {
			task.run();
		} catch (RuntimeException error) {
			log.error("[CRON] {} falló", name, error);
		}
	}

	@Scheduled(cron = "0 */5 * * * *")
	void scheduledRetry() {
		scheduled("retry-integrations", this::retryIntegrations);
	}

	@Scheduled(cron = "0 * * * * *")
	void scheduledConversations() {
		scheduled("conversations", this::conversations);
	}

	@Scheduled(cron = "0 */15 * * * *")
	void scheduledPropertyReports() {
		scheduled("property-reports", this::propertyReports);
	}
}

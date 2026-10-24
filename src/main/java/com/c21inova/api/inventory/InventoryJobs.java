package com.c21inova.api.inventory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.c21inova.api.common.CompanyService;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;

/**
 * Corre la sincronización del inventario dentro del proceso: revisa cada 30 s
 * si el panel pidió una corrida o una restauración, y si no, corre cada
 * PROPERTY_SYNC_INTERVAL_MINUTES (10 por defecto). PROPERTY_SYNC_ENABLED=false
 * la apaga. Al arrancar aplica el SQL (idempotente) de sus tablas.
 */
@Component
public class InventoryJobs implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InventoryJobs.class);

	private final InventorySyncService sync;
	private final CompanyService companies;
	private final DataSource dataSource;
	private final Env env;
	private final ReentrantLock lock = new ReentrantLock();
	private volatile boolean schemaReady = false;

	public InventoryJobs(InventorySyncService sync, CompanyService companies, DataSource dataSource, Env env) {
		this.sync = sync;
		this.companies = companies;
		this.dataSource = dataSource;
		this.env = env;
	}

	public boolean enabled() {
		return !"false".equals(env.optional("PROPERTY_SYNC_ENABLED")) && env.easyBrokerConfigured();
	}

	Duration interval() {
		String raw = env.optional("PROPERTY_SYNC_INTERVAL_MINUTES");
		try {
			int minutes = raw == null ? 10 : Integer.parseInt(raw.trim());
			return Duration.ofMinutes(Math.max(2, minutes));
		} catch (NumberFormatException e) {
			return Duration.ofMinutes(10);
		}
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureSchema();
	}

	void ensureSchema() {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			String sql = new ClassPathResource("schema/eb_property_sync.sql").getContentAsString(StandardCharsets.UTF_8);
			statement.execute(sql);
			schemaReady = true;
		} catch (Exception error) {
			log.error("[INVENTORY] no se pudieron crear las tablas del inventario: {}", error.getMessage());
		}
	}

	/** Corrida inmediata (cron externo o botón del panel vía API). */
	public Map<String, Object> runNow(String trigger) {
		if (!schemaReady) ensureSchema();
		if (!lock.tryLock()) return Json.obj("ok", true, "skipped", "already_running");
		try {
			return sync.run(companies.defaultCompanyId(), trigger);
		} finally {
			lock.unlock();
		}
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
	void tick() {
		if (!enabled()) return;
		try {
			if (!schemaReady) ensureSchema();
			if (!schemaReady) return;
			String companyId = companies.defaultCompanyId();
			if (sync.due(companyId, interval())) runNow("scheduler");
		} catch (RuntimeException error) {
			log.error("[INVENTORY] tarea programada falló", error);
		}
	}
}

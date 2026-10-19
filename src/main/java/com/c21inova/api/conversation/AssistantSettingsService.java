package com.c21inova.api.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;
import com.c21inova.api.config.Env;

/**
 * Configuración operativa del asistente (no secreta), editable por ADMIN.
 * Arranca en OFF: desplegar no cambia nada hasta que alguien lo activa.
 */
@Service
public class AssistantSettingsService {

	public record Settings(String id, String companyId, String mode, int debounceSeconds, int maxWaitSeconds, int maxClarifications,
			int abandonHandoffMinutes, int existingLeadWindowDays, int handoffReopenMinutes, List<String> testSubscriberIds,
			List<String> managementSubscriberIds, String updatedBy, Instant updatedAt) {

		public Map<String, Object> toView() {
			return Json.obj("mode", mode, "debounceSeconds", debounceSeconds, "maxWaitSeconds", maxWaitSeconds, "maxClarifications", maxClarifications,
					"abandonHandoffMinutes", abandonHandoffMinutes, "existingLeadWindowDays", existingLeadWindowDays, "handoffReopenMinutes",
					handoffReopenMinutes, "testSubscriberIds", testSubscriberIds, "managementSubscriberIds", managementSubscriberIds, "updatedBy", updatedBy,
					"updatedAt", updatedAt);
		}
	}

	public record Update(String mode, int debounceSeconds, int maxWaitSeconds, int maxClarifications, int abandonHandoffMinutes,
			int existingLeadWindowDays, int handoffReopenMinutes, List<String> testSubscriberIds, List<String> managementSubscriberIds) {
	}

	private final JdbcClient jdbc;
	private final Env env;

	public AssistantSettingsService(JdbcClient jdbc, Env env) {
		this.jdbc = jdbc;
		this.env = env;
	}

	public Settings get(String companyId) {
		Settings existing = find(companyId);
		if (existing != null) return existing;
		Instant now = Instant.now();
		jdbc.sql("""
				INSERT INTO assistant_settings (id, "companyId", "createdAt", "updatedAt") VALUES (:id, :c, :now, :now)
				ON CONFLICT ("companyId") DO NOTHING
				""").param("id", Ids.cuid()).param("c", companyId).param("now", Db.ts(now)).update();
		return find(companyId);
	}

	private Settings find(String companyId) {
		return jdbc.sql("""
				SELECT id, "companyId", mode::text AS mode, "debounceSeconds", "maxWaitSeconds", "maxClarifications", "abandonHandoffMinutes",
				  "existingLeadWindowDays", "handoffReopenMinutes", "testSubscriberIds", "managementSubscriberIds", "updatedBy", "updatedAt"
				FROM assistant_settings WHERE "companyId" = :c
				""").param("c", companyId).query((rs, i) -> new Settings(rs.getString("id"), rs.getString("companyId"), rs.getString("mode"),
				rs.getInt("debounceSeconds"), rs.getInt("maxWaitSeconds"), rs.getInt("maxClarifications"), rs.getInt("abandonHandoffMinutes"),
				rs.getInt("existingLeadWindowDays"), rs.getInt("handoffReopenMinutes"), Db.stringArray(rs, "testSubscriberIds"),
				Db.stringArray(rs, "managementSubscriberIds"), rs.getString("updatedBy"), Db.instant(rs, "updatedAt"))).optional().orElse(null);
	}

	/** El interruptor de emergencia del hosting (ASSISTANT_DISABLED=true) gana siempre. */
	public String effectiveMode(Settings settings) {
		return env.assistantKillSwitch() ? "OFF" : settings.mode();
	}

	public static boolean isTestSubscriber(Settings settings, String subscriberId) {
		return settings.testSubscriberIds().contains(subscriberId);
	}

	/** ¿Debe el asistente atender a este contacto? (independiente de pausas por conversación). */
	public boolean handles(Settings settings, String subscriberId) {
		String mode = effectiveMode(settings);
		if ("ON".equals(mode)) return true;
		if ("TEST_ONLY".equals(mode)) return isTestSubscriber(settings, subscriberId);
		return false;
	}

	public Settings update(String companyId, Update update, String updatedBy) {
		get(companyId);
		jdbc.sql("""
				UPDATE assistant_settings SET mode = CAST(:mode AS "AssistantMode"), "debounceSeconds" = :debounce, "maxWaitSeconds" = :maxWait,
				  "maxClarifications" = :maxClar, "abandonHandoffMinutes" = :abandon, "existingLeadWindowDays" = :window,
				  "handoffReopenMinutes" = :reopen, "testSubscriberIds" = CAST(:tests AS text[]), "managementSubscriberIds" = CAST(:mgmt AS text[]),
				  "updatedBy" = :by, "updatedAt" = :now
				WHERE "companyId" = :c
				""").param("mode", update.mode()).param("debounce", update.debounceSeconds()).param("maxWait", update.maxWaitSeconds())
				.param("maxClar", update.maxClarifications()).param("abandon", update.abandonHandoffMinutes()).param("window", update.existingLeadWindowDays())
				.param("reopen", update.handoffReopenMinutes()).param("tests", Db.arrayLiteral(update.testSubscriberIds()))
				.param("mgmt", Db.arrayLiteral(update.managementSubscriberIds())).param("by", updatedBy).param("now", Db.ts(Instant.now()))
				.param("c", companyId).update();
		return get(companyId);
	}
}

package com.c21inova.api.property;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.MexicoTime;
import com.c21inova.api.common.Texts;

/**
 * Consulta de un contacto por una propiedad, una fila por (contacto,
 * propiedad, día de CDMX). Cinco mensajes el mismo día siguen siendo UNA
 * consulta; los reportes cuentan contactos distintos por periodo.
 */
@Service
public class PropertyInquiryService {

	private static final Logger log = LoggerFactory.getLogger(PropertyInquiryService.class);

	/** Métodos de identificación de mayor a menor certeza (no se degrada uno ya registrado). */
	public static final Map<String, Integer> METHOD_RANK = Map.of(
			"code_in_message", 10, "code_in_url", 9, "easybroker_link", 9, "portal_listing", 8, "portal_page_code", 8,
			"internal_code", 7, "lead_property_code", 7, "confirmed_candidate", 5, "handoff", 5, "get_property", 3);

	public record Inquiry(String companyId, String publicId, String contactKey, String conversationId, String leadId, String method, String evidence,
			String linkPortal, String declaredSource, String acquisitionSource, int messageCount, String source, boolean isTest, Instant at) {
	}

	private final JdbcClient jdbc;

	public PropertyInquiryService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public static String contactKeyFor(String phone, String manyChatSubscriberId) {
		return phone != null && !phone.isEmpty() ? phone : "mc:" + manyChatSubscriberId;
	}

	private record Existing(String id, String method, String leadId, String conversationId, String linkPortal, String declaredSource,
			String acquisitionSource) {
	}

	private Existing find(String companyId, String publicId, String contactKey, String day) {
		return jdbc.sql("""
				SELECT id, method, "leadId", "conversationId", "linkPortal", "declaredSource", "acquisitionSource" FROM property_inquiries
				WHERE "companyId" = :companyId AND "publicId" = :publicId AND "contactKey" = :contactKey AND day = :day
				""").param("companyId", companyId).param("publicId", publicId).param("contactKey", contactKey).param("day", day)
				.query((rs, i) -> new Existing(rs.getString("id"), rs.getString("method"), rs.getString("leadId"), rs.getString("conversationId"),
						rs.getString("linkPortal"), rs.getString("declaredSource"), rs.getString("acquisitionSource")))
				.optional().orElse(null);
	}

	public void record(Inquiry input) {
		Instant at = input.at() != null ? input.at() : Instant.now();
		String day = MexicoTime.dateKey(at);
		String publicId = input.publicId().toUpperCase(Locale.ROOT);
		String evidence = Texts.slice(input.evidence(), 500);
		Existing existing = find(input.companyId(), publicId, input.contactKey(), day);
		if (existing == null) {
			try {
				jdbc.sql("""
						INSERT INTO property_inquiries (id, "companyId", "publicId", "contactKey", day, "conversationId", "leadId", channel, "linkPortal",
						  method, evidence, "acquisitionSource", "declaredSource", "messageCount", source, "isTest", "firstAt", "lastAt")
						VALUES (:id, :companyId, :publicId, :contactKey, :day, :conversationId, :leadId, 'whatsapp', :linkPortal, :method, :evidence,
						  :acquisition, :declared, :messageCount, :source, :isTest, :at, :at)
						""")
						.param("id", Ids.cuid()).param("companyId", input.companyId()).param("publicId", publicId).param("contactKey", input.contactKey())
						.param("day", day).param("conversationId", input.conversationId()).param("leadId", input.leadId())
						.param("linkPortal", input.linkPortal()).param("method", input.method()).param("evidence", evidence)
						.param("acquisition", Texts.slice(input.acquisitionSource(), 200)).param("declared", Texts.slice(input.declaredSource(), 200))
						.param("messageCount", Math.max(1, input.messageCount())).param("source", input.source()).param("isTest", input.isTest())
						.param("at", Db.ts(at)).update();
				return;
			} catch (RuntimeException raceLost) {
				// Carrera con otro registro del mismo día: se suma abajo.
			}
		}
		Existing current = existing != null ? existing : find(input.companyId(), publicId, input.contactKey(), day);
		if (current == null) return;
		boolean better = METHOD_RANK.getOrDefault(input.method(), 0) > METHOD_RANK.getOrDefault(current.method(), 0);
		StringBuilder set = new StringBuilder("\"messageCount\" = \"messageCount\" + :increment, \"lastAt\" = :at");
		if (better) set.append(", method = :method, evidence = :evidence");
		if (current.leadId() == null && input.leadId() != null) set.append(", \"leadId\" = :leadId");
		if (current.conversationId() == null && input.conversationId() != null) set.append(", \"conversationId\" = :conversationId");
		if (current.linkPortal() == null && input.linkPortal() != null) set.append(", \"linkPortal\" = :linkPortal");
		if (current.declaredSource() == null && input.declaredSource() != null) set.append(", \"declaredSource\" = :declared");
		if (current.acquisitionSource() == null && input.acquisitionSource() != null) set.append(", \"acquisitionSource\" = :acquisition");
		JdbcClient.StatementSpec spec = jdbc.sql("UPDATE property_inquiries SET " + set + " WHERE id = :id").param("id", current.id())
				.param("increment", Math.max(0, input.messageCount())).param("at", Db.ts(at));
		if (better) spec = spec.param("method", input.method()).param("evidence", evidence);
		if (current.leadId() == null && input.leadId() != null) spec = spec.param("leadId", input.leadId());
		if (current.conversationId() == null && input.conversationId() != null) spec = spec.param("conversationId", input.conversationId());
		if (current.linkPortal() == null && input.linkPortal() != null) spec = spec.param("linkPortal", input.linkPortal());
		if (current.declaredSource() == null && input.declaredSource() != null) spec = spec.param("declared", Texts.slice(input.declaredSource(), 200));
		if (current.acquisitionSource() == null && input.acquisitionSource() != null) {
			spec = spec.param("acquisition", Texts.slice(input.acquisitionSource(), 200));
		}
		spec.update();
	}

	/** Nunca rompe el flujo que la llama: una métrica perdida es mejor que un mensaje sin responder. */
	public void recordSafe(Inquiry input) {
		try {
			record(input);
		} catch (RuntimeException error) {
			log.error("[INQUIRY] no se pudo registrar la consulta {}: {}", input.publicId(), error.getMessage());
		}
	}
}

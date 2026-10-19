package com.c21inova.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.c21inova.api.advisor.Advisor;
import com.c21inova.api.advisor.AdvisorRounds;
import com.c21inova.api.assignment.AssignmentEngine;
import com.c21inova.api.common.Csv;
import com.c21inova.api.common.Json;
import com.c21inova.api.common.LenientJson;
import com.c21inova.api.common.Phone;
import com.c21inova.api.common.Texts;
import com.c21inova.api.report.ReportDataService;
import com.c21inova.api.conversation.ListingExtract;
import com.c21inova.api.conversation.Policy;
import com.c21inova.api.conversation.Prompt;
import com.c21inova.api.conversation.PropertyReference;
import com.c21inova.api.conversation.Session;
import com.c21inova.api.integration.IntegrationNotice;
import com.c21inova.api.integration.ManyChatErrors;
import com.c21inova.api.lead.AdvisorNotice;
import com.c21inova.api.lead.LeadRules;
import com.c21inova.api.property.PropertyReportRules;
import com.c21inova.api.report.DateRange;

/**
 * Paridad con el panel en TypeScript: cada caso de src/test/resources/parity-vectors.json
 * se generó ejecutando la función original; aquí se compara con la versión en Java.
 */
class ParityTest {

	private final List<String> failures = new ArrayList<>();

	@SuppressWarnings("unchecked")
	@Test
	void javaMatchesTypeScript() throws Exception {
		Map<String, Object> vectors;
		try (InputStream in = getClass().getResourceAsStream("/parity-vectors.json")) {
			vectors = Json.parseObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
		}
		Map<String, Function<Object, Object>> impl = implementations();
		int checked = 0;
		for (Map.Entry<String, Object> group : vectors.entrySet()) {
			Function<Object, Object> fn = impl.get(group.getKey());
			if (fn == null) {
				failures.add("Sin implementación para " + group.getKey());
				continue;
			}
			for (Object raw : (List<Object>) group.getValue()) {
				Map<String, Object> vector = (Map<String, Object>) raw;
				Object actual;
				try {
					actual = Json.parse(Json.write(fn.apply(vector.get("input"))));
				} catch (RuntimeException error) {
					failures.add(group.getKey() + " " + Json.write(vector.get("input")) + " → excepción " + error);
					continue;
				}
				Object expected = vector.get("output");
				if (!java.util.Objects.equals(normalize(expected), normalize(actual))) {
					failures.add(group.getKey() + " " + truncate(Json.write(vector.get("input"))) + "\n   esperado: " + truncate(Json.write(expected))
							+ "\n   obtenido: " + truncate(Json.write(actual)));
				}
				checked++;
			}
		}
		assertTrue(failures.isEmpty(), failures.size() + " diferencias de " + checked + ":\n" + String.join("\n", failures));
	}

	private static String truncate(String text) {
		return text.length() > 1500 ? text.substring(0, 1500) + "…" : text;
	}

	/** Mapas sin llaves nulas (undefined = null) y números comparados por valor. */
	@SuppressWarnings("unchecked")
	static Object normalize(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> out = new java.util.TreeMap<>();
			for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) if (e.getValue() != null) out.put(e.getKey(), normalize(e.getValue()));
			return out;
		}
		if (value instanceof List<?> list) return list.stream().map(ParityTest::normalize).toList();
		if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
		return value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> m(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> l(Object o) {
		return (List<Object>) o;
	}

	private static Instant at(Object iso) {
		return iso == null ? null : Instant.parse((String) iso);
	}

	private static Integer i(Object o) {
		return o == null ? null : ((Number) o).intValue();
	}

	private static Double d(Object o) {
		return o == null ? null : ((Number) o).doubleValue();
	}

	private static AdvisorNotice.NoticeProperty noticeProperty(Map<String, Object> p) {
		if (p == null) return null;
		List<AdvisorNotice.Operation> ops = Json.objects(p.get("operations")).stream()
				.map(op -> new AdvisorNotice.Operation(Json.str(op, "type"), Json.str(op, "formatted_amount"), null)).toList();
		List<String> features = Json.objects(p.get("features")).stream().map(f -> Json.str(f, "name")).toList();
		return new AdvisorNotice.NoticeProperty((String) p.get("public_id"), (String) p.get("title"), (String) p.get("property_type"),
				(String) p.get("location"), (String) p.get("public_url"), ops, d(p.get("bedrooms")), d(p.get("bathrooms")), d(p.get("half_bathrooms")),
				d(p.get("parking_spaces")), d(p.get("construction_size")), d(p.get("lot_size")), features, (String) p.get("description"),
				(Boolean) p.get("show_prices"));
	}

	private static AdvisorNotice.ClientContext client(Map<String, Object> c) {
		if (c == null) return null;
		List<AdvisorNotice.OtherProperty> others = Json.objects(c.get("otherProperties")).stream()
				.map(o -> new AdvisorNotice.OtherProperty(Json.str(o, "publicId"), Json.str(o, "title"), Json.str(o, "url"))).toList();
		return new AdvisorNotice.ClientContext((String) c.get("reason"), (String) c.get("operation"), (String) c.get("zone"), (String) c.get("budget"),
				l(c.get("additionalNeeds")).stream().map(Object::toString).toList(), (String) c.get("summary"), others);
	}

	private static List<Advisor> advisors() {
		return List.of(advisor("a1", "A", 5, null), advisor("a2", "B", 10, 3), advisor("a3", "C", 10, null), advisor("a4", "D", 1, 1));
	}

	private static Advisor advisor(String id, String name, int weight, Integer limit) {
		return new Advisor(id, "c", name, null, "1", null, null, null, true, weight, limit, null, 0, true, true, true, true, null, null, null, null);
	}

	private static Map<String, Integer> counts(Object raw) {
		Map<String, Integer> out = new HashMap<>();
		if (raw != null) m(raw).forEach((k, v) -> out.put(k, i(v)));
		return out;
	}

	private static AdvisorRounds.Schedule schedule(Map<String, Object> r) {
		return new AdvisorRounds.Schedule((String) r.get("advisorId"), l(r.get("weekdays")).stream().map(x -> i(x)).toList(), i(r.get("startMinute")),
				i(r.get("endMinute")), (Boolean) r.get("active"));
	}

	private static List<ListingExtract.CatalogListing<String>> catalog() {
		return List.of(
				listing("EB-AA1111", "Casa en Valle Real", "EB-AA1111 Casa en Valle Real casa Zapopan venta $8,500,000", 8500000.0, 4),
				listing("EB-BB2222", "Departamento Torre Altus", "EB-BB2222 Departamento Torre Altus departamento Puerta de Hierro renta $45,000", 45000.0, 2),
				listing("EB-CC3333", "Casa en Valle Imperial", "EB-CC3333 Casa en Valle Imperial casa Zapopan venta $3,200,000", 3200000.0, 3),
				listing("EB-DD4444", "Terreno en Coto Almendro", "EB-DD4444 Terreno en Coto Almendro terreno Tlajomulco venta $1,000,000", 1000000.0, null));
	}

	private static ListingExtract.CatalogListing<String> listing(String id, String title, String text, double price, Integer bedrooms) {
		return new ListingExtract.CatalogListing<>(id, id, title, PropertyReference.normalizeSearchText(text), List.of(price), bedrooms);
	}

	private static Map<String, Object> facts(Object raw) {
		return raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m(raw));
	}

	private static Throwable error(String name) {
		return switch (name) {
			case "api429" -> new ManyChatErrors.ManyChatApiException(429, "Too Many", Json.obj("status", "error", "message", "Too many requests"),
					"POST /fb/sending/sendFlow", 30000L);
			case "api500" -> new ManyChatErrors.ManyChatApiException(502, "Bad Gateway", null, "POST /fb/sending/sendFlow", null);
			case "api401" -> new ManyChatErrors.ManyChatApiException(401, "Unauthorized", Json.obj("message", "Bad token"), null, null);
			case "apiChannel" -> new ManyChatErrors.ManyChatApiException(400, "Bad", Json.obj("status", "error", "message",
					"Flow can't be sent outside the 24 hours window", "details", Json.obj("messages", List.of(Json.obj("message", "Use a template")))), null, null);
			case "apiFlow" -> new ManyChatErrors.ManyChatApiException(400, "Bad", Json.obj("status", "error", "message", "Flow content20260714 not found"), null,
					null);
			case "apiContact" -> new ManyChatErrors.ManyChatApiException(400, "Bad", Json.obj("status", "error", "message", "Subscriber does not exist"), null,
					null);
			case "apiAuthText" -> new ManyChatErrors.ManyChatApiException(400, "Bad",
					Json.obj("status", "error", "message", "Permission denied for this api key"), null, null);
			case "apiRejected" -> new ManyChatErrors.ManyChatApiException(400, "Bad",
					Json.obj("status", "error", "details", Json.obj("field_value", "too long", "other", List.of("a", 1, "b"))), null, null);
			case "body200" -> new ManyChatErrors.ManyChatApiException(200, "OK", Json.obj("status", "error"), null, null);
			case "sanitize" -> new ManyChatErrors.ManyChatApiException(400, "Bad",
					"Bearer abc.def token 1234567:abcdefabcdefabcdef phone +52 33 1234 5678", null, null);
			case "timeoutSend", "timeoutFields" -> new ManyChatErrors.ManyChatTimeoutException("POST /x", 10000);
			case "network" -> new ManyChatErrors.ManyChatNetworkException("POST /x", new RuntimeException("reset"));
			case "config" -> new ManyChatErrors.ManyChatConfigException("MANYCHAT_ADVISOR_FLOW_ID", "Falta flujo");
			default -> new RuntimeException("algo  falló");
		};
	}

	private static IntegrationNotice.NoticeJob job(Map<String, Object> j) {
		return new IntegrationNotice.NoticeJob((String) j.get("id"), (String) j.get("type"), (String) j.get("status"), i(j.get("attempts")),
				(String) j.get("lastError"), (String) j.get("errorKind"), (String) j.get("errorCode"), at(j.get("lastAttemptAt")), at(j.get("nextRetryAt")),
				at(j.get("createdAt")), j.get("payload"));
	}

	private static List<IntegrationNotice.NoticeJob> jobs() {
		return List.of(
				job(Json.obj("id", "j1", "type", "MANYCHAT_FLOW", "status", "FAILED", "attempts", 1, "lastError", "Flow not found", "errorKind", "FLOW",
						"errorCode", "HTTP_400", "lastAttemptAt", "2026-10-18T10:00:00Z", "nextRetryAt", "2026-10-18T10:00:00Z", "createdAt",
						"2026-10-18T10:00:00Z", "payload", Json.obj("subscriberId", "999123"))),
				job(Json.obj("id", "j2", "type", "MANYCHAT_FLOW", "status", "RETRYING", "attempts", 2, "errorKind", "PROVIDER", "errorCode", "HTTP_502",
						"nextRetryAt", "2026-10-18T11:00:00Z", "createdAt", "2026-10-18T09:00:00Z", "payload", Json.obj("subscriberId", "111"))),
				job(Json.obj("id", "j3", "type", "EASYBROKER_ASSIGN", "status", "RETRYING", "attempts", 1, "lastError", "x", "errorKind", "PROVIDER",
						"errorCode", "EASYBROKER", "nextRetryAt", "2026-10-18T11:00:00Z", "createdAt", "2026-10-18T09:00:00Z", "payload", Json.obj())),
				job(Json.obj("id", "j4", "type", "EASYBROKER_CREATE", "status", "UNCERTAIN", "attempts", 1, "createdAt", "2026-10-18T09:00:00Z", "payload",
						Json.obj())));
	}

	private static List<PropertyReportRules.InquiryRow> inquiryRows() {
		return List.of(
				new PropertyReportRules.InquiryRow("EB-AA1111", "+521", "2026-10-17", 3, "whatsapp", "inmuebles24", null, "Facebook", false,
						Instant.parse("2026-10-17T18:00:00Z")),
				new PropertyReportRules.InquiryRow("EB-AA1111", "+521", "2026-10-18", 1, "whatsapp", null, null, null, false, Instant.parse("2026-10-18T18:00:00Z")),
				new PropertyReportRules.InquiryRow("EB-AA1111", "+522", "2026-10-10", 2, "whatsapp", null, "Campaña X", null, false,
						Instant.parse("2026-10-10T18:00:00Z")),
				new PropertyReportRules.InquiryRow("EB-BB2222", "+523", "2026-10-18", 5, "whatsapp", null, null, null, false, Instant.parse("2026-10-18T19:00:00Z")),
				new PropertyReportRules.InquiryRow("EB-BB2222", "+524", "2026-10-18", 5, "whatsapp", null, null, null, true, Instant.parse("2026-10-18T19:00:00Z")));
	}

	private static List<PropertyReportRules.EventView> events() {
		return List.of(
				new PropertyReportRules.EventView("SHOWING", "DONE", "Visita", null, Instant.parse("2026-10-18T17:00:00Z"), null, "Le gustó  mucho", 1),
				new PropertyReportRules.EventView("OPEN_HOUSE", "SCHEDULED", "Open", "Con café", Instant.parse("2026-10-25T17:00:00Z"), null, null, 2),
				new PropertyReportRules.EventView("OFFER", "CANCELLED", "Oferta", null, null, null, null, 1));
	}

	private static Object weeklyJson(PropertyReportRules.WeeklyReport report) {
		return Json.obj("variables", report.variables(), "text", report.text(), "metrics",
				Json.obj("periodLeads", report.periodLeads(), "cumulativeLeads", report.cumulativeLeads()));
	}

	private Map<String, Function<Object, Object>> implementations() {
		Map<String, Function<Object, Object>> impl = new HashMap<>();
		impl.put("phone", in -> Phone.normalizeE164((String) in));
		impl.put("samePhone", in -> Phone.same((String) l(in).get(0), (String) l(in).get(1)));
		impl.put("interest", in -> LeadRules.classifyInterest((String) in));
		impl.put("campaignCode", in -> LeadRules.extractCampaignPropertyCode((String) in));
		impl.put("notice", in -> AdvisorNotice.build(noticeProperty(m(m(in).get("property"))), (String) m(in).get("propertyData"),
				client(m(m(in).get("client"))), i(m(in).get("max"))));
		impl.put("fitFields", in -> AdvisorNotice.fitFields(l(in).stream().map(f -> new AdvisorNotice.Field(i(m(f).get("fieldId")), (String) m(f).get("value")))
				.toList()));
		impl.put("budget", in -> AdvisorNotice.budget(l(in).stream().map(Object::toString).toList()));
		impl.put("pick", in -> {
			Advisor picked = AssignmentEngine.pickWeightedLeastAssigned(advisors(), counts(m(in).get("counts")), () -> d(m(in).get("random")));
			return picked == null ? null : picked.id();
		});
		impl.put("roundActive", in -> AdvisorRounds.isActiveAt(schedule(m(m(in).get("round"))), at(m(in).get("iso"))));
		impl.put("applyRounds", in -> AdvisorRounds.applyRounds(advisors(), Advisor::id, Advisor::dailyLimit, counts(m(in).get("counts")),
				Set.copyOf(l(m(in).get("onRound")).stream().map(Object::toString).toList())).pool().stream().map(Advisor::id).toList());
		impl.put("parseTime", in -> AdvisorRounds.parseTimeToMinute((String) in));
		impl.put("formatMinute", in -> AdvisorRounds.formatMinute(i(in)));
		impl.put("burst", in -> {
			Session.Burst b = Session.computeProcessAfter(at(m(in).get("now")), at(m(in).get("started")), i(m(in).get("d")), i(m(in).get("w")));
			return List.of(Texts.iso(b.processAfter()), Texts.iso(b.burstStartedAt()));
		});
		impl.put("dedupe", in -> Session.inboundDedupeKey((String) l(in).get(0), (String) l(in).get(1), (String) l(in).get(2), (String) l(in).get(3)));
		impl.put("session", in -> Session.decideSession(at(l(in).get(0)), at(l(in).get(1)), at(l(in).get(2)), (Boolean) l(in).get(3)));
		impl.put("confirmation", in -> Session.buildAssignmentConfirmation(new Session.AssignmentFacts((String) m(in).get("status"),
				(String) m(in).get("advisorName"), (Boolean) m(in).get("advisorNotified"))));
		impl.put("waitNotice", in -> {
			Map<String, Object> a = m(m(in).get("assignment"));
			return Session.buildWaitNotice((String) m(in).get("reason"), new Session.AssignmentFacts((String) a.get("status"), (String) a.get("advisorName"),
					(Boolean) a.get("advisorNotified")), i(m(in).get("minutes")), (Boolean) m(in).get("saved"));
		});
		impl.put("remaining", in -> Session.remainingWaitMinutes(at(l(in).get(0)), at(l(in).get(1))));
		impl.put("mentionsAdvisor", in -> Session.mentionsAdvisor((String) l(in).get(0), (String) l(in).get(1)));
		impl.put("lowSignal", in -> Session.isLowSignalMessage((String) in));
		impl.put("handoff", in -> {
			Policy.HandoffDecision decision = Policy.decideCommercialHandoff((String) m(in).get("primaryIntent"),
					l(m(in).get("secondaryIntents")).stream().map(Object::toString).toList(), (String) m(in).get("verifiedPropertyId"),
					(String) m(in).get("trigger"), (Boolean) m(in).get("fromCampaign"));
			return decision.allowed() ? Json.obj("allowed", true, "interesCliente", decision.interesCliente(), "datosPropiedad", decision.datosPropiedad())
					: Json.obj("allowed", false, "reason", decision.reason());
		});
		impl.put("mergeFacts", in -> Policy.mergeFacts(facts(m(in).get("prev")), facts(m(in).get("upd"))));
		impl.put("missingFacts", in -> Policy.computeMissingFacts((String) l(in).get(0), facts(l(in).get(1))));
		impl.put("claims", in -> Policy.claimsAssignment((String) in));
		impl.put("internal", in -> Policy.mentionsInternalProcess((String) in));
		impl.put("codes", in -> PropertyReference.extractEasyBrokerCodes((String) in));
		impl.put("urls", in -> PropertyReference.extractUrls((String) in));
		impl.put("canonical", in -> PropertyReference.canonicalizeUrl((String) in));
		impl.put("listingKey", in -> {
			String canonical = PropertyReference.canonicalizeUrl((String) in);
			return canonical == null ? null : PropertyReference.portalListingKeyFromUrl(canonical, null);
		});
		impl.put("listingKeyRemote", in -> PropertyReference.portalListingKeyFromUrl((String) l(in).get(0), (String) l(in).get(1)));
		impl.put("parseUrl", in -> PropertyReference.parsePropertyUrl((String) in));
		impl.put("tokens", in -> PropertyReference.searchTokens((String) in));
		impl.put("normalize", in -> PropertyReference.normalizeSearchText((String) in));
		impl.put("score", in -> PropertyReference.scoreProperties((String) in, catalog(), ListingExtract.CatalogListing::title,
				ListingExtract.CatalogListing::searchText, 5).stream().map(s -> List.of(s.property().publicId(), Math.round(s.score() * 1e6) / 1e6)).toList());
		impl.put("match", in -> {
			Map<String, Object> info = m(in);
			ListingExtract.MatchResult<String> r = ListingExtract.matchToCatalog(new ListingExtract.ListingInfo((String) info.get("title"),
					(String) info.get("description"), d(info.get("price")), null, (String) info.get("location"), i(info.get("bedrooms")), List.of()), catalog(), 3);
			return Json.obj("confidence", r.confidence(), "matches",
					r.matches().stream().map(x -> List.of(x.property().publicId(), Math.round(x.score() * 1e6) / 1e6, x.priceMatch())).toList());
		});
		impl.put("listingInfo", in -> ListingExtract.extract((String) in));
		impl.put("slug", in -> ListingExtract.slugKeywords((String) in));
		impl.put("systemPrompt", in -> Prompt.SYSTEM_PROMPT);
		impl.put("stateBlock", in -> "full".equals(in)
				? Prompt.buildStateBlock(new Prompt.State("first_contact", "BUY", List.of("SELL"),
						facts(Json.obj("zone", Json.obj("value", "Zapopan", "status", "known"), "budget_max", Json.obj("value", null, "status", "declined"))),
						List.of(new Prompt.VerifiedProperty("EB-AA1111", "Casa", "https://x"), new Prompt.VerifiedProperty("EB-BB2222", "Depa", null)), "Resumen",
						"todavía no canalizado", 1, 3, null, "EB-AA1111", "18/10/2026",
						new Prompt.PreviousSessionView("ayer", "antes", List.<String[]>of(new String[] { "EB-CC3333", "Casa vieja" }),
								"Quedó canalizado con Ana (asesor vigente; no se reasigna)."),
						List.of("https://a", "https://b")))
				: Prompt.buildStateBlock(new Prompt.State("none", "UNKNOWN", List.of(), new LinkedHashMap<>(), List.of(), null, "x", 0, 3, "Ana", null, "hoy", null,
						null)));
		impl.put("classify", in -> ManyChatErrors.classify(error((String) m(in).get("name")), (Boolean) m(in).get("idempotent")));
		impl.put("providerMessage", in -> ManyChatErrors.providerMessage(in));
		impl.put("dateRange", in -> {
			DateRange.Resolved r = DateRange.resolve((String) m(in).get("preset"), "2026-02-01", "2026-02-28", at(m(in).get("now")));
			return List.of(Texts.iso(r.startDate()), Texts.iso(r.endDate()), r.label());
		});
		impl.put("weekly", in -> {
			PropertyReportRules.WeeklyPeriod p = PropertyReportRules.weeklyPeriodFor(at(in), 5);
			return List.of(Texts.iso(p.start()), Texts.iso(p.endExclusive()), p.startKey(), p.endKey());
		});
		impl.put("weeklyDue", in -> List.of(PropertyReportRules.isWeeklyDue(at(in), 5, 9, 30), PropertyReportRules.isWeeklyDue(at(in), 5, null, 0)));
		impl.put("aggregateInquiries", in -> PropertyReportRules.aggregate(inquiryRows(), "2026-10-16", "2026-10-22").stream().map(x -> {
			Map<String, Object> json = x.toJson();
			json.put("lastInquiryAt", Texts.iso(x.lastInquiryAt()));
			return json;
		}).toList());
		impl.put("ownerWeekly", in -> {
			PropertyReportRules.WeeklyPeriod period = PropertyReportRules.weeklyPeriodFor(Instant.parse("2026-10-23T18:00:00Z"), 5);
			if ("empty".equals(in)) return weeklyJson(PropertyReportRules.buildWeekly("EB-ZZ0000", null, period, null, null, List.of()));
			PropertyReportRules.Metrics metrics = PropertyReportRules.aggregate(inquiryRows(), "2026-10-16", "2026-10-22").get(0);
			return weeklyJson(PropertyReportRules.buildWeekly("EB-AA1111", "Casa Valle Real", period, metrics, "2026-10-01", events()));
		});
		impl.put("ownerEvent", in -> {
			PropertyReportRules.EventView event = events().stream().filter(e -> (e.status() + e.type()).equals(in)).findFirst().orElseThrow();
			PropertyReportRules.EventNotice notice = PropertyReportRules.buildEventNotice("EB-AA1111", "Casa", event);
			return Json.obj("variables", notice.variables(), "text", notice.text());
		});
		impl.put("reportAggregation", in -> {
			Map<String, Object> agg = ReportDataService.aggregate(10,
					List.of(Json.obj("k", "PROPERTY", "n", 4), Json.obj("k", "NO_RESPONSE", "n", 1), Json.obj("k", "TIMEOUT", "n", 2), Json.obj("k", "OTHER", "n", 3)),
					List.of(Json.obj("k", null, "n", 2), Json.obj("k", "  ", "n", 1), Json.obj("k", "WhatsApp IA", "n", 7)),
					List.of(Json.obj("k", "COMPLETED", "n", 6), Json.obj("k", "FAILED", "n", 1), Json.obj("k", "NOTIFIED", "n", 3)),
					List.of(Json.obj("a", "a1", "k", "PROPERTY", "n", 4), Json.obj("a", null, "k", "OTHER", "n", 3), Json.obj("a", "zz", "k", "TIMEOUT", "n", 3)),
					Map.of("a1", "Ana"),
					List.of(Json.obj("a", "a1", "k", "DIRECT_PROPERTY_ADVISOR", "n", 2), Json.obj("a", "a1", "k", "WEIGHTED_ROTATION", "n", 2),
							Json.obj("a", "zz", "k", "MANUAL", "n", 1)),
					8);
			// El vector usa statusLabel = minúsculas; el servicio real usa las etiquetas en español.
			for (Object row : l(agg.get("byStatus"))) m(row).put("label", ((String) m(row).get("status")).toLowerCase());
			return agg;
		});
		impl.put("escapeJson", in -> LenientJson.escapeControlCharsInStrings((String) in));
		impl.put("notice2", in -> switch ((String) in) {
			case "all" -> IntegrationNotice.current(jobs(), IntegrationNotice.NoticeJob::type, Set.of());
			case "confirmed" -> IntegrationNotice.current(jobs(), IntegrationNotice.NoticeJob::type, Set.of("MANYCHAT_FLOW", "EASYBROKER_CREATE"));
			default -> IntegrationNotice.current(List.of(), null, Set.of());
		});
		impl.put("failureAudit", in -> "noNext".equals(in) ? IntegrationNotice.failureAuditMessage("X", "FAILED", null, null, "r", 1, null)
				: IntegrationNotice.failureAuditMessage("MANYCHAT_FLOW", "RETRYING", "PROVIDER", "HTTP_502", "Gateway", 2, Instant.parse("2026-10-18T11:00:00Z")));
		impl.put("uncovered", in -> IntegrationNotice.uncoveredAuditError(List.of(
				new IntegrationNotice.AuditRow("RETRY_SCHEDULED", "a", Instant.parse("2026-10-18T10:00:00Z")),
				new IntegrationNotice.AuditRow("MANYCHAT_NOTIFICATION_FAILED", "b", Instant.parse("2026-10-18T08:00:00Z")),
				new IntegrationNotice.AuditRow("PROPERTY_FETCH_FAILED", "c", Instant.parse("2026-10-18T07:00:00Z"))), jobs(), false));
		impl.put("recovery", in -> {
			Map<String, Object> j = m(l(in).get(0));
			Map<String, Object> s = m(l(in).get(1));
			return IntegrationNotice.categorizeRecovery((String) j.get("status"), (String) j.get("subscriberId"), (Boolean) s.get("exists"),
					(String) s.get("assignedAdvisorId"), (String) s.get("assignedAdvisorSubscriberId"), (Boolean) s.get("noticeConfirmed"));
		});
		impl.put("fingerprint", in -> LeadRules.fingerprint("+523312345678", "Vi una propiedad", "EB-AA1111", "WA", Instant.parse("2026-10-18T05:30:00Z")));
		impl.put("csv", in -> Csv.toCsv(List.of(List.of("=HYPERLINK(1)", "+5213312345678", "a \"b\", c", "línea\nnueva"), List.of("-5", "@x", "ok"))));
		return impl;
	}
}

package com.c21inova.api.integration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.c21inova.api.common.Json;
import com.c21inova.api.common.Phone;
import com.c21inova.api.common.Retry;
import com.c21inova.api.lead.AdvisorNotice;

@Service
public class EasyBrokerService {

	private static final Retry.Options RETRY = new Retry.Options(3, 500, 4000);

	/** Propiedad de EasyBroker con `location` siempre como texto (el detalle la devuelve como objeto). */
	public record Property(
			String publicId,
			String title,
			String propertyType,
			String location,
			String publicUrl,
			String agentEmail,
			List<AdvisorNotice.Operation> operations,
			Double bedrooms,
			Double bathrooms,
			Double halfBathrooms,
			Double parkingSpaces,
			Double lotSize,
			Double constructionSize,
			String description,
			String updatedAt,
			String status,
			Boolean showPrices,
			List<String> features,
			String internalId) {

		public AdvisorNotice.NoticeProperty toNotice() {
			return new AdvisorNotice.NoticeProperty(publicId, title, propertyType, location, publicUrl, operations, bedrooms, bathrooms,
					halfBathrooms, parkingSpaces, constructionSize, lotSize, features, description, showPrices);
		}

		/** Mezcla listado + detalle (el detalle gana), como `{ ...property, ...detail }`. */
		public Property mergedWith(Property detail) {
			if (detail == null) return this;
			return new Property(
					publicId,
					detail.title != null ? detail.title : title,
					detail.propertyType != null ? detail.propertyType : propertyType,
					detail.location != null ? detail.location : location,
					detail.publicUrl != null ? detail.publicUrl : publicUrl,
					detail.agentEmail != null ? detail.agentEmail : agentEmail,
					detail.operations != null ? detail.operations : operations,
					detail.bedrooms != null ? detail.bedrooms : bedrooms,
					detail.bathrooms != null ? detail.bathrooms : bathrooms,
					detail.halfBathrooms != null ? detail.halfBathrooms : halfBathrooms,
					detail.parkingSpaces != null ? detail.parkingSpaces : parkingSpaces,
					detail.lotSize != null ? detail.lotSize : lotSize,
					detail.constructionSize != null ? detail.constructionSize : constructionSize,
					detail.description != null ? detail.description : description,
					detail.updatedAt != null ? detail.updatedAt : updatedAt,
					detail.status != null ? detail.status : status,
					detail.showPrices != null ? detail.showPrices : showPrices,
					detail.features != null ? detail.features : features,
					detail.internalId);
		}
	}

	public record Page<T>(List<T> content, Integer total, boolean hasNext) {
	}

	public record ContactRequest(String id, String contactId, String phone, String source, String propertyId) {
	}

	public record PortalIntegration(String partnerName, String partnerSymbol, String operationType, String remoteListingId, String listingUrl,
			boolean published, String status) {
	}

	public record PropertyIntegrations(String publicId, List<PortalIntegration> integrations) {
	}

	private final EasyBrokerClient client;

	public EasyBrokerService(EasyBrokerClient client) {
		this.client = client;
	}

	static Property normalizeProperty(Map<String, Object> raw) {
		Object location = raw.get("location");
		String locationText = location instanceof String s ? s : Json.str(Json.asMap(location), "name");
		Map<String, Object> agent = Json.asMap(raw.get("agent"));
		List<AdvisorNotice.Operation> operations = null;
		if (raw.get("operations") instanceof List<?>) {
			operations = new ArrayList<>();
			for (Map<String, Object> op : Json.objects(raw.get("operations"))) {
				operations.add(new AdvisorNotice.Operation(Json.str(op, "type"), Json.str(op, "formatted_amount"), Json.number(op, "amount")));
			}
		}
		List<String> features = null;
		if (raw.get("features") instanceof List<?>) {
			features = new ArrayList<>();
			for (Map<String, Object> feature : Json.objects(raw.get("features"))) {
				String name = Json.str(feature, "name");
				if (name != null) features.add(name);
			}
		}
		Object internalId = raw.get("internal_id");
		return new Property(
				Json.str(raw, "public_id"),
				Json.str(raw, "title"),
				Json.str(raw, "property_type"),
				locationText,
				Json.str(raw, "public_url"),
				Json.str(agent, "email"),
				operations,
				Json.number(raw, "bedrooms"),
				Json.number(raw, "bathrooms"),
				Json.number(raw, "half_bathrooms"),
				Json.number(raw, "parking_spaces"),
				Json.number(raw, "lot_size"),
				Json.number(raw, "construction_size"),
				Json.str(raw, "description"),
				Json.str(raw, "updated_at"),
				Json.str(raw, "status"),
				Json.bool(raw, "show_prices"),
				features,
				internalId instanceof String s ? s : null);
	}

	/** id y contact_id llegan como números en la API real; aquí se normalizan a texto. */
	static ContactRequest normalizeContactRequest(Map<String, Object> raw) {
		return new ContactRequest(
				raw.get("id") != null ? raw.get("id").toString() : null,
				raw.get("contact_id") != null ? raw.get("contact_id").toString() : null,
				raw.get("phone") instanceof String s ? s : null,
				raw.get("source") instanceof String s ? s : null,
				raw.get("property_id") != null ? raw.get("property_id").toString() : null);
	}

	public Property getProperty(String publicId) {
		return Retry.withRetry(() -> normalizeProperty(Json.asMap(client.request("GET", "/properties/" + encode(publicId), null, null))), RETRY);
	}

	/** Una página del inventario publicado, con la indicación de si hay más. */
	public Page<Property> listPublishedPropertiesPage(int page, int limit) {
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("page", page);
		query.put("limit", limit);
		query.put("search[statuses][]", "published");
		Map<String, Object> result = Retry.withRetry(() -> Json.asMap(client.request("GET", "/properties", query, null)), RETRY);
		List<Property> content = Json.objects(result == null ? null : result.get("content")).stream().map(EasyBrokerService::normalizeProperty).toList();
		Map<String, Object> pagination = result == null ? null : Json.asMap(result.get("pagination"));
		return new Page<>(content, Json.integer(pagination, "total"), Json.str(pagination, "next_page") != null);
	}

	/** Una página del inventario completo (todos los estados: publicada, reservada, no publicada…), tal como lo da la API. */
	public Page<Map<String, Object>> listAllPropertiesRawPage(int page, int limit) {
		Map<String, Object> query = new LinkedHashMap<>();
		query.put("page", page);
		query.put("limit", limit);
		Map<String, Object> result = Retry.withRetry(() -> Json.asMap(client.request("GET", "/properties", query, null)), RETRY);
		List<Map<String, Object>> content = Json.objects(result == null ? null : result.get("content"));
		Map<String, Object> pagination = result == null ? null : Json.asMap(result.get("pagination"));
		return new Page<>(content, Json.integer(pagination, "total"), Json.str(pagination, "next_page") != null);
	}

	/** Detalle completo sin normalizar (imágenes, ubicación como objeto, etc.). */
	public Map<String, Object> getPropertyRaw(String publicId) {
		return Retry.withRetry(() -> Json.asMap(client.request("GET", "/properties/" + encode(publicId), null, null)), RETRY);
	}

	/** Alta de una propiedad (POST /properties). Sin reintentos: un reintento podría duplicarla. */
	public Map<String, Object> createProperty(Map<String, Object> body) {
		return Json.asMap(client.request("POST", "/properties", null, body, 30_000));
	}

	/** Anuncios de cada propiedad en portales (GET /property_integrations). Solo lectura. */
	public Page<PropertyIntegrations> listPropertyIntegrationsPage(int page, int limit) {
		Map<String, Object> query = Map.of("page", page, "limit", limit);
		Map<String, Object> result = Retry.withRetry(() -> Json.asMap(client.request("GET", "/property_integrations", query, null)), RETRY);
		List<PropertyIntegrations> content = new ArrayList<>();
		for (Map<String, Object> item : Json.objects(result == null ? null : result.get("content"))) {
			List<PortalIntegration> integrations = new ArrayList<>();
			for (Map<String, Object> integration : Json.objects(item.get("integrations"))) {
				Map<String, Object> partner = Json.asMap(integration.get("integration_partner"));
				Object remote = integration.get("remote_listing_id");
				integrations.add(new PortalIntegration(Json.str(partner, "name"), Json.str(partner, "symbol"), Json.str(integration, "operation_type"),
						remote == null ? null : remote.toString(), Json.str(integration, "listing_url"), Boolean.TRUE.equals(integration.get("published")),
						Json.str(integration, "status")));
			}
			content.add(new PropertyIntegrations(Json.str(item, "public_id"), integrations));
		}
		Map<String, Object> pagination = result == null ? null : Json.asMap(result.get("pagination"));
		return new Page<>(content, Json.integer(pagination, "total"), Json.str(pagination, "next_page") != null);
	}

	public ContactRequest createContactRequest(String name, String phone, String message, String source, String propertyId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("phone", phone);
		body.put("message", message);
		body.put("source", source);
		if (propertyId != null) body.put("property_id", propertyId);
		Map<String, Object> raw = Retry.withRetry(() -> Json.asMap(client.request("POST", "/contact_requests", null, body)), RETRY.withMaxAttempts(4));
		return normalizeContactRequest(raw == null ? Map.of() : raw);
	}

	public List<ContactRequest> listRecentContactRequests() {
		Map<String, Object> result = Retry.withRetry(() -> Json.asMap(client.request("GET", "/contact_requests", null, null)), RETRY);
		return Json.objects(result == null ? null : result.get("content")).stream().map(EasyBrokerService::normalizeContactRequest).toList();
	}

	/** El campo "agent" de PATCH /contacts/{id} recibe el CORREO del asesor en EasyBroker, no un id. */
	public void assignContactToAdvisor(String contactId, String advisorEasyBrokerEmail) {
		Retry.withRetry(() -> client.request("PATCH", "/contacts/" + encode(contactId), null, Map.of("agent", advisorEasyBrokerEmail)),
				RETRY.withMaxAttempts(4));
	}

	/** Busca el contact_request recién creado por teléfono + origen (+ propiedad). Devuelve null si no aparece. */
	public ContactRequest findRecentContactRequest(String phone, String source, String propertyId) {
		return Retry.pollUntil(() -> {
			for (ContactRequest request : listRecentContactRequests()) {
				if (Phone.same(request.phone(), phone) && source.equals(request.source())
						&& (propertyId == null || propertyId.equals(request.propertyId()))) {
					return request;
				}
			}
			return null;
		}, new Retry.Options(5, 1000, 8000));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}

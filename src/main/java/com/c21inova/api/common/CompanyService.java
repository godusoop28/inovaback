package com.c21inova.api.common;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Todo está separado por companyId (multiempresa), pero hoy hay un solo
 * cliente. Se resuelve la Company de Century 21 Inova una vez por proceso.
 */
@Service
public class CompanyService {

	/** Identificador técnico: se queda con doble N (renombrarlo rompería la búsqueda). */
	public static final String DEFAULT_COMPANY_SLUG = "century21-innova";

	private final JdbcClient jdbc;
	private volatile String cachedCompanyId;

	public CompanyService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public String defaultCompanyId() {
		String cached = cachedCompanyId;
		if (cached != null) return cached;
		String id = jdbc.sql("SELECT id FROM companies WHERE slug = :slug").param("slug", DEFAULT_COMPANY_SLUG).query(String.class).optional()
				.orElse(null);
		if (id == null) {
			// Base nueva: se crea la empresa (mismo comportamiento que el seed).
			id = Ids.cuid();
			jdbc.sql("""
					INSERT INTO companies (id, name, slug, active, "createdAt", "updatedAt")
					VALUES (:id, :name, :slug, true, :now, :now) ON CONFLICT (slug) DO NOTHING
					""").param("id", id).param("name", Brand.BRAND_NAME).param("slug", DEFAULT_COMPANY_SLUG).param("now", Db.ts(Instant.now())).update();
			id = jdbc.sql("SELECT id FROM companies WHERE slug = :slug").param("slug", DEFAULT_COMPANY_SLUG).query(String.class).single();
		}
		cachedCompanyId = id;
		return id;
	}
}

package com.c21inova.api.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.context.SecurityContextHolder;

import com.c21inova.api.common.ApiException;

/** Usuario autenticado de la petición actual (vuelto a leer de la base en cada petición). */
public record SessionUser(String id, String companyId, String name, String email, String role) {

	/** Acceso por sección del panel (mismo mapa que lib/permissions.ts). */
	public static final Map<String, List<String>> SECTION_ACCESS = Map.of(
			"dashboard", List.of("ADMIN", "DIRECCION", "CONSULTA"),
			"leads", List.of("ADMIN", "DIRECCION"),
			"conversaciones", List.of("ADMIN", "DIRECCION"),
			"asesores", List.of("ADMIN", "DIRECCION"),
			"reportes", List.of("ADMIN", "DIRECCION", "CONSULTA"),
			"propiedades", List.of("ADMIN", "DIRECCION", "CONSULTA"),
			"usuarios", List.of("ADMIN"),
			"integraciones", List.of("ADMIN"),
			"testing", List.of("ADMIN"),
			"configuracion", List.of("ADMIN"));

	public static SessionUser current() {
		Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
				: SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (principal instanceof SessionUser user) return user;
		throw ApiException.unauthorized("No autenticado.");
	}

	/** Lanza 403 si el rol no está entre los indicados. */
	public static SessionUser requireRole(String... roles) {
		SessionUser user = current();
		if (!Arrays.asList(roles).contains(user.role())) {
			throw ApiException.forbidden("Se requiere alguno de estos roles: " + String.join(", ", roles) + ".");
		}
		return user;
	}

	public List<String> sections() {
		return SECTION_ACCESS.entrySet().stream().filter(e -> e.getValue().contains(role)).map(Map.Entry::getKey).sorted().toList();
	}
}

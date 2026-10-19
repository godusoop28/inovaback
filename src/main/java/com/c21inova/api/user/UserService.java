package com.c21inova.api.user;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.AuditService;
import com.c21inova.api.common.Db;
import com.c21inova.api.common.Ids;
import com.c21inova.api.common.Json;

@Service
public class UserService {

	/** Freno de fuerza bruta: tras estos intentos fallidos de un correo en la ventana, todo intento falla. */
	private static final int MAX_FAILED_LOGINS = 10;
	private static final long FAILED_LOGIN_WINDOW_MS = 15 * 60 * 1000;
	private static final String LAST_ADMIN_ERROR = "Debe quedar al menos un ADMIN activo.";

	/** Mismo costo (10) que bcryptjs en el panel: los hashes existentes siguen funcionando. */
	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
	private final JdbcClient jdbc;
	private final AuditService audit;

	public UserService(JdbcClient jdbc, AuditService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	static String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	public Optional<User> findById(String id) {
		return jdbc.sql("SELECT " + User.COLUMNS + " FROM users WHERE id = :id").param("id", id).query(User::map).optional();
	}

	public Optional<User> findByEmail(String email) {
		return jdbc.sql("SELECT " + User.COLUMNS + " FROM users WHERE email = :email").param("email", normalizeEmail(email)).query(User::map).optional();
	}

	private int recentFailedLogins(String email) {
		return jdbc.sql("""
				SELECT count(*)::int FROM audit_logs WHERE "eventType" = 'LOGIN_FAILED' AND message = :email AND "createdAt" >= :since
				""").param("email", email).param("since", Db.ts(Instant.now().minusMillis(FAILED_LOGIN_WINDOW_MS))).query(Integer.class).single();
	}

	/**
	 * Verificación real del login: usuario activo y bcrypt. Nunca distingue
	 * "contraseña incorrecta" de "no existe" para no permitir enumerar cuentas.
	 */
	public Optional<User> verifyLogin(String email, String password) {
		String normalized = normalizeEmail(email);
		if (recentFailedLogins(normalized) >= MAX_FAILED_LOGINS) return Optional.empty();
		User user = findByEmail(normalized).orElse(null);
		boolean valid = user != null && user.active() && user.passwordHash() != null && password != null && encoder.matches(password, user.passwordHash());
		if (!valid) {
			// Sin companyId: no aparece en la actividad del panel.
			audit.log(null, null, null, "LOGIN_FAILED", "warning", normalized);
			return Optional.empty();
		}
		return Optional.of(user);
	}

	public List<User> list(String companyId) {
		return jdbc.sql("SELECT " + User.COLUMNS + " FROM users WHERE \"companyId\" = :c ORDER BY name ASC").param("c", companyId).query(User::map).list();
	}

	public User require(String companyId, String id) {
		User user = findById(id).orElse(null);
		if (user == null || !user.companyId().equals(companyId)) throw ApiException.notFound("Usuario no encontrado.");
		return user;
	}

	public User create(String companyId, String name, String email, String password, String role, boolean active) {
		String id = Ids.cuid();
		Instant now = Instant.now();
		try {
			jdbc.sql("""
					INSERT INTO users (id, "companyId", name, email, "passwordHash", role, active, "createdAt", "updatedAt")
					VALUES (:id, :c, :name, :email, :hash, CAST(:role AS "UserRole"), :active, :now, :now)
					""").param("id", id).param("c", companyId).param("name", name).param("email", normalizeEmail(email))
					.param("hash", encoder.encode(password)).param("role", role).param("active", active).param("now", Db.ts(now)).update();
		} catch (DuplicateKeyException duplicate) {
			throw ApiException.badRequest("Ya existe un usuario con ese correo.");
		}
		User user = findById(id).orElseThrow();
		audit.log(companyId, null, null, "USER_CREATED", "ok", "Usuario creado: " + user.email() + " (" + user.role() + ").");
		return user;
	}

	/** true si es el único ADMIN activo: quitarle el rol o desactivarlo dejaría a nadie gestionando usuarios. */
	public boolean isLastActiveAdmin(String id) {
		User user = findById(id).orElse(null);
		if (user == null || !"ADMIN".equals(user.role()) || !user.active()) return false;
		int others = jdbc.sql("SELECT count(*)::int FROM users WHERE \"companyId\" = :c AND role = 'ADMIN' AND active = true AND id <> :id")
				.param("c", user.companyId()).param("id", id).query(Integer.class).single();
		return others == 0;
	}

	public User update(String companyId, String id, String name, String email, String role, boolean active) {
		require(companyId, id);
		if ((!"ADMIN".equals(role) || !active) && isLastActiveAdmin(id)) throw ApiException.badRequest(LAST_ADMIN_ERROR);
		try {
			jdbc.sql("""
					UPDATE users SET name = :name, email = :email, role = CAST(:role AS "UserRole"), active = :active, "updatedAt" = :now WHERE id = :id
					""").param("name", name).param("email", normalizeEmail(email)).param("role", role).param("active", active)
					.param("now", Db.ts(Instant.now())).param("id", id).update();
		} catch (DuplicateKeyException duplicate) {
			throw ApiException.badRequest("Ya existe un usuario con ese correo.");
		}
		User user = findById(id).orElseThrow();
		audit.log(null, null, null, "USER_UPDATED", "ok", "Usuario actualizado: " + user.email() + ".",
				Json.obj("name", name, "email", normalizeEmail(email), "role", role, "active", active));
		return user;
	}

	public User setActive(String companyId, String id, boolean active) {
		require(companyId, id);
		if (!active && isLastActiveAdmin(id)) throw ApiException.badRequest(LAST_ADMIN_ERROR);
		jdbc.sql("UPDATE users SET active = :active, \"updatedAt\" = :now WHERE id = :id").param("active", active).param("now", Db.ts(Instant.now()))
				.param("id", id).update();
		User user = findById(id).orElseThrow();
		audit.log(null, null, null, "USER_UPDATED", "ok", "Usuario " + user.email() + " " + (active ? "activado" : "desactivado") + ".");
		return user;
	}

	public void resetPassword(String companyId, String id, String password) {
		require(companyId, id);
		jdbc.sql("UPDATE users SET \"passwordHash\" = :hash, \"updatedAt\" = :now WHERE id = :id").param("hash", encoder.encode(password))
				.param("now", Db.ts(Instant.now())).param("id", id).update();
		User user = findById(id).orElseThrow();
		audit.log(null, null, null, "USER_PASSWORD_RESET", "ok", "Contraseña restablecida para " + user.email() + ".");
	}

	public void delete(String companyId, String id, String currentUserId) {
		User user = require(companyId, id);
		if (user.id().equals(currentUserId)) throw ApiException.badRequest("No puedes eliminar tu propia cuenta.");
		if (isLastActiveAdmin(id)) throw ApiException.badRequest(LAST_ADMIN_ERROR);
		jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update();
		audit.log(null, null, null, "USER_DELETED", "ok", "Usuario eliminado: " + user.email() + ".");
	}
}

package com.c21inova.api.user;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.auth.SessionUser;
import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Json;

/** Usuarios del panel: solo ADMIN. */
@RestController
@RequestMapping("/api/users")
public class UserController {

	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
	private static final List<String> ROLES = List.of("ADMIN", "DIRECCION", "CONSULTA");

	private final UserService users;

	public UserController(UserService users) {
		this.users = users;
	}

	public record UserRequest(String nombre, String correo, String password, String rol, Boolean activo) {
	}

	public record PasswordRequest(String password) {
	}

	public record ActiveRequest(Boolean activo) {
	}

	private static void validate(UserRequest body, boolean requirePassword) {
		if (body == null) throw ApiException.badRequest("Cuerpo JSON inválido.");
		if (body.nombre() == null || body.nombre().trim().length() < 2) throw ApiException.unprocessable("El nombre debe tener al menos 2 caracteres.");
		if (body.correo() == null || !EMAIL.matcher(body.correo().trim()).matches()) throw ApiException.unprocessable("Correo inválido.");
		if (body.rol() == null || !ROLES.contains(body.rol())) throw ApiException.unprocessable("Rol inválido.");
		if (requirePassword && (body.password() == null || body.password().length() < 8)) {
			throw ApiException.unprocessable("La contraseña debe tener al menos 8 caracteres.");
		}
	}

	@GetMapping
	public Map<String, Object> list() {
		SessionUser user = SessionUser.requireRole("ADMIN");
		return Json.obj("ok", true, "users", users.list(user.companyId()).stream().map(User::toView).toList());
	}

	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody UserRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		validate(body, true);
		User created = users.create(user.companyId(), body.nombre().trim(), body.correo(), body.password(), body.rol(), Boolean.TRUE.equals(body.activo()));
		return ResponseEntity.status(HttpStatus.CREATED).body(Json.obj("ok", true, "user", created.toView()));
	}

	@PatchMapping("/{id}")
	public Map<String, Object> update(@PathVariable String id, @RequestBody UserRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		validate(body, false);
		return Json.obj("ok", true, "user",
				users.update(user.companyId(), id, body.nombre().trim(), body.correo(), body.rol(), Boolean.TRUE.equals(body.activo())).toView());
	}

	@PatchMapping("/{id}/active")
	public Map<String, Object> setActive(@PathVariable String id, @RequestBody ActiveRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		if (body == null || body.activo() == null) throw ApiException.badRequest("activo es obligatorio.");
		return Json.obj("ok", true, "user", users.setActive(user.companyId(), id, body.activo()).toView());
	}

	@PostMapping("/{id}/password")
	public Map<String, Object> resetPassword(@PathVariable String id, @RequestBody PasswordRequest body) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		if (body == null || body.password() == null || body.password().length() < 8) {
			throw ApiException.unprocessable("La contraseña debe tener al menos 8 caracteres.");
		}
		users.resetPassword(user.companyId(), id, body.password());
		return Json.obj("ok", true);
	}

	@DeleteMapping("/{id}")
	public Map<String, Object> delete(@PathVariable String id) {
		SessionUser user = SessionUser.requireRole("ADMIN");
		users.delete(user.companyId(), id, user.id());
		return Json.obj("ok", true);
	}
}

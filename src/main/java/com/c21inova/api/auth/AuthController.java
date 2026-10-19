package com.c21inova.api.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.c21inova.api.common.ApiException;
import com.c21inova.api.common.Json;
import com.c21inova.api.user.User;
import com.c21inova.api.user.UserService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	public record LoginRequest(String email, String password) {
	}

	private final UserService users;
	private final JwtService jwt;

	public AuthController(UserService users, JwtService jwt) {
		this.users = users;
		this.jwt = jwt;
	}

	/** Correo + contraseña → JWT. Mismo mensaje para cualquier fallo (no permite enumerar cuentas). */
	@PostMapping("/login")
	public Map<String, Object> login(@RequestBody LoginRequest body) {
		if (body == null || body.email() == null || body.password() == null) throw ApiException.badRequest("Correo y contraseña son obligatorios.");
		User user = users.verifyLogin(body.email(), body.password())
				.orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos."));
		return Json.obj("ok", true, "token", jwt.issue(user), "expiresInSeconds", jwt.ttl().toSeconds(), "user", sessionView(user.id(), user.name(),
				user.email(), user.role()));
	}

	@GetMapping("/me")
	public Map<String, Object> me() {
		SessionUser user = SessionUser.current();
		return Json.obj("ok", true, "user", sessionView(user.id(), user.name(), user.email(), user.role()), "sections", user.sections());
	}

	private static Map<String, Object> sessionView(String id, String name, String email, String role) {
		return Json.obj("id", id, "name", name, "email", email, "role", role);
	}
}

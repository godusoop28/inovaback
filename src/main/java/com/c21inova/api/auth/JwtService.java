package com.c21inova.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.c21inova.api.common.Secrets;
import com.c21inova.api.config.Env;
import com.c21inova.api.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Tokens de sesión del panel (HS256). La clave se deriva de JWT_SECRET/AUTH_SECRET con SHA-256 (32 bytes). */
@Service
public class JwtService {

	private final Env env;

	public JwtService(Env env) {
		this.env = env;
	}

	private SecretKey key() {
		return Keys.hmacShaKeyFor(Secrets.sha256(env.jwtSecret()));
	}

	public Duration ttl() {
		String hours = env.optional("JWT_TTL_HOURS");
		return Duration.ofHours(hours != null ? Long.parseLong(hours) : 24 * 7);
	}

	public String issue(User user) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(user.id())
				.claim("role", user.role())
				.claim("email", user.email())
				.claim("name", user.name())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(ttl())))
				.signWith(key())
				.compact();
	}

	/** Devuelve el id del usuario, o null si el token no es válido o venció. */
	public String verify(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
			return claims.getSubject();
		} catch (JwtException | IllegalArgumentException invalid) {
			return null;
		}
	}

	static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}

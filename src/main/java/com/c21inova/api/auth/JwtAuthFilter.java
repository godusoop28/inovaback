package com.c21inova.api.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.c21inova.api.user.User;
import com.c21inova.api.user.UserService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * "Authorization: Bearer <jwt>". El usuario se vuelve a leer de la base:
 * una cuenta desactivada o eliminada pierde acceso de inmediato y un cambio
 * de rol aplica sin esperar a que venza el token.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtService jwt;
	private final UserService users;

	public JwtAuthFilter(JwtService jwt, UserService users) {
		this.jwt = jwt;
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String userId = jwt.verify(header.substring(7).trim());
			if (userId != null) {
				User user = users.findById(userId).orElse(null);
				if (user != null && user.active()) {
					SessionUser session = new SessionUser(user.id(), user.companyId(), user.name(), user.email(), user.role());
					UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(session, null,
							List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
					SecurityContextHolder.getContext().setAuthentication(authentication);
				}
			}
		}
		chain.doFilter(request, response);
	}
}

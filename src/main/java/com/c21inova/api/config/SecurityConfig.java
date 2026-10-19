package com.c21inova.api.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.c21inova.api.auth.JwtAuthFilter;

/**
 * API sin sesión de servidor: el panel manda el JWT en cada petición.
 * Webhooks y crons no usan JWT: validan su propio secreto compartido en el
 * controlador (x-inova-secret / CRON_SECRET).
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
		http.csrf(csrf -> csrf.disable())
				.cors(cors -> {
				})
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, e) -> {
					response.setStatus(HttpStatus.UNAUTHORIZED.value());
					response.setContentType("application/json;charset=UTF-8");
					response.getWriter().write("{\"ok\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"No autenticado.\"}}");
				}))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/", "/api/health", "/api/auth/login", "/api/webhooks/**", "/api/cron/**").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/** El filtro JWT corre solo dentro de la cadena de seguridad, no como filtro de servlet suelto. */
	@Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
		org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
				filter);
		registration.setEnabled(false);
		return registration;
	}

	/** Orígenes del panel: CORS_ORIGINS (coma). Por defecto el panel en Vercel y el entorno local. */
	@Bean
	CorsConfigurationSource corsConfigurationSource(Env env) {
		String raw = env.optional("CORS_ORIGINS");
		List<String> origins = raw != null ? Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
				: List.of("https://webpanelinova-nu.vercel.app", "http://localhost:3000");
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOriginPatterns(origins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setExposedHeaders(List.of("Content-Disposition"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}

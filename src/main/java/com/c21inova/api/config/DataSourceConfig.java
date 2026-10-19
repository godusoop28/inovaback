package com.c21inova.api.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Acepta la misma DATABASE_URL que usa Prisma ("postgresql://usuario:clave@host/db?sslmode=require",
 * la que da Render o Neon) y la convierte a JDBC. JDBC_DATABASE_URL, si existe, se usa tal cual.
 */
@Configuration
public class DataSourceConfig {

	@Bean
	DataSource dataSource(Environment environment) {
		HikariConfig config = new HikariConfig();
		String jdbcUrl = environment.getProperty("JDBC_DATABASE_URL");
		if (jdbcUrl != null && !jdbcUrl.isBlank()) {
			config.setJdbcUrl(jdbcUrl);
		} else {
			String url = environment.getProperty("DATABASE_URL");
			if (url == null || url.isBlank()) {
				throw new IllegalStateException("Falta DATABASE_URL (postgresql://usuario:clave@host/db).");
			}
			JdbcParts parts = toJdbc(url);
			config.setJdbcUrl(parts.jdbcUrl());
			config.setUsername(parts.username());
			config.setPassword(parts.password());
		}
		// Instancias chicas (Render free / basic): pocas conexiones bastan.
		config.setMaximumPoolSize(Integer.parseInt(environment.getProperty("DB_POOL_SIZE", "5")));
		config.setMinimumIdle(1);
		config.setConnectionTimeout(15_000);
		config.setPoolName("inova");
		return new HikariDataSource(config);
	}

	record JdbcParts(String jdbcUrl, String username, String password) {
	}

	static JdbcParts toJdbc(String databaseUrl) {
		URI uri = URI.create(databaseUrl.trim());
		String userInfo = uri.getRawUserInfo();
		String username = null;
		String password = null;
		if (userInfo != null) {
			int colon = userInfo.indexOf(':');
			username = decode(colon >= 0 ? userInfo.substring(0, colon) : userInfo);
			password = colon >= 0 ? decode(userInfo.substring(colon + 1)) : null;
		}
		String host = uri.getHost();
		int port = uri.getPort() > 0 ? uri.getPort() : 5432;
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/postgres" : uri.getRawPath();
		StringBuilder query = new StringBuilder();
		if (uri.getRawQuery() != null) {
			for (String pair : uri.getRawQuery().split("&")) {
				// channel_binding lo entiende libpq; pgjdbc lo acepta, pero se omite por compatibilidad.
				if (pair.startsWith("channel_binding=")) continue;
				if (!query.isEmpty()) query.append('&');
				query.append(pair);
			}
		}
		// Render externo y Neon exigen TLS; el host interno de Render no tiene dominio y no lo necesita.
		if (!query.toString().contains("sslmode=") && host != null && host.contains(".")) {
			if (!query.isEmpty()) query.append('&');
			query.append("sslmode=require");
		}
		String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path + (query.isEmpty() ? "" : "?" + query);
		return new JdbcParts(jdbcUrl, username, password);
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}

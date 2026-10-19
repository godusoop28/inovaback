package com.c21inova.api.conversation;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Descarga acotada de una página pública para identificar una propiedad:
 * solo HTTPS, solo dominios permitidos, destino resuelto por DNS y bloqueado
 * si es privado/local, redirecciones revalidadas una por una, tamaño y
 * tiempo limitados. Sin cookies ni credenciales; no salta bloqueos.
 */
@Component
public class LinkFetcher {

	private static final int MAX_REDIRECTS = 3;
	private static final int MAX_BYTES = 1536 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	public record Result(boolean ok, String finalUrl, String html, String reason) {
		static Result fail(String reason) {
			return new Result(false, null, null, reason);
		}
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

	/** true si la IP (v4 o v6) es privada, local, reservada o de metadatos de nube. */
	public static boolean isPrivateAddress(String address) {
		String ip = address.trim().toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
		Matcher mapped = Pattern.compile("^::ffff:(\\d+\\.\\d+\\.\\d+\\.\\d+)$").matcher(ip);
		if (mapped.matches()) return isPrivateAddress(mapped.group(1));
		Matcher v4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matcher(ip);
		if (v4.matches()) {
			int a = Integer.parseInt(v4.group(1));
			int b = Integer.parseInt(v4.group(2));
			if (a == 0 || a == 10 || a == 127) return true;
			if (a == 169 && b == 254) return true;
			if (a == 172 && b >= 16 && b <= 31) return true;
			if (a == 192 && b == 168) return true;
			if (a == 100 && b >= 64 && b <= 127) return true;
			if (a == 192 && b == 0) return true;
			if (a == 198 && (b == 18 || b == 19)) return true;
			return a >= 224;
		}
		if (ip.equals("::") || ip.equals("::1")) return true;
		if (ip.startsWith("fe80") || ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("ff")) return true;
		if (ip.contains(":")) return false;
		return true;
	}

	public static boolean isAllowedHost(String hostname, List<String> allowedDomains) {
		String host = hostname.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
		return allowedDomains.stream().map(d -> d.toLowerCase(Locale.ROOT).trim()).anyMatch(d -> !d.isEmpty() && (host.equals(d) || host.endsWith("." + d)));
	}

	/** ok → url; si no, motivo (invalid_url, only_https, credentials_in_url, port_not_allowed, domain_not_supported). */
	static Object checkFetchable(String rawUrl, List<String> allowedDomains) {
		URI uri;
		try {
			uri = new URI(rawUrl);
		} catch (Exception e) {
			return "invalid_url";
		}
		if (uri.getScheme() == null || uri.getHost() == null) return "invalid_url";
		if (!"https".equalsIgnoreCase(uri.getScheme())) return "only_https";
		if (uri.getRawUserInfo() != null) return "credentials_in_url";
		if (uri.getPort() != -1 && uri.getPort() != 443) return "port_not_allowed";
		if (!isAllowedHost(uri.getHost(), allowedDomains)) return "domain_not_supported";
		return uri;
	}

	public Result fetch(String rawUrl, List<String> allowedDomains) {
		String current = rawUrl;
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			Object check = checkFetchable(current, allowedDomains);
			if (check instanceof String reason) return Result.fail(reason);
			URI uri = (URI) check;
			try {
				InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
				if (addresses.length == 0) return Result.fail("private_address");
				for (InetAddress address : addresses) if (isPrivateAddress(address.getHostAddress())) return Result.fail("private_address");
			} catch (UnknownHostException e) {
				return Result.fail("dns_failed");
			}
			try {
				HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("accept", "text/html")
						.header("user-agent", "Century21InovaAssistant/1.0 (+identificacion de propiedades)").GET().build();
				HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
				int status = response.statusCode();
				if (status >= 300 && status < 400) {
					response.body().close();
					String location = response.headers().firstValue("location").orElse(null);
					if (location == null) return Result.fail("bad_redirect");
					current = uri.resolve(location).toString();
					continue;
				}
				if (status < 200 || status >= 300) {
					response.body().close();
					return Result.fail("http_" + status);
				}
				String type = response.headers().firstValue("content-type").orElse("");
				if (!type.contains("text/html")) {
					response.body().close();
					return Result.fail("not_html");
				}
				byte[] bytes;
				try (InputStream body = response.body()) {
					bytes = body.readNBytes(MAX_BYTES);
				}
				return new Result(true, uri.toString(), new String(bytes, StandardCharsets.UTF_8), null);
			} catch (HttpTimeoutException e) {
				return Result.fail("timeout");
			} catch (IOException e) {
				return Result.fail("fetch_failed");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return Result.fail("fetch_failed");
			} catch (IllegalArgumentException e) {
				return Result.fail("invalid_url");
			}
		}
		return Result.fail("too_many_redirects");
	}
}

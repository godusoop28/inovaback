package com.c21inova.api.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> api(ApiException error) {
		return ResponseEntity.status(error.status()).body(body(error.code(), error.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException error) {
		return ResponseEntity.badRequest().body(body("INVALID_BODY", "Cuerpo JSON inválido."));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException error) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", "Ruta no encontrada."));
	}

	/** Reglas de negocio que el servicio rechaza con un mensaje para el usuario (p. ej. "Asesor no encontrado."). */
	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, Object>> rejected(IllegalArgumentException error) {
		return ResponseEntity.badRequest().body(body("REJECTED", error.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> unexpected(Exception error) {
		log.error("[API] error no controlado", error);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body("INTERNAL_ERROR", "Error interno del servidor."));
	}

	static Map<String, Object> body(String code, String message) {
		return Json.obj("ok", false, "error", Json.obj("code", code, "message", message));
	}
}

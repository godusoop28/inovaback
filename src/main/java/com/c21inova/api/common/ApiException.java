package com.c21inova.api.common;

import org.springframework.http.HttpStatus;

/** Error con código y mensaje en español para el panel: { ok:false, error:{ code, message } }. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY", message);
	}

	public static ApiException unprocessable(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BODY", message);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
	}

	public static ApiException forbidden(String message) {
		return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
	}

	public static ApiException unauthorized(String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
	}
}

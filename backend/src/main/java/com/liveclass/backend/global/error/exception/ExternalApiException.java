package com.liveclass.backend.global.error.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public class ExternalApiException extends RuntimeException {

	private final String code;
	private final HttpStatus httpStatus;

	public ExternalApiException(String code, String message, HttpStatus httpStatus) {
		super(message);
		this.code = code;
		this.httpStatus = httpStatus;
	}
}

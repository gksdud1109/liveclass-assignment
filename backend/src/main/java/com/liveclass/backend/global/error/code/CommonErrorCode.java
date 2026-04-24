package com.liveclass.backend.global.error.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {

	NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 리소스를 찾을 수 없습니다."),

	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),

	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "유효하지 않은 입력 값입니다."),
	MISSING_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, "필수 요청 파라미터가 누락되었습니다."),
	TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "요청 타입이 올바르지 않습니다."),
	MESSAGE_NOT_READABLE(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),

	DUPLICATE_KEY(HttpStatus.CONFLICT, "데이터가 이미 존재합니다."),
	OPTIMISTIC_LOCK_FAILURE(HttpStatus.CONFLICT, "데이터 충돌이 발생했습니다. 다시 시도해주세요."),
	EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 API 호출에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String message;
}

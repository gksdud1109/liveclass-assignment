package com.liveclass.backend.global.response;

import com.liveclass.backend.global.error.code.ErrorCode;
import com.liveclass.backend.global.error.exception.BusinessException;

public record ErrorResponse(
	int status,
	String code,
	String message
) {
	public static ErrorResponse from(BusinessException ex){
		ErrorCode errorCode = ex.getErrorCode();
		return new ErrorResponse(
			errorCode.getHttpStatus().value(),
			toCodeName(errorCode),
			ex.getMessage()
		);
	}

	public static ErrorResponse from(ErrorCode errorCode){
		return new ErrorResponse(
			errorCode.getHttpStatus().value(),
			toCodeName(errorCode),
			errorCode.getMessage()
		);
	}

	private static String toCodeName(ErrorCode errorCode) {
		return (errorCode instanceof Enum<?> e) ? e.name() : errorCode.getClass().getSimpleName();
	}
}

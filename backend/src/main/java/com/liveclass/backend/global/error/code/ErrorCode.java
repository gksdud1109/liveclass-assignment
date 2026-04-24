package com.liveclass.backend.global.error.code;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

	HttpStatus getHttpStatus();
	String getMessage();
}

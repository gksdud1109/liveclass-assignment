package com.liveclass.backend.notification.exception;

import org.springframework.http.HttpStatus;

import com.liveclass.backend.global.error.code.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
	NOT_DEAD_LETTER(HttpStatus.BAD_REQUEST, "DEAD_LETTER 상태의 알림만 수동 재시도가 가능합니다.");

	private final HttpStatus httpStatus;
	private final String message;
}

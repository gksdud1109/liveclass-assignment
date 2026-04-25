package com.liveclass.backend.notification.sender;

public class NotificationSendException extends RuntimeException {

	public NotificationSendException(String message) {
		super(message);
	}

	public NotificationSendException(String message, Throwable cause) {
		super(message, cause);
	}
}

package com.liveclass.backend.notification.dto;

import java.time.LocalDateTime;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;

public record NotificationResponse(
	Long id,
	String recipientId,
	NotificationType notificationType,
	String referenceId,
	NotificationChannel channel,
	NotificationStatus status,
	String renderedTitle,
	String renderedBody,
	int retryCount,
	LocalDateTime nextAttemptAt,
	LocalDateTime scheduledAt,
	LocalDateTime sentAt,
	LocalDateTime readAt,
	String lastError,
	LocalDateTime createdAt
) {

	public static NotificationResponse from(Notification n) {
		return new NotificationResponse(
			n.getId(),
			n.getRecipientId(),
			n.getNotificationType(),
			n.getReferenceId(),
			n.getChannel(),
			n.getStatus(),
			n.getRenderedTitle(),
			n.getRenderedBody(),
			n.getRetryCount(),
			n.getNextAttemptAt(),
			n.getScheduledAt(),
			n.getSentAt(),
			n.getReadAt(),
			n.getLastError(),
			n.getCreatedAt()
		);
	}
}

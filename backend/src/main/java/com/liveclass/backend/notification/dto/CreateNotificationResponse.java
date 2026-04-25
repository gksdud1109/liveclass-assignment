package com.liveclass.backend.notification.dto;

import java.time.LocalDateTime;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationStatus;

public record CreateNotificationResponse(
	Long id,
	NotificationStatus status,
	LocalDateTime createdAt,
	boolean duplicate
) {

	public static CreateNotificationResponse of(Notification n, boolean duplicate) {
		return new CreateNotificationResponse(n.getId(), n.getStatus(), n.getCreatedAt(), duplicate);
	}
}

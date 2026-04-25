package com.liveclass.backend.notification.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateNotificationRequest(
	@NotBlank @Size(max = 64) String recipientId,
	@NotNull NotificationType notificationType,
	@NotBlank @Size(max = 64) String referenceId,
	@NotNull NotificationChannel channel,
	Map<String, Object> payload,
	LocalDateTime scheduledAt
) {
}

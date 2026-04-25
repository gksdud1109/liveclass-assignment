package com.liveclass.backend.notification.template;

import java.util.Map;

import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationType;

public interface NotificationTemplateRenderer {

	RenderedNotification render(
		NotificationType type,
		NotificationChannel channel,
		Map<String, Object> payload
	);
}

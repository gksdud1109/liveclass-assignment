package com.liveclass.backend.notification.sender;

import org.springframework.stereotype.Component;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.template.RenderedNotification;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class InAppSender implements NotificationSender {

	@Override
	public NotificationChannel supports() {
		return NotificationChannel.IN_APP;
	}

	@Override
	public void send(Notification notification, RenderedNotification rendered) {
		log.info("[IN_APP] to={} title='{}' body='{}'",
			notification.getRecipientId(), rendered.title(), rendered.body());
	}
}

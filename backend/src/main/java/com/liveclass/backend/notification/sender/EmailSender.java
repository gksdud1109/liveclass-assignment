package com.liveclass.backend.notification.sender;

import org.springframework.stereotype.Component;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.template.RenderedNotification;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EmailSender implements NotificationSender {

	@Override
	public NotificationChannel supports() {
		return NotificationChannel.EMAIL;
	}

	@Override
	public void send(Notification notification, RenderedNotification rendered) {
		log.info("[EMAIL] to={} subject='{}' body='{}'",
			notification.getRecipientId(), rendered.title(), rendered.body());
	}
}

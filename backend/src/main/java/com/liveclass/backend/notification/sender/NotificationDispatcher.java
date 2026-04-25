package com.liveclass.backend.notification.sender;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.template.RenderedNotification;

@Component
public class NotificationDispatcher {

	private final Map<NotificationChannel, NotificationSender> senders;

	public NotificationDispatcher(List<NotificationSender> senderList) {
		this.senders = senderList.stream()
			.collect(Collectors.toMap(NotificationSender::supports, Function.identity()));
	}

	public void send(Notification notification, RenderedNotification rendered) {
		NotificationSender sender = senders.get(notification.getChannel());
		if (sender == null) {
			throw new IllegalStateException(
				"No sender configured for channel: " + notification.getChannel());
		}
		sender.send(notification, rendered);
	}
}

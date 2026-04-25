package com.liveclass.backend.notification.sender;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.template.RenderedNotification;

public interface NotificationSender {

	NotificationChannel supports();

	void send(Notification notification, RenderedNotification rendered);
}

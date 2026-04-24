package com.liveclass.backend.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.liveclass.backend.notification.domain.NotificationAttempt;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, Long> {

	List<NotificationAttempt> findByNotificationIdOrderByAttemptNoAsc(Long notificationId);
}

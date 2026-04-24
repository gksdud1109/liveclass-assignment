package com.liveclass.backend.notification.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	Optional<Notification> findByRecipientIdAndNotificationTypeAndReferenceIdAndChannel(
		String recipientId,
		NotificationType notificationType,
		String referenceId,
		NotificationChannel channel
	);

	@Query("""
		SELECT n FROM Notification n
		WHERE n.recipientId = :recipientId
		  AND (:status IS NULL OR n.status = :status)
		  AND (:channel IS NULL OR n.channel = :channel)
		  AND (:type IS NULL OR n.notificationType = :type)
		  AND (:readFilter IS NULL
		       OR (:readFilter = true AND n.readAt IS NOT NULL)
		       OR (:readFilter = false AND n.readAt IS NULL))
		ORDER BY n.createdAt DESC
		""")
	Page<Notification> search(
		@Param("recipientId") String recipientId,
		@Param("status") NotificationStatus status,
		@Param("channel") NotificationChannel channel,
		@Param("type") NotificationType type,
		@Param("readFilter") Boolean readFilter,
		Pageable pageable
	);
}

package com.liveclass.backend.notification.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/**
	 * 다중 워커 인스턴스 환경에서 안전한 batch claim용 ID 조회.
	 *
	 * <p>SELECT...FOR UPDATE SKIP LOCKED — 이미 다른 트랜잭션이 락을 잡은 행은 건너뛰고
	 * 가용한 PENDING 행만 가져옴. 동일 트랜잭션 내에서 후속 markProcessing UPDATE까지 함께 묶어
	 * COMMIT 시점에 락 해제되도록 한다.
	 */
	@Query(value = """
		SELECT id FROM notification
		WHERE status = 'PENDING' AND next_attempt_at <= :now
		ORDER BY next_attempt_at
		LIMIT :batchSize
		FOR UPDATE SKIP LOCKED
		""", nativeQuery = true)
	List<Long> findClaimableIds(
		@Param("now") LocalDateTime now,
		@Param("batchSize") int batchSize
	);

	@Modifying
	@Query("""
		UPDATE Notification n
		SET n.status = :status,
		    n.processingStartedAt = :now,
		    n.workerId = :workerId
		WHERE n.id IN :ids
		""")
	int markProcessing(
		@Param("status") NotificationStatus status,
		@Param("now") LocalDateTime now,
		@Param("workerId") String workerId,
		@Param("ids") List<Long> ids
	);
}

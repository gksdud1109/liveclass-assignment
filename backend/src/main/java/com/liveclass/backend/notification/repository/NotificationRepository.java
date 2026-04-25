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
	 * 다중 워커 환경에서 batch claim용 PENDING ID 조회.
	 * SKIP LOCKED로 다른 워커가 잡은 행은 건너뛰며, 같은 트랜잭션의 markProcessing 까지 묶어 COMMIT 시 락 해제.
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
		WHERE n.id IN :ids AND n.status = :expectedStatus
		""")
	int markProcessing(
		@Param("status") NotificationStatus status,
		@Param("expectedStatus") NotificationStatus expectedStatus,
		@Param("now") LocalDateTime now,
		@Param("workerId") String workerId,
		@Param("ids") List<Long> ids
	);

	/**
	 * 임계치 초과 PROCESSING 행을 복구한다.
	 * retry_count를 1 증가시키고 maxRetry 도달 시 DEAD_LETTER로 전이 — 무한 중복 발송 루프 차단을 위한 보수적 정책.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE Notification n
		SET n.retryCount = n.retryCount + 1,
		    n.status = CASE WHEN n.retryCount + 1 >= n.maxRetry
		                    THEN :deadLetterStatus
		                    ELSE :pendingStatus END,
		    n.nextAttemptAt = :now,
		    n.processingStartedAt = null,
		    n.workerId = null,
		    n.lastError = :reason
		WHERE n.status = :processingStatus
		  AND n.processingStartedAt < :threshold
		""")
	int recoverStuck(
		@Param("processingStatus") NotificationStatus processingStatus,
		@Param("pendingStatus") NotificationStatus pendingStatus,
		@Param("deadLetterStatus") NotificationStatus deadLetterStatus,
		@Param("threshold") LocalDateTime threshold,
		@Param("now") LocalDateTime now,
		@Param("reason") String reason
	);

	/**
	 * 동시 읽음 처리 멱등성: COALESCE로 read_at의 최초 값만 유지.
	 * 여러 기기에서 동시에 호출되어도 첫 호출의 시각만 저장된다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE notification SET read_at = COALESCE(read_at, :now) WHERE id = :id",
		nativeQuery = true)
	int markRead(@Param("id") Long id, @Param("now") LocalDateTime now);
}

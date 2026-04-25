package com.liveclass.backend.notification.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.liveclass.backend.global.error.exception.BusinessException;
import com.liveclass.backend.notification.domain.AttemptResult;
import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationAttempt;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.exception.NotificationErrorCode;
import com.liveclass.backend.notification.policy.RetryPolicy;
import com.liveclass.backend.notification.repository.NotificationAttemptRepository;
import com.liveclass.backend.notification.repository.NotificationRepository;
import com.liveclass.backend.notification.template.RenderedNotification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 워커가 호출하는 비동기 처리 트랜잭션 경계 모음.
 * 외부 발송은 트랜잭션 밖에서, DB 상태 전이만 짧은 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProcessingService {

	private final NotificationRepository notificationRepository;
	private final NotificationAttemptRepository attemptRepository;
	private final RetryPolicy retryPolicy;

	/**
	 * PENDING 행을 batchSize 만큼 PROCESSING으로 claim하고 ID를 반환한다.
	 * SELECT FOR UPDATE SKIP LOCKED와 UPDATE를 같은 트랜잭션에 묶어 다중 워커 중복 claim을 방지한다.
	 */
	@Transactional
	public List<Long> claimBatch(String workerId, int batchSize) {
		LocalDateTime now = LocalDateTime.now();
		List<Long> ids = notificationRepository.findClaimableIds(now, batchSize);
		if (ids.isEmpty()) {
			return List.of();
		}
		notificationRepository.markProcessing(
			NotificationStatus.PROCESSING,
			NotificationStatus.PENDING,
			now,
			workerId,
			ids
		);
		log.debug("Claimed {} notifications by worker={}", ids.size(), workerId);
		return ids;
	}

	@Transactional(readOnly = true)
	public Notification fetchOwnedForProcessing(Long id, String expectedWorkerId) {
		Notification notification = notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		if (!isOwnedBy(notification, expectedWorkerId)) {
			log.warn("Worker {} attempted fetch on id={} now owned by {} — skipping send",
				expectedWorkerId, id, notification.getWorkerId());
			return null;
		}
		return notification;
	}

	/**
	 * 호출자 worker의 ID가 row의 현재 worker_id와 일치할 때만 결과를 반영한다.
	 * 불일치는 sweeper 복구 후 다른 워커가 점유 중인 상황이므로 양보한다 (attempt_no 충돌, 간섭 방지).
	 */
	@Transactional
	public void recordSuccess(Long id, String expectedWorkerId, RenderedNotification rendered) {
		Notification notification = notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		if (!isOwnedBy(notification, expectedWorkerId)) {
			log.warn("Worker {} attempted recordSuccess on id={} now owned by {} — ignoring",
				expectedWorkerId, id, notification.getWorkerId());
			return;
		}
		int attemptNo = notification.getRetryCount() + 1;
		notification.markSent(rendered.title(), rendered.body());
		recordAttempt(id, attemptNo, AttemptResult.SUCCESS, null);
	}

	@Transactional
	public void recordFailure(Long id, String expectedWorkerId, String errorMessage) {
		Notification notification = notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		if (!isOwnedBy(notification, expectedWorkerId)) {
			log.warn("Worker {} attempted recordFailure on id={} now owned by {} — ignoring",
				expectedWorkerId, id, notification.getWorkerId());
			return;
		}
		int attemptNo = notification.getRetryCount() + 1;
		LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(
			notification.getRetryCount(), LocalDateTime.now());
		notification.markFailed(errorMessage, nextAttemptAt);
		recordAttempt(id, attemptNo, AttemptResult.FAILED, errorMessage);
	}

	private boolean isOwnedBy(Notification notification, String expectedWorkerId) {
		return expectedWorkerId != null && expectedWorkerId.equals(notification.getWorkerId());
	}

	private void recordAttempt(Long notificationId, int attemptNo, AttemptResult result, String errorMessage) {
		NotificationAttempt attempt = NotificationAttempt.started(notificationId, attemptNo);
		if (result == AttemptResult.SUCCESS) {
			attempt.succeed();
		} else {
			attempt.fail(errorMessage);
		}
		attemptRepository.save(attempt);
	}
}

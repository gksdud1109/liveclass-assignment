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
 *
 * <p>설계 원칙: 외부 발송(네트워크 I/O)은 트랜잭션 밖에서 수행하고,
 * DB 상태 전이(claim / 결과 반영)만 짧은 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProcessingService {

	private final NotificationRepository notificationRepository;
	private final NotificationAttemptRepository attemptRepository;
	private final RetryPolicy retryPolicy;

	/**
	 * batchSize 만큼 PENDING 행을 PROCESSING으로 전환하며 ID를 반환한다.
	 *
	 * <p>SELECT...FOR UPDATE SKIP LOCKED + UPDATE를 같은 트랜잭션에 묶음.
	 * COMMIT 전까지 락이 유지되어 같은 행을 다른 워커가 집을 수 없다.
	 */
	@Transactional
	public List<Long> claimBatch(String workerId, int batchSize) {
		LocalDateTime now = LocalDateTime.now();
		List<Long> ids = notificationRepository.findClaimableIds(now, batchSize);
		if (ids.isEmpty()) {
			return List.of();
		}
		notificationRepository.markProcessing(NotificationStatus.PROCESSING, now, workerId, ids);
		log.debug("Claimed {} notifications by worker={}", ids.size(), workerId);
		return ids;
	}

	@Transactional(readOnly = true)
	public Notification fetchForProcessing(Long id) {
		return notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
	}

	@Transactional
	public void recordSuccess(Long id, RenderedNotification rendered) {
		Notification notification = notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		int attemptNo = notification.getRetryCount() + 1;
		notification.markSent(rendered.title(), rendered.body());
		recordAttempt(id, attemptNo, AttemptResult.SUCCESS, null);
	}

	@Transactional
	public void recordFailure(Long id, String errorMessage) {
		Notification notification = notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
		int attemptNo = notification.getRetryCount() + 1;
		LocalDateTime nextAttemptAt = retryPolicy.nextAttemptAt(
			notification.getRetryCount(), LocalDateTime.now());
		notification.markFailed(errorMessage, nextAttemptAt);
		recordAttempt(id, attemptNo, AttemptResult.FAILED, errorMessage);
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

package com.liveclass.backend.notification.sweeper;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 임계치 초과 PROCESSING 알림을 복구하는 컴포넌트.
 * 워커 크래시, 결과 기록 실패 등 PROCESSING이 영구화되는 모든 상황을 처리한다.
 *
 * 정책: 복구 시 retry_count를 1 증가시키고 maxRetry 도달 시 DEAD_LETTER로 전이.
 * 워커 크래시와 recordSuccess 실패를 DB만 보고는 구분 불가하므로 보수적으로 카운트하여 무한 중복 발송을 차단한다.
 *
 * 한계: send 성공 후 recordSuccess 실패한 행은 maxRetry-retry_count 회까지 중복 발송 가능.
 * exactly-once는 외부 채널의 idempotency 키 없이는 불가하며, 본 시스템은 at-least-once를 채택한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckNotificationSweeper {

	private static final String STUCK_RECOVERY_REASON =
		"stuck recovery: processing exceeded threshold";

	private final NotificationRepository notificationRepository;

	@Value("${notification.sweeper.threshold-seconds:300}")
	private long thresholdSeconds;

	@Transactional
	public int recoverStuckNotifications() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime threshold = now.minusSeconds(thresholdSeconds);
		int recovered = notificationRepository.recoverStuck(
			NotificationStatus.PROCESSING,
			NotificationStatus.PENDING,
			NotificationStatus.DEAD_LETTER,
			threshold,
			now,
			STUCK_RECOVERY_REASON
		);
		if (recovered > 0) {
			log.warn("Recovered {} stuck notifications (threshold {}s)",
				recovered, thresholdSeconds);
		}
		return recovered;
	}
}

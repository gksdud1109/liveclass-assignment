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
 * PROCESSING 상태로 멈춰있는 알림을 임계치 초과 시 복구하는 컴포넌트.
 *
 * <p>워커 인스턴스 크래시, 결과 기록(recordSuccess/Failure) 실패, 발송 성공 후 DB 반영 실패 등
 * PROCESSING이 영구화되는 모든 상황을 막는다.
 *
 * <p><b>retry_count 증가 정책:</b> 복구 시 retry_count를 1 증가시킨다. 워커 크래시와
 * "발송 성공 후 recordSuccess 실패"는 DB만 보고는 구분 불가하므로, 무한 복구 → 무한 중복 발송
 * 가능성을 차단하기 위해 보수적으로 1회 실패 카운트. 정확한 실패 횟수보다 무한 루프 방지를 우선.
 * maxRetry 도달 시 DEAD_LETTER로 전이.
 *
 * <p><b>본질적 한계:</b> send 성공 후 recordSuccess 실패 행은 복구 시 PENDING 복귀되어
 * 1회 중복 발송될 수 있음 (단, 위 정책 덕분에 무한이 아닌 maxRetry-retry_count 회로 제한).
 * 진정한 exactly-once는 외부 채널의 idempotency 키 없이는 분산환경에서 본질적 해소 불가.
 * 본 시스템은 at-least-once 의미를 채택.
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

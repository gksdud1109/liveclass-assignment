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
 * PROCESSING 상태로 멈춰있는 알림을 임계치 초과 시 PENDING으로 되돌리는 복구 로직.
 *
 * <p>워커 인스턴스 크래시 또는 결과 기록(recordSuccess/Failure) 실패로 PROCESSING이 영구화되는
 * 상황을 막는다. retry_count는 증가시키지 않음 — 워커 크래시는 발송 실패와 다르므로.
 *
 * <p><b>알려진 한계</b>: send 자체는 성공했으나 recordSuccess가 실패한 행도 동일하게 PENDING으로
 * 복귀되어 사용자에게 중복 발송될 수 있음. 외부 채널의 idempotency 키 없이는 분산 환경에서
 * 본질적으로 해소 불가하며, 본 시스템은 at-least-once 의미를 채택한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckNotificationSweeper {

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
			threshold,
			now
		);
		if (recovered > 0) {
			log.warn("Recovered {} stuck notifications (threshold {}s)",
				recovered, thresholdSeconds);
		}
		return recovered;
	}
}

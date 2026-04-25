package com.liveclass.backend.notification.sweeper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.repository.NotificationRepository;
import com.liveclass.backend.support.PostgresContainerTest;

@SpringBootTest(properties = {
	"notification.worker.scheduling-enabled=false",
	"notification.sweeper.scheduling-enabled=false",
	"notification.sweeper.threshold-seconds=300"
})
class StuckNotificationSweeperIntegrationTest extends PostgresContainerTest {

	@Autowired
	private StuckNotificationSweeper sweeper;

	@Autowired
	private NotificationRepository notificationRepository;

	@AfterEach
	void cleanup() {
		notificationRepository.deleteAll();
	}

	@Test
	void recoverStuckNotifications_resetsOldProcessingRowsToPending_andBumpsRetryCount() {
		Notification stuck = persistProcessingRow(
			"user-stuck",
			LocalDateTime.now().minusMinutes(10),
			"worker-old",
			3
		);
		Notification fresh = persistProcessingRow(
			"user-fresh",
			LocalDateTime.now().minusSeconds(30),
			"worker-fresh",
			1
		);

		int recovered = sweeper.recoverStuckNotifications();

		assertThat(recovered).isEqualTo(1);

		Notification reloadedStuck = notificationRepository.findById(stuck.getId()).orElseThrow();
		assertThat(reloadedStuck.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(reloadedStuck.getProcessingStartedAt()).isNull();
		assertThat(reloadedStuck.getWorkerId()).isNull();
		assertThat(reloadedStuck.getRetryCount())
			.as("retry_count bumps on recovery — conservative policy to prevent infinite duplicate-send loops")
			.isEqualTo(4);
		assertThat(reloadedStuck.getLastError()).contains("stuck recovery");
		assertThat(reloadedStuck.getNextAttemptAt()).isAfterOrEqualTo(LocalDateTime.now().minusSeconds(2));

		Notification reloadedFresh = notificationRepository.findById(fresh.getId()).orElseThrow();
		assertThat(reloadedFresh.getStatus())
			.as("rows below threshold should remain PROCESSING")
			.isEqualTo(NotificationStatus.PROCESSING);
		assertThat(reloadedFresh.getWorkerId()).isEqualTo("worker-fresh");
		assertThat(reloadedFresh.getRetryCount()).isEqualTo(1);
	}

	@Test
	void recoverStuckNotifications_noStuckRows_returnsZero() {
		persistProcessingRow("user-fresh", LocalDateTime.now().minusSeconds(10), "worker-1", 0);

		int recovered = sweeper.recoverStuckNotifications();

		assertThat(recovered).isEqualTo(0);
	}

	@Test
	void recoverStuckNotifications_atMaxRetryThreshold_transitionsToDeadLetter() {
		// retry_count=4 + sweep 시 +1 = 5 = maxRetry → DEAD_LETTER 전이
		// 무한 복구 → 무한 중복 발송 루프 차단을 보장하는 핵심 테스트
		Notification stuck = persistProcessingRow(
			"user-loop-victim",
			LocalDateTime.now().minusMinutes(10),
			"worker-stale",
			4
		);

		int recovered = sweeper.recoverStuckNotifications();

		assertThat(recovered).isEqualTo(1);

		Notification reloaded = notificationRepository.findById(stuck.getId()).orElseThrow();
		assertThat(reloaded.getStatus())
			.as("repeated stuck recovery must terminate at DEAD_LETTER, not loop forever")
			.isEqualTo(NotificationStatus.DEAD_LETTER);
		assertThat(reloaded.getRetryCount()).isEqualTo(5);
		assertThat(reloaded.getProcessingStartedAt()).isNull();
		assertThat(reloaded.getWorkerId()).isNull();
		assertThat(reloaded.getLastError()).contains("stuck recovery");
	}

	private Notification persistProcessingRow(
		String recipientId,
		LocalDateTime processingStartedAt,
		String workerId,
		int retryCount
	) {
		Notification n = notificationRepository.saveAndFlush(Notification.create(
			recipientId,
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-" + recipientId,
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));
		ReflectionTestUtils.setField(n, "status", NotificationStatus.PROCESSING);
		ReflectionTestUtils.setField(n, "processingStartedAt", processingStartedAt);
		ReflectionTestUtils.setField(n, "workerId", workerId);
		ReflectionTestUtils.setField(n, "retryCount", retryCount);
		return notificationRepository.saveAndFlush(n);
	}
}

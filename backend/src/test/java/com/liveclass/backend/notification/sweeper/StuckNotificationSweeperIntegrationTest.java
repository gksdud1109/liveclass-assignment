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
	void recoverStuckNotifications_resetsOldProcessingRowsToPending() {
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
			.as("retry_count must NOT bump on recovery — worker crash != send failure")
			.isEqualTo(3);
		assertThat(reloadedStuck.getNextAttemptAt()).isAfterOrEqualTo(LocalDateTime.now().minusSeconds(2));

		Notification reloadedFresh = notificationRepository.findById(fresh.getId()).orElseThrow();
		assertThat(reloadedFresh.getStatus())
			.as("rows below threshold should remain PROCESSING")
			.isEqualTo(NotificationStatus.PROCESSING);
		assertThat(reloadedFresh.getWorkerId()).isEqualTo("worker-fresh");
	}

	@Test
	void recoverStuckNotifications_noStuckRows_returnsZero() {
		persistProcessingRow("user-fresh", LocalDateTime.now().minusSeconds(10), "worker-1", 0);

		int recovered = sweeper.recoverStuckNotifications();

		assertThat(recovered).isEqualTo(0);
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

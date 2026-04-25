package com.liveclass.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationTest {

	@Test
	void resetForManualRetry_nonDeadLetter_throws() {
		Notification n = newPendingNotification();

		assertThatThrownBy(() -> n.resetForManualRetry(false))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DEAD_LETTER");
	}

	@Test
	void resetForManualRetry_deadLetter_resetsToPending() {
		Notification n = newPendingNotification();
		ReflectionTestUtils.setField(n, "status", NotificationStatus.DEAD_LETTER);
		ReflectionTestUtils.setField(n, "retryCount", 5);
		ReflectionTestUtils.setField(n, "lastError", "max retry exceeded");

		n.resetForManualRetry(false);

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(n.getRetryCount()).isEqualTo(5);
		assertThat(n.getLastError()).isNull();
	}

	@Test
	void resetForManualRetry_withResetCount_clearsRetryCount() {
		Notification n = newPendingNotification();
		ReflectionTestUtils.setField(n, "status", NotificationStatus.DEAD_LETTER);
		ReflectionTestUtils.setField(n, "retryCount", 5);

		n.resetForManualRetry(true);

		assertThat(n.getRetryCount()).isEqualTo(0);
	}

	private Notification newPendingNotification() {
		return Notification.create(
			"user-test",
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-test",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test"),
			null
		);
	}
}

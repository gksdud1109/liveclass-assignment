package com.liveclass.backend.notification.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

	private final RetryPolicy policy = new RetryPolicy();

	@Test
	void backoff_grows_exponentially_until_cap() {
		assertThat(policy.backoff(0)).isEqualTo(Duration.ofSeconds(60));
		assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(120));
		assertThat(policy.backoff(2)).isEqualTo(Duration.ofSeconds(240));
		assertThat(policy.backoff(3)).isEqualTo(Duration.ofSeconds(480));
		assertThat(policy.backoff(4)).isEqualTo(Duration.ofSeconds(960));
		assertThat(policy.backoff(5)).isEqualTo(Duration.ofSeconds(1920));
	}

	@Test
	void backoff_capsAt_oneHour() {
		assertThat(policy.backoff(6)).isEqualTo(Duration.ofHours(1));
		assertThat(policy.backoff(20)).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void backoff_largeRetryCount_doesNotOverflow() {
		assertThat(policy.backoff(100)).isEqualTo(Duration.ofHours(1));
	}

	@Test
	void backoff_negativeRetryCount_throws() {
		assertThatThrownBy(() -> policy.backoff(-1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nextAttemptAt_addsBackoffToFromTime() {
		LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);

		assertThat(policy.nextAttemptAt(0, from)).isEqualTo(from.plusSeconds(60));
		assertThat(policy.nextAttemptAt(2, from)).isEqualTo(from.plusSeconds(240));
		assertThat(policy.nextAttemptAt(10, from)).isEqualTo(from.plusHours(1));
	}
}

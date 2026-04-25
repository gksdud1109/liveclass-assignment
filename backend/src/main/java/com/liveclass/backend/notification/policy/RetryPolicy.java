package com.liveclass.backend.notification.policy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {

	private static final Duration BASE_BACKOFF = Duration.ofSeconds(60);
	private static final Duration MAX_BACKOFF = Duration.ofHours(1);
	private static final int OVERFLOW_GUARD = 30;

	public Duration backoff(int retryCount) {
		if (retryCount < 0) {
			throw new IllegalArgumentException("retryCount must be >= 0, got " + retryCount);
		}
		if (retryCount >= OVERFLOW_GUARD) {
			return MAX_BACKOFF;
		}
		long seconds = Math.min(
			BASE_BACKOFF.toSeconds() << retryCount,
			MAX_BACKOFF.toSeconds()
		);
		return Duration.ofSeconds(seconds);
	}

	public LocalDateTime nextAttemptAt(int retryCount, LocalDateTime from) {
		return from.plus(backoff(retryCount));
	}
}

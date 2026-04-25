package com.liveclass.backend.notification.sweeper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@ConditionalOnProperty(
	name = "notification.sweeper.scheduling-enabled",
	havingValue = "true",
	matchIfMissing = true
)
@RequiredArgsConstructor
public class StuckNotificationSweeperScheduler {

	private final StuckNotificationSweeper sweeper;

	@Scheduled(fixedDelayString = "${notification.sweeper.poll-interval-ms:60000}")
	public void sweep() {
		sweeper.recoverStuckNotifications();
	}
}

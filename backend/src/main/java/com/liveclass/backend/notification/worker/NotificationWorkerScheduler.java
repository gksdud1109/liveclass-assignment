package com.liveclass.backend.notification.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
	name = "notification.worker.scheduling-enabled",
	havingValue = "true",
	matchIfMissing = true
)
@RequiredArgsConstructor
public class NotificationWorkerScheduler {

	private final NotificationWorker worker;

	@Scheduled(fixedDelayString = "${notification.worker.poll-interval-ms:1000}")
	public void poll() {
		worker.runOnce();
	}
}

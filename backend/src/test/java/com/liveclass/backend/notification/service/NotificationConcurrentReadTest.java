package com.liveclass.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.repository.NotificationRepository;
import com.liveclass.backend.support.PostgresContainerTest;

@SpringBootTest(properties = {
	"notification.worker.scheduling-enabled=false",
	"notification.sweeper.scheduling-enabled=false"
})
class NotificationConcurrentReadTest extends PostgresContainerTest {

	@Autowired
	private NotificationService service;

	@Autowired
	private NotificationRepository repository;

	@AfterEach
	void cleanup() {
		repository.deleteAll();
	}

	@Test
	void concurrentMarkRead_keepsFirstTimestamp() throws Exception {
		Notification saved = repository.saveAndFlush(Notification.create(
			"user-read",
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-read",
			NotificationChannel.IN_APP,
			Map.of("courseTitle", "Concurrent Read Test"),
			null
		));

		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);

		List<Future<LocalDateTime>> futures = IntStream.range(0, threads)
			.mapToObj(i -> executor.submit(() -> {
				start.await();
				return service.markRead(saved.getId()).getReadAt();
			}))
			.toList();
		start.countDown();

		Set<LocalDateTime> distinctTimestamps = new HashSet<>();
		for (Future<LocalDateTime> f : futures) {
			distinctTimestamps.add(f.get(10, TimeUnit.SECONDS));
		}
		executor.shutdownNow();

		assertThat(distinctTimestamps)
			.as("All concurrent readers should observe the same persisted readAt (first wins)")
			.hasSize(1);

		Notification reloaded = repository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getReadAt()).isNotNull();
	}
}

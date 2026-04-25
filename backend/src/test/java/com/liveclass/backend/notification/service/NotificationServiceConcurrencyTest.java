package com.liveclass.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.dto.CreateNotificationRequest;
import com.liveclass.backend.notification.repository.NotificationRepository;
import com.liveclass.backend.notification.service.NotificationService.RegisterResult;
import com.liveclass.backend.support.PostgresContainerTest;

/**
 * 멱등성 보증의 최종 진실원천은 DB unique 제약. 이 테스트는 실제 PostgreSQL 컨테이너에서
 * 동시 INSERT가 unique 제약으로 직렬화되는지 검증한다.
 */
@SpringBootTest(properties = "notification.worker.scheduling-enabled=false")
class NotificationServiceConcurrencyTest extends PostgresContainerTest {

	@Autowired
	private NotificationService service;

	@Autowired
	private NotificationRepository repository;

	@AfterEach
	void cleanup() {
		repository.deleteAll();
	}

	@Test
	void concurrentRegister_sameDedupKey_persistsExactlyOne() throws Exception {
		int threads = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch startLatch = new CountDownLatch(1);

		CreateNotificationRequest request = new CreateNotificationRequest(
			"race-user",
			NotificationType.ENROLLMENT_CONFIRMED,
			"race-ref",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Race Test", "startDate", "2026-05-01"),
			null
		);

		List<Future<RegisterResult>> futures = IntStream.range(0, threads)
			.mapToObj(i -> executor.submit(() -> {
				startLatch.await();
				return service.register(request);
			}))
			.toList();
		startLatch.countDown();

		Set<Long> returnedIds = new HashSet<>();
		int duplicateCount = 0;
		for (Future<RegisterResult> f : futures) {
			RegisterResult result = f.get(10, TimeUnit.SECONDS);
			returnedIds.add(result.notification().getId());
			if (result.duplicate()) {
				duplicateCount++;
			}
		}
		executor.shutdownNow();

		assertThat(repository.count())
			.as("DB unique constraint should allow only one row")
			.isEqualTo(1);
		assertThat(returnedIds)
			.as("All concurrent callers should receive the same id")
			.hasSize(1);
		assertThat(duplicateCount)
			.as("All but one caller should see duplicate=true")
			.isEqualTo(threads - 1);
	}
}

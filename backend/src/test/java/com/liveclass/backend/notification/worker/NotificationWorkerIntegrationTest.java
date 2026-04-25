package com.liveclass.backend.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.liveclass.backend.notification.domain.AttemptResult;
import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationAttempt;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.repository.NotificationAttemptRepository;
import com.liveclass.backend.notification.repository.NotificationRepository;
import com.liveclass.backend.notification.sender.EmailSender;
import com.liveclass.backend.notification.sender.NotificationDispatcher;
import com.liveclass.backend.notification.sender.NotificationSendException;
import com.liveclass.backend.notification.service.NotificationProcessingService;
import com.liveclass.backend.notification.template.NotificationTemplateRenderer;
import com.liveclass.backend.support.PostgresContainerTest;

@SpringBootTest(properties = "notification.worker.scheduling-enabled=false")
class NotificationWorkerIntegrationTest extends PostgresContainerTest {

	@Autowired
	private NotificationWorker worker;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private NotificationAttemptRepository attemptRepository;

	@Autowired
	private NotificationProcessingService processingService;

	@Autowired
	private NotificationDispatcher dispatcher;

	@Autowired
	private NotificationTemplateRenderer renderer;

	@MockitoSpyBean
	private EmailSender emailSender;

	@AfterEach
	void cleanup() {
		attemptRepository.deleteAll();
		notificationRepository.deleteAll();
	}

	@Test
	void runOnce_pendingNotification_isMarkedSent_andAttemptRecorded() {
		Notification saved = notificationRepository.saveAndFlush(Notification.create(
			"user-1",
			NotificationType.ENROLLMENT_CONFIRMED,
			"enrollment-1",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Spring Boot 입문", "startDate", "2026-05-01"),
			null
		));

		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(1);
		Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(reloaded.getSentAt()).isNotNull();
		assertThat(reloaded.getRenderedTitle()).contains("Spring Boot 입문");
		assertThat(reloaded.getProcessingStartedAt()).isNull();
		assertThat(reloaded.getWorkerId()).isNull();

		List<NotificationAttempt> attempts = attemptRepository.findByNotificationIdOrderByAttemptNoAsc(saved.getId());
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getResult()).isEqualTo(AttemptResult.SUCCESS);
		assertThat(attempts.get(0).getAttemptNo()).isEqualTo(1);
	}

	@Test
	void runOnce_senderFails_marksPendingForRetry_withBackoff() {
		doThrow(new NotificationSendException("simulated SMTP timeout"))
			.when(emailSender).send(any(), any());

		Notification saved = notificationRepository.saveAndFlush(Notification.create(
			"user-2",
			NotificationType.PAYMENT_CONFIRMED,
			"payment-1",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "JPA 실전", "amount", "50000"),
			null
		));

		LocalDateTime beforeRun = LocalDateTime.now();
		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(1);
		Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(reloaded.getRetryCount()).isEqualTo(1);
		assertThat(reloaded.getLastError()).contains("simulated SMTP timeout");
		assertThat(reloaded.getNextAttemptAt()).isAfter(beforeRun);
		assertThat(reloaded.getProcessingStartedAt()).isNull();

		List<NotificationAttempt> attempts = attemptRepository.findByNotificationIdOrderByAttemptNoAsc(saved.getId());
		assertThat(attempts).hasSize(1);
		assertThat(attempts.get(0).getResult()).isEqualTo(AttemptResult.FAILED);
		assertThat(attempts.get(0).getErrorMessage()).contains("simulated");
	}

	@Test
	void runOnce_lastRetryFails_transitionsToDeadLetter() {
		doThrow(new NotificationSendException("permanent failure"))
			.when(emailSender).send(any(), any());

		Notification saved = notificationRepository.saveAndFlush(Notification.create(
			"user-3",
			NotificationType.CLASS_STARTING_SOON,
			"class-1",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startAt", "2026-05-01T10:00"),
			null
		));
		// 사전 상태: retryCount=4, status=PENDING. 다음 실패에서 5 도달 → DEAD_LETTER
		ReflectionTestUtils.setField(saved, "retryCount", 4);
		ReflectionTestUtils.setField(saved, "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
		notificationRepository.saveAndFlush(saved);

		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(1);
		Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
		assertThat(reloaded.getRetryCount()).isEqualTo(5);
		assertThat(reloaded.getLastError()).contains("permanent failure");
	}

	@Test
	void runOnce_afterRetry_sucessfulSend_marksSent() {
		// 첫 시도 실패, 두 번째 시도 성공 시나리오
		doThrow(new NotificationSendException("transient"))
			.when(emailSender).send(any(), any());

		Notification saved = notificationRepository.saveAndFlush(Notification.create(
			"user-4",
			NotificationType.ENROLLMENT_CONFIRMED,
			"enrollment-2",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));

		// 1차 실행: 실패
		worker.runOnce();
		Notification afterFirst = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(afterFirst.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(afterFirst.getRetryCount()).isEqualTo(1);

		// 다음 시도 시각을 과거로 당기고 sender 정상 동작 복원
		ReflectionTestUtils.setField(afterFirst, "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
		notificationRepository.saveAndFlush(afterFirst);
		org.mockito.Mockito.reset(emailSender);

		// 2차 실행: 성공
		worker.runOnce();
		Notification afterSecond = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(afterSecond.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(afterSecond.getRetryCount()).isEqualTo(1);
		assertThat(afterSecond.getSentAt()).isNotNull();
		assertThat(afterSecond.getLastError()).isNull();

		List<NotificationAttempt> attempts = attemptRepository.findByNotificationIdOrderByAttemptNoAsc(saved.getId());
		assertThat(attempts).hasSize(2);
		assertThat(attempts.get(0).getResult()).isEqualTo(AttemptResult.FAILED);
		assertThat(attempts.get(1).getResult()).isEqualTo(AttemptResult.SUCCESS);
	}

	@Test
	void multipleWorkers_concurrentClaim_processEachExactlyOnce() throws Exception {
		int notificationCount = 30;
		int workerCount = 4;

		List<Long> ids = IntStream.range(0, notificationCount)
			.mapToObj(i -> notificationRepository.saveAndFlush(Notification.create(
				"user-" + i,
				NotificationType.ENROLLMENT_CONFIRMED,
				"ref-" + i,
				NotificationChannel.EMAIL,
				Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
				null
			)).getId())
			.toList();

		List<NotificationWorker> workers = IntStream.range(0, workerCount)
			.mapToObj(i -> new NotificationWorker(processingService, dispatcher, renderer, 10))
			.toList();

		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicInteger totalProcessed = new AtomicInteger(0);

		List<Future<Integer>> futures = workers.stream()
			.map(w -> executor.submit(() -> {
				startLatch.await();
				int processed;
				int local = 0;
				do {
					processed = w.runOnce();
					local += processed;
					totalProcessed.addAndGet(processed);
				} while (processed > 0);
				return local;
			}))
			.toList();
		startLatch.countDown();

		for (Future<Integer> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		executor.shutdownNow();

		assertThat(totalProcessed.get())
			.as("Total processed across all workers should equal total pending")
			.isEqualTo(notificationCount);

		List<Notification> reloadedAll = notificationRepository.findAllById(ids);
		assertThat(reloadedAll)
			.as("All notifications should be SENT exactly once")
			.allMatch(n -> n.getStatus() == NotificationStatus.SENT);

		for (Long id : ids) {
			List<NotificationAttempt> attempts = attemptRepository.findByNotificationIdOrderByAttemptNoAsc(id);
			assertThat(attempts)
				.as("Each notification should have exactly one SUCCESS attempt")
				.hasSize(1);
			assertThat(attempts.get(0).getResult()).isEqualTo(AttemptResult.SUCCESS);
		}
	}
}

package com.liveclass.backend.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.liveclass.backend.notification.sweeper.StuckNotificationSweeper;
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

	@MockitoSpyBean
	private NotificationProcessingService processingService;

	@Autowired
	private NotificationDispatcher dispatcher;

	@Autowired
	private NotificationTemplateRenderer renderer;

	@MockitoSpyBean
	private EmailSender emailSender;

	@Autowired
	private StuckNotificationSweeper sweeper;

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
	void runOnce_futureScheduledNotification_isNotClaimedUntilDue() {
		Notification scheduled = notificationRepository.saveAndFlush(Notification.create(
			"user-future",
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-future",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Scheduled Test", "startDate", "2026-05-01"),
			LocalDateTime.now().plusMinutes(10)
		));

		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(0);
		verify(emailSender, never()).send(any(), any());

		Notification reloaded = notificationRepository.findById(scheduled.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(reloaded.getSentAt()).isNull();
		assertThat(attemptRepository.findByNotificationIdOrderByAttemptNoAsc(scheduled.getId())).isEmpty();
	}

	@Test
	void fetchOwnedForProcessing_staleWorkerOwnership_returnsNull() {
		Notification owned = persistOwnedByWorker("worker-B", NotificationStatus.PROCESSING, 0);

		Notification fetched = processingService.fetchOwnedForProcessing(owned.getId(), "worker-A-stale");

		assertThat(fetched).isNull();
	}

	@Test
	void runOnce_sendSucceeds_butRecordSuccessFails_doesNotMarkFailed() {
		// 발송은 성공했는데 결과 기록이 실패한 경우 — 절대 PENDING/DEAD_LETTER로 떨어지면 안 됨
		// (그러면 다음 cycle에서 사용자에게 중복 발송)
		Notification saved = notificationRepository.saveAndFlush(Notification.create(
			"user-record-fail",
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-record-fail",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));

		doThrow(new RuntimeException("simulated DB blip on recordSuccess"))
			.when(processingService).recordSuccess(any(), any(), any());

		int processed = worker.runOnce();

		// 발송은 실제로 일어났어야 함
		verify(emailSender).send(any(), any());
		assertThat(processed).isEqualTo(1);

		// row는 PROCESSING으로 남고, recordFailure는 절대 호출되지 않아야 함
		Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus())
			.as("Must NOT regress to PENDING/DEAD_LETTER on recordSuccess failure — that would cause duplicate delivery")
			.isEqualTo(NotificationStatus.PROCESSING);
		assertThat(reloaded.getRetryCount()).isEqualTo(0);

		verify(processingService, never()).recordFailure(any(), any(), any());

		// 결과 기록이 실패했으므로 attempt 행은 없음 (recordSuccess와 같은 tx)
		assertThat(attemptRepository.findByNotificationIdOrderByAttemptNoAsc(saved.getId()))
			.isEmpty();
	}

	@Test
	void runOnce_recordFailure_throws_rowStaysProcessing_andSweeperRecovers() {
		// send 실패 후 recordFailure 자체도 DB 오류로 실패하는 시나리오.
		// 이 트랜잭션은 롤백되어 row가 PROCESSING에 잔류하지만, stuck sweeper가 임계치 초과 시 회수한다.
		Notification saved = notificationRepository.saveAndFlush(Notification.create(
			"user-record-failure",
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-record-failure",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));

		doThrow(new NotificationSendException("simulated send failure"))
			.when(emailSender).send(any(), any());
		doThrow(new RuntimeException("simulated DB blip on recordFailure"))
			.when(processingService).recordFailure(any(), any(), any());

		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(1);
		verify(emailSender).send(any(), any());
		verify(processingService).recordFailure(any(), any(), any());

		// recordFailure tx 롤백 → 상태 변경 미반영, attempt 미기록
		Notification afterWorker = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(afterWorker.getStatus())
			.as("recordFailure throwing means tx rolled back; row stays in PROCESSING")
			.isEqualTo(NotificationStatus.PROCESSING);
		assertThat(afterWorker.getRetryCount()).isEqualTo(0);
		assertThat(afterWorker.getLastError()).isNull();
		assertThat(attemptRepository.findByNotificationIdOrderByAttemptNoAsc(saved.getId()))
			.isEmpty();

		// 시뮬레이션: processing_started_at을 임계치 이전으로 강제하여 stuck 상태 재현
		ReflectionTestUtils.setField(afterWorker, "processingStartedAt", LocalDateTime.now().minusMinutes(10));
		notificationRepository.saveAndFlush(afterWorker);

		int recovered = sweeper.recoverStuckNotifications();

		assertThat(recovered).isEqualTo(1);
		Notification afterSweeper = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(afterSweeper.getStatus())
			.as("sweeper restores stuck rows so they are not lost")
			.isEqualTo(NotificationStatus.PENDING);
		assertThat(afterSweeper.getRetryCount())
			.as("sweeper conservatively bumps retry_count to bound duplicate sends")
			.isEqualTo(1);
		assertThat(afterSweeper.getLastError()).contains("stuck recovery");
	}

	@Test
	void recordSuccess_workerIdMismatch_isSilentlyIgnored() {
		// Race 시나리오: A가 claim → 지연 → sweeper가 PENDING 복구 → B가 다시 claim
		// → A의 늦은 recordSuccess가 도착하면 B의 처리에 간섭하면 안 됨
		Notification owned = persistOwnedByWorker("worker-B", NotificationStatus.PROCESSING, 0);

		processingService.recordSuccess(
			owned.getId(),
			"worker-A-stale",
			new com.liveclass.backend.notification.template.RenderedNotification("t", "b")
		);

		Notification reloaded = notificationRepository.findById(owned.getId()).orElseThrow();
		assertThat(reloaded.getStatus())
			.as("stale worker must not flip status of a row owned by another worker")
			.isEqualTo(NotificationStatus.PROCESSING);
		assertThat(reloaded.getWorkerId()).isEqualTo("worker-B");
		assertThat(attemptRepository.findByNotificationIdOrderByAttemptNoAsc(owned.getId()))
			.as("no attempt should be recorded for a stale worker")
			.isEmpty();
	}

	@Test
	void recordFailure_workerIdMismatch_isSilentlyIgnored() {
		Notification owned = persistOwnedByWorker("worker-B", NotificationStatus.PROCESSING, 0);

		processingService.recordFailure(
			owned.getId(),
			"worker-A-stale",
			"stale worker error"
		);

		Notification reloaded = notificationRepository.findById(owned.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
		assertThat(reloaded.getRetryCount())
			.as("stale worker must not bump retry_count of a row owned by another worker")
			.isEqualTo(0);
		assertThat(reloaded.getLastError()).isNull();
		assertThat(attemptRepository.findByNotificationIdOrderByAttemptNoAsc(owned.getId())).isEmpty();
	}

	private Notification persistOwnedByWorker(String workerId, NotificationStatus status, int retryCount) {
		Notification n = notificationRepository.saveAndFlush(Notification.create(
			"user-" + workerId,
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-" + workerId + "-" + System.nanoTime(),
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));
		ReflectionTestUtils.setField(n, "status", status);
		ReflectionTestUtils.setField(n, "workerId", workerId);
		ReflectionTestUtils.setField(n, "processingStartedAt", LocalDateTime.now());
		ReflectionTestUtils.setField(n, "retryCount", retryCount);
		return notificationRepository.saveAndFlush(n);
	}

	@Test
	void runOnce_scheduledForFuture_isNotPickedUp() {
		Notification scheduled = notificationRepository.saveAndFlush(Notification.create(
			"user-scheduled",
			NotificationType.CLASS_STARTING_SOON,
			"class-future",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Future Class", "startAt", "2026-12-01T10:00"),
			LocalDateTime.now().plusMinutes(30)
		));

		int processed = worker.runOnce();

		assertThat(processed)
			.as("future-scheduled notifications must not be picked up before nextAttemptAt")
			.isEqualTo(0);
		Notification reloaded = notificationRepository.findById(scheduled.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
		assertThat(reloaded.getScheduledAt()).isAfter(LocalDateTime.now());
		assertThat(reloaded.getSentAt()).isNull();
	}

	@Test
	void runOnce_scheduledTimeReached_isProcessed() {
		Notification scheduled = notificationRepository.saveAndFlush(Notification.create(
			"user-scheduled-reached",
			NotificationType.CLASS_STARTING_SOON,
			"class-now",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Imminent Class", "startAt", "2026-05-01T10:00"),
			LocalDateTime.now().minusSeconds(1)
		));

		int processed = worker.runOnce();

		assertThat(processed).isEqualTo(1);
		Notification reloaded = notificationRepository.findById(scheduled.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(reloaded.getSentAt()).isNotNull();
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

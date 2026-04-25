package com.liveclass.backend.notification.worker;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.sender.NotificationDispatcher;
import com.liveclass.backend.notification.service.NotificationProcessingService;
import com.liveclass.backend.notification.template.NotificationTemplateRenderer;
import com.liveclass.backend.notification.template.RenderedNotification;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class NotificationWorker {

	@Getter
	private final String workerId;
	private final int batchSize;
	private final NotificationProcessingService processingService;
	private final NotificationDispatcher dispatcher;
	private final NotificationTemplateRenderer renderer;

	public NotificationWorker(
		NotificationProcessingService processingService,
		NotificationDispatcher dispatcher,
		NotificationTemplateRenderer renderer,
		@Value("${notification.worker.batch-size:50}") int batchSize
	) {
		this.processingService = processingService;
		this.dispatcher = dispatcher;
		this.renderer = renderer;
		this.batchSize = batchSize;
		this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/**
	 * 한 사이클 실행: claim → 발송 → 결과 반영.
	 *
	 * <p>발송은 트랜잭션 외부에서 수행. 발송 중 예외는 워커 루프를 멈추지 않고
	 * recordFailure로 흡수되어 재시도 또는 DEAD_LETTER 전이.
	 */
	public int runOnce() {
		List<Long> claimed = processingService.claimBatch(workerId, batchSize);
		for (Long id : claimed) {
			processOne(id);
		}
		return claimed.size();
	}

	/**
	 * 발송 전(조회/렌더링) 실패와 발송 자체의 실패는 재시도 흐름으로 흡수한다.
	 * 그러나 <b>발송 성공 후 recordSuccess가 실패하는 경우</b>는 절대 recordFailure를
	 * 호출하지 않는다 — 호출하면 다음 cycle에서 사용자에게 중복 발송된다.
	 *
	 * <p>이 경우 row는 PROCESSING으로 남고 운영 알림이 필요하다. Stage 4의 스턱 복구가
	 * 5분 후 PENDING으로 되돌리면 중복 발송 가능 — 이 한계는 외부 채널의 idempotency 키
	 * 없이는 분산 환경에서 본질적으로 해소 불가. README에 at-least-once 의미 명시.
	 */
	private void processOne(Long id) {
		Notification notification;
		RenderedNotification rendered;
		try {
			notification = processingService.fetchForProcessing(id);
			rendered = renderer.render(
				notification.getNotificationType(),
				notification.getChannel(),
				notification.getPayload()
			);
		} catch (Exception preSendEx) {
			log.warn("Pre-send failure id={} reason={}", id, preSendEx.getMessage());
			safelyRecordFailure(id, preSendEx.getMessage());
			return;
		}

		try {
			dispatcher.send(notification, rendered);
		} catch (Exception sendEx) {
			log.warn("Send failed id={} reason={}", id, sendEx.getMessage());
			safelyRecordFailure(id, sendEx.getMessage());
			return;
		}

		try {
			processingService.recordSuccess(id, rendered);
		} catch (Exception recordEx) {
			log.error(
				"Send succeeded but recordSuccess failed id={}. "
					+ "Notification remains in PROCESSING; stuck recovery will eventually "
					+ "reset to PENDING and may cause duplicate delivery — operational alert needed.",
				id, recordEx
			);
		}
	}

	private void safelyRecordFailure(Long id, String message) {
		try {
			processingService.recordFailure(id, message);
		} catch (Exception inner) {
			log.error("Failed to record failure for id={}", id, inner);
		}
	}
}

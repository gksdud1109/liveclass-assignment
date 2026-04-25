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

	private void processOne(Long id) {
		try {
			Notification notification = processingService.fetchForProcessing(id);
			RenderedNotification rendered = renderer.render(
				notification.getNotificationType(),
				notification.getChannel(),
				notification.getPayload()
			);
			dispatcher.send(notification, rendered);
			processingService.recordSuccess(id, rendered);
		} catch (Exception ex) {
			log.warn("Notification send failed id={} reason={}", id, ex.getMessage());
			try {
				processingService.recordFailure(id, ex.getMessage());
			} catch (Exception inner) {
				log.error("Failed to record failure for id={}", id, inner);
			}
		}
	}
}

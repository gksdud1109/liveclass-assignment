package com.liveclass.backend.notification.domain;

import java.time.LocalDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.liveclass.backend.global.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
	name = "notification",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_notification_dedup",
		columnNames = {"recipient_id", "notification_type", "reference_id", "channel"}
	),
	indexes = {
		@Index(name = "idx_notification_status_next", columnList = "status, next_attempt_at"),
		@Index(name = "idx_notification_status_processing", columnList = "status, processing_started_at"),
		@Index(name = "idx_notification_recipient_created", columnList = "recipient_id, created_at")
	}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

	private static final int DEFAULT_MAX_RETRY = 5;

	@Column(name = "recipient_id", nullable = false, length = 64)
	private String recipientId;

	@Enumerated(EnumType.STRING)
	@Column(name = "notification_type", nullable = false, length = 40)
	private NotificationType notificationType;

	@Column(name = "reference_id", nullable = false, length = 64)
	private String referenceId;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, length = 16)
	private NotificationChannel channel;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private NotificationStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload")
	private Map<String, Object> payload;

	@Column(name = "rendered_title", length = 200)
	private String renderedTitle;

	@Column(name = "rendered_body", columnDefinition = "TEXT")
	private String renderedBody;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "max_retry", nullable = false)
	private int maxRetry;

	@Column(name = "next_attempt_at", nullable = false)
	private LocalDateTime nextAttemptAt;

	@Column(name = "processing_started_at")
	private LocalDateTime processingStartedAt;

	@Column(name = "worker_id", length = 40)
	private String workerId;

	@Column(name = "scheduled_at")
	private LocalDateTime scheduledAt;

	@Column(name = "last_error", columnDefinition = "TEXT")
	private String lastError;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	public static Notification create(
		String recipientId,
		NotificationType notificationType,
		String referenceId,
		NotificationChannel channel,
		Map<String, Object> payload,
		LocalDateTime scheduledAt
	) {
		Notification n = new Notification();
		n.recipientId = recipientId;
		n.notificationType = notificationType;
		n.referenceId = referenceId;
		n.channel = channel;
		n.payload = payload;
		n.scheduledAt = scheduledAt;
		n.status = NotificationStatus.PENDING;
		n.retryCount = 0;
		n.maxRetry = DEFAULT_MAX_RETRY;
		n.nextAttemptAt = scheduledAt != null ? scheduledAt : LocalDateTime.now();
		return n;
	}

	public void markProcessing(String workerId) {
		this.status = NotificationStatus.PROCESSING;
		this.processingStartedAt = LocalDateTime.now();
		this.workerId = workerId;
	}

	public void markSent(String renderedTitle, String renderedBody) {
		this.status = NotificationStatus.SENT;
		this.renderedTitle = renderedTitle;
		this.renderedBody = renderedBody;
		this.sentAt = LocalDateTime.now();
		this.processingStartedAt = null;
		this.workerId = null;
		this.lastError = null;
	}

	public void markFailed(String error, LocalDateTime nextAttemptAt) {
		this.retryCount += 1;
		this.lastError = error;
		this.processingStartedAt = null;
		this.workerId = null;
		if (this.retryCount >= this.maxRetry) {
			this.status = NotificationStatus.DEAD_LETTER;
		} else {
			this.status = NotificationStatus.PENDING;
			this.nextAttemptAt = nextAttemptAt;
		}
	}

	public void recoverFromStuck() {
		this.status = NotificationStatus.PENDING;
		this.nextAttemptAt = LocalDateTime.now();
		this.processingStartedAt = null;
		this.workerId = null;
	}

	public void resetForManualRetry(boolean resetRetryCount) {
		this.status = NotificationStatus.PENDING;
		this.nextAttemptAt = LocalDateTime.now();
		this.lastError = null;
		this.processingStartedAt = null;
		this.workerId = null;
		if (resetRetryCount) {
			this.retryCount = 0;
		}
	}

	public boolean isDeadLetter() {
		return this.status == NotificationStatus.DEAD_LETTER;
	}

	public boolean isProcessing() {
		return this.status == NotificationStatus.PROCESSING;
	}
}

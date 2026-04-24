package com.liveclass.backend.notification.domain;

import java.time.LocalDateTime;

import com.liveclass.backend.global.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
	name = "notification_attempt",
	indexes = @Index(name = "idx_attempt_notification", columnList = "notification_id, attempt_no")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationAttempt extends BaseEntity {

	@Column(name = "notification_id", nullable = false)
	private Long notificationId;

	@Column(name = "attempt_no", nullable = false)
	private int attemptNo;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "finished_at")
	private LocalDateTime finishedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "result", length = 16)
	private AttemptResult result;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	public static NotificationAttempt started(Long notificationId, int attemptNo) {
		NotificationAttempt a = new NotificationAttempt();
		a.notificationId = notificationId;
		a.attemptNo = attemptNo;
		a.startedAt = LocalDateTime.now();
		return a;
	}

	public void succeed() {
		this.result = AttemptResult.SUCCESS;
		this.finishedAt = LocalDateTime.now();
	}

	public void fail(String errorMessage) {
		this.result = AttemptResult.FAILED;
		this.errorMessage = errorMessage;
		this.finishedAt = LocalDateTime.now();
	}
}

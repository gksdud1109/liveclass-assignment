package com.liveclass.backend.notification.service;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.liveclass.backend.global.error.exception.BusinessException;
import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.dto.CreateNotificationRequest;
import com.liveclass.backend.notification.exception.NotificationErrorCode;
import com.liveclass.backend.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notificationRepository;

	public RegisterResult register(CreateNotificationRequest request) {
		Optional<Notification> existing = lookupDedup(request);
		if (existing.isPresent()) {
			return RegisterResult.duplicate(existing.get());
		}

		try {
			Notification saved = notificationRepository.saveAndFlush(Notification.create(
				request.recipientId(),
				request.notificationType(),
				request.referenceId(),
				request.channel(),
				request.payload(),
				request.scheduledAt()
			));
			return RegisterResult.created(saved);
		} catch (DataIntegrityViolationException raceLost) {
			log.info("Concurrent register lost race for recipient={} type={} reference={} channel={}",
				request.recipientId(), request.notificationType(), request.referenceId(), request.channel());
			return lookupDedup(request)
				.map(RegisterResult::duplicate)
				.orElseThrow(() -> raceLost);
		}
	}

	public Notification findById(Long id) {
		return notificationRepository.findById(id)
			.orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
	}

	public Page<Notification> search(
		String recipientId,
		NotificationStatus status,
		NotificationChannel channel,
		NotificationType type,
		Boolean readFilter,
		Pageable pageable
	) {
		return notificationRepository.search(recipientId, status, channel, type, readFilter, pageable);
	}

	private Optional<Notification> lookupDedup(CreateNotificationRequest request) {
		return notificationRepository.findByRecipientIdAndNotificationTypeAndReferenceIdAndChannel(
			request.recipientId(),
			request.notificationType(),
			request.referenceId(),
			request.channel()
		);
	}

	public record RegisterResult(Notification notification, boolean duplicate) {
		public static RegisterResult created(Notification n) {
			return new RegisterResult(n, false);
		}

		public static RegisterResult duplicate(Notification n) {
			return new RegisterResult(n, true);
		}
	}
}

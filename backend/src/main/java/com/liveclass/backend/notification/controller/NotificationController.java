package com.liveclass.backend.notification.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.dto.CreateNotificationRequest;
import com.liveclass.backend.notification.dto.CreateNotificationResponse;
import com.liveclass.backend.notification.dto.NotificationResponse;
import com.liveclass.backend.notification.service.NotificationService;
import com.liveclass.backend.notification.service.NotificationService.RegisterResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService service;

	@PostMapping
	public ResponseEntity<CreateNotificationResponse> register(
		@Valid @RequestBody CreateNotificationRequest request
	) {
		RegisterResult result = service.register(request);
		HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity
			.status(status)
			.body(CreateNotificationResponse.of(result.notification(), result.duplicate()));
	}

	@GetMapping("/{id}")
	public NotificationResponse getOne(@PathVariable Long id) {
		return NotificationResponse.from(service.findById(id));
	}

	@GetMapping
	public Page<NotificationResponse> search(
		@RequestParam String recipientId,
		@RequestParam(required = false) NotificationStatus status,
		@RequestParam(required = false) NotificationChannel channel,
		@RequestParam(required = false) NotificationType type,
		@RequestParam(name = "read", required = false) Boolean readFilter,
		@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		return service.search(recipientId, status, channel, type, readFilter, pageable)
			.map(NotificationResponse::from);
	}
}

package com.liveclass.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveclass.backend.notification.domain.Notification;
import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationStatus;
import com.liveclass.backend.notification.domain.NotificationType;
import com.liveclass.backend.notification.dto.CreateNotificationRequest;
import com.liveclass.backend.notification.repository.NotificationRepository;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationRepository repository;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void cleanup() {
		repository.deleteAll();
	}

	@Test
	void register_newRequest_returns202_andPersistsPending() throws Exception {
		CreateNotificationRequest request = new CreateNotificationRequest(
			"user-1",
			NotificationType.ENROLLMENT_CONFIRMED,
			"enrollment-1",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Spring Boot 입문", "startDate", "2026-05-01"),
			null
		);

		mockMvc.perform(post("/api/notifications")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.duplicate").value(false))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.id").isNumber());

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void register_sameDedupKey_returns200_andDuplicateTrue() throws Exception {
		CreateNotificationRequest request = new CreateNotificationRequest(
			"user-2",
			NotificationType.PAYMENT_CONFIRMED,
			"payment-1",
			NotificationChannel.IN_APP,
			Map.of("courseTitle", "Test"),
			null
		);
		String body = objectMapper.writeValueAsString(request);

		MvcResult firstResult = mockMvc.perform(post("/api/notifications")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andReturn();
		long firstId = readId(firstResult);

		mockMvc.perform(post("/api/notifications")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.duplicate").value(true))
			.andExpect(jsonPath("$.id").value(firstId));

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void register_missingRequiredField_returns400() throws Exception {
		String invalidBody = """
			{
			  "recipientId": "",
			  "notificationType": "ENROLLMENT_CONFIRMED",
			  "referenceId": "ref-1",
			  "channel": "EMAIL"
			}
			""";

		mockMvc.perform(post("/api/notifications")
				.contentType(MediaType.APPLICATION_JSON)
				.content(invalidBody))
			.andExpect(status().isBadRequest());
	}

	@Test
	void getOne_existing_returnsNotification() throws Exception {
		Notification saved = repository.saveAndFlush(Notification.create(
			"user-3",
			NotificationType.CLASS_STARTING_SOON,
			"class-1",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test"),
			null
		));

		mockMvc.perform(get("/api/notifications/{id}", saved.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(saved.getId()))
			.andExpect(jsonPath("$.recipientId").value("user-3"))
			.andExpect(jsonPath("$.notificationType").value("CLASS_STARTING_SOON"));
	}

	@Test
	void getOne_notFound_returns404() throws Exception {
		mockMvc.perform(get("/api/notifications/{id}", 999_999L))
			.andExpect(status().isNotFound());
	}

	@Test
	void search_filtersByRecipientAndType() throws Exception {
		repository.saveAndFlush(Notification.create(
			"user-A", NotificationType.ENROLLMENT_CONFIRMED, "ref-1",
			NotificationChannel.EMAIL, Map.of("courseTitle", "C1"), null));
		repository.saveAndFlush(Notification.create(
			"user-A", NotificationType.PAYMENT_CONFIRMED, "ref-2",
			NotificationChannel.EMAIL, Map.of("courseTitle", "C2"), null));
		repository.saveAndFlush(Notification.create(
			"user-B", NotificationType.ENROLLMENT_CONFIRMED, "ref-3",
			NotificationChannel.EMAIL, Map.of("courseTitle", "C3"), null));

		mockMvc.perform(get("/api/notifications")
				.param("recipientId", "user-A"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2));

		mockMvc.perform(get("/api/notifications")
				.param("recipientId", "user-A")
				.param("type", "PAYMENT_CONFIRMED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].notificationType").value("PAYMENT_CONFIRMED"));
	}

	@Test
	void retry_deadLetter_returnsPending_andKeepsRetryCount() throws Exception {
		Notification deadLetter = persistDeadLetter("user-dl-1", "ref-dl-1", 5);

		mockMvc.perform(post("/api/notifications/{id}/retry", deadLetter.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resetRetryCount\": false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(deadLetter.getId()))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.retryCount").value(5));
	}

	@Test
	void retry_deadLetter_withResetTrue_clearsRetryCount() throws Exception {
		Notification deadLetter = persistDeadLetter("user-dl-2", "ref-dl-2", 5);

		mockMvc.perform(post("/api/notifications/{id}/retry", deadLetter.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resetRetryCount\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.retryCount").value(0));
	}

	@Test
	void retry_emptyBody_defaultsToNotResetting() throws Exception {
		Notification deadLetter = persistDeadLetter("user-dl-3", "ref-dl-3", 5);

		mockMvc.perform(post("/api/notifications/{id}/retry", deadLetter.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.retryCount").value(5));
	}

	@Test
	void retry_pendingNotification_returns400_NOT_DEAD_LETTER() throws Exception {
		Notification pending = repository.saveAndFlush(Notification.create(
			"user-pending", NotificationType.ENROLLMENT_CONFIRMED, "ref-pending",
			NotificationChannel.EMAIL, Map.of("courseTitle", "Test"), null));

		mockMvc.perform(post("/api/notifications/{id}/retry", pending.getId()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_DEAD_LETTER"));
	}

	@Test
	void retry_notFound_returns404() throws Exception {
		mockMvc.perform(post("/api/notifications/{id}/retry", 999_999L))
			.andExpect(status().isNotFound());
	}

	@Test
	void markRead_unread_setsReadAt() throws Exception {
		Notification saved = repository.saveAndFlush(Notification.create(
			"user-read-1", NotificationType.ENROLLMENT_CONFIRMED, "ref-r1",
			NotificationChannel.IN_APP, Map.of("courseTitle", "Test"), null));

		mockMvc.perform(post("/api/notifications/{id}/read", saved.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readAt").isNotEmpty());

		Notification reloaded = repository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getReadAt()).isNotNull();
	}

	@Test
	void markRead_alreadyRead_keepsOriginalTimestamp() throws Exception {
		Notification saved = repository.saveAndFlush(Notification.create(
			"user-read-2", NotificationType.ENROLLMENT_CONFIRMED, "ref-r2",
			NotificationChannel.IN_APP, Map.of("courseTitle", "Test"), null));

		mockMvc.perform(post("/api/notifications/{id}/read", saved.getId()))
			.andExpect(status().isOk());
		LocalDateTime firstReadAt = repository.findById(saved.getId()).orElseThrow().getReadAt();

		Thread.sleep(20);

		mockMvc.perform(post("/api/notifications/{id}/read", saved.getId()))
			.andExpect(status().isOk());
		LocalDateTime secondReadAt = repository.findById(saved.getId()).orElseThrow().getReadAt();

		assertThat(secondReadAt)
			.as("Second mark-read must NOT overwrite the first timestamp (COALESCE)")
			.isEqualTo(firstReadAt);
	}

	@Test
	void markRead_notFound_returns404() throws Exception {
		mockMvc.perform(post("/api/notifications/{id}/read", 999_999L))
			.andExpect(status().isNotFound());
	}

	@Test
	void markRead_emailChannel_returns400_andDoesNotSetReadAt() throws Exception {
		Notification email = repository.saveAndFlush(Notification.create(
			"user-email-read",
			NotificationType.ENROLLMENT_CONFIRMED,
			"ref-email-read",
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));

		mockMvc.perform(post("/api/notifications/{id}/read", email.getId()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("READ_NOT_SUPPORTED_FOR_CHANNEL"));

		Notification reloaded = repository.findById(email.getId()).orElseThrow();
		assertThat(reloaded.getReadAt())
			.as("EMAIL channel must not be readable; readAt remains null")
			.isNull();
	}

	@Test
	void search_readFilter_separatesReadFromUnread() throws Exception {
		Notification a = repository.saveAndFlush(Notification.create(
			"user-rf", NotificationType.ENROLLMENT_CONFIRMED, "rf-1",
			NotificationChannel.IN_APP, Map.of("courseTitle", "A"), null));
		Notification b = repository.saveAndFlush(Notification.create(
			"user-rf", NotificationType.PAYMENT_CONFIRMED, "rf-2",
			NotificationChannel.IN_APP, Map.of("courseTitle", "B"), null));

		mockMvc.perform(post("/api/notifications/{id}/read", a.getId()))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/notifications")
				.param("recipientId", "user-rf")
				.param("read", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].id").value(a.getId()));

		mockMvc.perform(get("/api/notifications")
				.param("recipientId", "user-rf")
				.param("read", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].id").value(b.getId()));
	}

	private Notification persistDeadLetter(String recipientId, String referenceId, int retryCount) {
		Notification n = repository.saveAndFlush(Notification.create(
			recipientId,
			NotificationType.ENROLLMENT_CONFIRMED,
			referenceId,
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Test", "startDate", "2026-05-01"),
			null
		));
		ReflectionTestUtils.setField(n, "status", NotificationStatus.DEAD_LETTER);
		ReflectionTestUtils.setField(n, "retryCount", retryCount);
		ReflectionTestUtils.setField(n, "lastError", "max retry exceeded");
		return repository.saveAndFlush(n);
	}

	private long readId(MvcResult result) throws Exception {
		JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
		return node.get("id").asLong();
	}
}

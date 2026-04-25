package com.liveclass.backend.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationType;

class DefaultNotificationTemplateRendererTest {

	private final NotificationTemplateRenderer renderer = new DefaultNotificationTemplateRenderer();

	@Test
	void render_enrollmentConfirmed_email_substitutes_placeholders() {
		RenderedNotification rendered = renderer.render(
			NotificationType.ENROLLMENT_CONFIRMED,
			NotificationChannel.EMAIL,
			Map.of("courseTitle", "Spring Boot 입문", "startDate", "2026-05-01")
		);

		assertThat(rendered.title()).isEqualTo("[수강 신청 완료] Spring Boot 입문");
		assertThat(rendered.body())
			.contains("Spring Boot 입문")
			.contains("2026-05-01");
	}

	@Test
	void render_inApp_isShorter_thanEmail_forSameType() {
		Map<String, Object> emailPayload = Map.of(
			"courseTitle", "JPA 실전",
			"startDate", "2026-05-01"
		);
		Map<String, Object> inAppPayload = Map.of("courseTitle", "JPA 실전");

		RenderedNotification email = renderer.render(
			NotificationType.ENROLLMENT_CONFIRMED, NotificationChannel.EMAIL, emailPayload);
		RenderedNotification inApp = renderer.render(
			NotificationType.ENROLLMENT_CONFIRMED, NotificationChannel.IN_APP, inAppPayload);

		assertThat(inApp.body().length()).isLessThan(email.body().length());
	}

	@Test
	void render_missingPayloadKey_throws() {
		assertThatThrownBy(() -> renderer.render(
			NotificationType.PAYMENT_CONFIRMED,
			NotificationChannel.EMAIL,
			Map.of()
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Missing payload key");
	}

	@Test
	void render_nullPayload_throws_whenPlaceholdersExist() {
		assertThatThrownBy(() -> renderer.render(
			NotificationType.ENROLLMENT_CONFIRMED,
			NotificationChannel.EMAIL,
			null
		))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@EnumSource(NotificationType.class)
	void allTypes_haveTemplates_forBothChannels(NotificationType type) {
		Map<String, Object> payload = Map.of(
			"courseTitle", "Test",
			"startDate", "2026-01-01",
			"amount", "10000",
			"startAt", "2026-01-01T10:00"
		);

		for (NotificationChannel channel : NotificationChannel.values()) {
			RenderedNotification rendered = renderer.render(type, channel, payload);
			assertThat(rendered.title()).isNotBlank();
			assertThat(rendered.body()).isNotBlank();
		}
	}
}

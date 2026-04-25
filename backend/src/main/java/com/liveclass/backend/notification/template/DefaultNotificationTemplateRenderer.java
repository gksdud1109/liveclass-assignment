package com.liveclass.backend.notification.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.liveclass.backend.notification.domain.NotificationChannel;
import com.liveclass.backend.notification.domain.NotificationType;

@Component
public class DefaultNotificationTemplateRenderer implements NotificationTemplateRenderer {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

	private final Map<TemplateKey, Template> templates = Map.ofEntries(
		Map.entry(
			new TemplateKey(NotificationType.ENROLLMENT_CONFIRMED, NotificationChannel.EMAIL),
			new Template(
				"[수강 신청 완료] {{courseTitle}}",
				"신청하신 강의 '{{courseTitle}}'의 수강이 확정되었습니다. 시작일: {{startDate}}"
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.ENROLLMENT_CONFIRMED, NotificationChannel.IN_APP),
			new Template(
				"수강 신청 완료",
				"'{{courseTitle}}' 신청이 완료되었습니다."
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL),
			new Template(
				"[결제 확정] {{courseTitle}}",
				"'{{courseTitle}}' 결제가 확정되었습니다. 금액: {{amount}}원"
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.IN_APP),
			new Template(
				"결제 확정",
				"'{{courseTitle}}' 결제가 완료되었습니다."
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.CLASS_STARTING_SOON, NotificationChannel.EMAIL),
			new Template(
				"[D-1] {{courseTitle}} 시작 안내",
				"'{{courseTitle}}' 강의가 곧 시작됩니다. 시작 시각: {{startAt}}"
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.CLASS_STARTING_SOON, NotificationChannel.IN_APP),
			new Template(
				"강의 시작 D-1",
				"'{{courseTitle}}'가 곧 시작됩니다."
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.ENROLLMENT_CANCELLED, NotificationChannel.EMAIL),
			new Template(
				"[수강 취소] {{courseTitle}}",
				"'{{courseTitle}}' 수강 신청이 취소되었습니다."
			)
		),
		Map.entry(
			new TemplateKey(NotificationType.ENROLLMENT_CANCELLED, NotificationChannel.IN_APP),
			new Template(
				"수강 취소",
				"'{{courseTitle}}' 신청이 취소되었습니다."
			)
		)
	);

	@Override
	public RenderedNotification render(
		NotificationType type,
		NotificationChannel channel,
		Map<String, Object> payload
	) {
		Template template = templates.get(new TemplateKey(type, channel));
		if (template == null) {
			throw new IllegalStateException(
				"No template registered for type=" + type + ", channel=" + channel
			);
		}
		return new RenderedNotification(
			substitute(template.title(), payload),
			substitute(template.body(), payload)
		);
	}

	private String substitute(String template, Map<String, Object> payload) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String key = matcher.group(1);
			Object value = payload != null ? payload.get(key) : null;
			if (value == null) {
				throw new IllegalArgumentException("Missing payload key: " + key);
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private record TemplateKey(NotificationType type, NotificationChannel channel) {
	}

	private record Template(String title, String body) {
	}
}

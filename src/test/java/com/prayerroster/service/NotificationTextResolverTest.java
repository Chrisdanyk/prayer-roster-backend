package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Notification;
import com.prayerroster.domain.NotificationType;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class NotificationTextResolverTest {

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    {
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding("UTF-8");
    }

    private final NotificationTextResolver resolver = new NotificationTextResolver(messageSource, new ObjectMapper());

    private static Notification notification(String messageKey, String paramsJson) {
        Notification notification = new Notification();
        notification.setType(NotificationType.ASSIGNMENT_PUBLISHED);
        notification.setMessageKey(messageKey);
        notification.setParams(paramsJson);
        return notification;
    }

    @Test
    void resolveSubject_returnsMessageForLocale() {
        Notification notification = notification("notification.assignmentPublished", null);

        assertThat(resolver.resolveSubject(notification, Locale.FRENCH)).isEqualTo("Nouvelle affectation de prière");
        assertThat(resolver.resolveSubject(notification, Locale.ENGLISH)).isEqualTo("New prayer assignment");
    }

    @Test
    void resolveBody_substitutesFormattedDateAndTranslatedRole() {
        Notification notification = notification("notification.assignmentPublished", "{\"date\":\"2026-09-06\",\"role\":\"MODERATOR\"}");

        String frenchBody = resolver.resolveBody(notification, Locale.FRENCH);
        assertThat(frenchBody).contains("modérateur").contains("6 septembre 2026");

        String englishBody = resolver.resolveBody(notification, Locale.ENGLISH);
        assertThat(englishBody).contains("moderator").contains("September 6, 2026");
    }

    @Test
    void resolveBody_includesDaysBeforeForReminders() {
        Notification notification = notification(
            "notification.assignmentReminder",
            "{\"date\":\"2026-09-06\",\"role\":\"PREACHER\",\"daysBefore\":\"7\"}"
        );

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).contains("prédicateur").contains("7 jour(s)");
    }

    @Test
    void resolveBody_joinsEachItemOnItsOwnLineForABatchedPublishedNotification() {
        Notification notification = notification(
            "notification.assignmentsPublished",
            "[{\"date\":\"2026-09-06\",\"role\":\"MODERATOR\"},{\"date\":\"2026-09-13\",\"role\":\"PREACHER\"}]"
        );

        String frenchBody = resolver.resolveBody(notification, Locale.FRENCH);
        assertThat(frenchBody).contains("modérateur").contains("6 septembre 2026");
        assertThat(frenchBody).contains("prédicateur").contains("13 septembre 2026");
    }

    @Test
    void resolveBody_handlesAnEmptyBatchGracefully() {
        Notification notification = notification("notification.assignmentsRemoved", "[]");

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }

    @Test
    void resolveBody_handlesMalformedBatchJsonGracefully() {
        Notification notification = notification("notification.assignmentsPublished", "not valid json");

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }

    @Test
    void resolveBody_handlesMissingParamsGracefully() {
        Notification notification = notification("notification.assignmentPublished", null);

        // Should not throw even though date/role are absent from params.
        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }

    @Test
    void resolveBody_handlesMalformedParamsJsonGracefully() {
        Notification notification = notification("notification.assignmentPublished", "not valid json");

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }

    @Test
    void resolveBody_handlesBlankParamsStringGracefully() {
        Notification notification = notification("notification.assignmentPublished", "   ");

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }

    @Test
    void resolveBody_handlesEmptyParamsMap() throws Exception {
        Map<String, String> empty = Map.of();
        Notification notification = notification("notification.assignmentPublished", new ObjectMapper().writeValueAsString(empty));

        assertThat(resolver.resolveBody(notification, Locale.FRENCH)).isNotNull();
    }
}

package com.prayerroster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prayerroster.domain.Notification;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@link Notification}'s {@code messageKey}/{@code params} to final human-readable text
 * in a given locale, at read time - never pre-rendered, so a {@code User.langKey} change is always
 * reflected correctly on the next read (see docs/phase1-architecture.md section 13). {@code params}
 * stores locale-independent raw values only (an ISO date, a role name); this class is where those
 * become locale-specific text - including a nested lookup for the role name itself, which needs its
 * own translation before it can be substituted into the body message.
 */
@Component
public class NotificationTextResolver {

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public NotificationTextResolver(MessageSource messageSource, ObjectMapper objectMapper) {
        this.messageSource = messageSource;
        this.objectMapper = objectMapper;
    }

    public String resolveSubject(Notification notification, Locale locale) {
        return messageSource.getMessage(notification.getMessageKey() + ".subject", null, locale);
    }

    public String resolveBody(Notification notification, Locale locale) {
        Map<String, String> params = parseParams(notification.getParams());
        String date = params.containsKey("date") ? formatDate(params.get("date"), locale) : null;
        String role = params.containsKey("role") ? resolveRole(params.get("role"), locale) : null;
        String daysBefore = params.get("daysBefore");
        return messageSource.getMessage(notification.getMessageKey() + ".body", new Object[] { date, role, daysBefore }, locale);
    }

    private String resolveRole(String roleName, Locale locale) {
        return messageSource.getMessage("role." + roleName, null, locale);
    }

    private String formatDate(String isoDate, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(LocalDate.parse(isoDate));
    }

    private Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}

package com.prayerroster.service;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import tech.jhipster.config.JHipsterProperties;

/**
 * Low-level email sending - wraps the already-localized subject/body text (resolved by {@link
 * NotificationTextResolver}) in a minimal HTML template (see docs/phase1-architecture.md section
 * 13). Deliberately never swallows failures itself: {@link EmailNotificationService} is the layer
 * responsible for catching send failures and recording them, so a mail provider outage is visible
 * on the {@code Notification} row rather than silently lost here.
 */
@Service
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender javaMailSender;
    private final SpringTemplateEngine templateEngine;
    private final JHipsterProperties jHipsterProperties;

    public MailService(JavaMailSender javaMailSender, SpringTemplateEngine templateEngine, JHipsterProperties jHipsterProperties) {
        this.javaMailSender = javaMailSender;
        this.templateEngine = templateEngine;
        this.jHipsterProperties = jHipsterProperties;
    }

    public void sendEmail(String to, String subject, String textBody) {
        sendEmail(to, subject, textBody, null, null);
    }

    /**
     * @param actionUrl when non-null, the template renders a clickable call-to-action button
     *     labelled {@code actionLabel}; when null, the email is text-only.
     */
    public void sendEmail(String to, String subject, String textBody, String actionUrl, String actionLabel) {
        LOG.debug("Sending email to '{}' with subject '{}'", to, subject);
        Context context = new Context();
        context.setVariable("body", textBody);
        context.setVariable("actionUrl", actionUrl);
        context.setVariable("actionLabel", actionLabel);
        String htmlContent = templateEngine.process("mail/notification", context);

        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(jHipsterProperties.getMail().getFrom());
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            javaMailSender.send(mimeMessage);
        } catch (Exception e) {
            throw new MailSendException("Failed to send email to " + to, e);
        }
    }

    /**
     * Fire-and-forget entry point for callers with no natural {@link
     * com.prayerroster.domain.Notification} row to hang a retry sweep off (see
     * {@link AllowedEmailService}). An exception thrown here never reaches the original caller -
     * {@code @Async void} methods report failures to the executor's uncaught-exception handler, not
     * back to the call site - so the catch has to live in this method, not in the caller.
     */
    @org.springframework.scheduling.annotation.Async
    public void sendEmailAsync(String to, String subject, String textBody, String actionUrl, String actionLabel) {
        try {
            sendEmail(to, subject, textBody, actionUrl, actionLabel);
        } catch (MailSendException e) {
            LOG.warn("Failed to send email to {}", to, e);
        }
    }
}

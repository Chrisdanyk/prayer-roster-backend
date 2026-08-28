package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import tech.jhipster.config.JHipsterProperties;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender javaMailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

    private MailService service;
    private JHipsterProperties jHipsterProperties;

    @BeforeEach
    void setUp() {
        jHipsterProperties = new JHipsterProperties();
        jHipsterProperties.getMail().setFrom("prayerRosterBackend@example.com");
        service = new MailService(javaMailSender, templateEngine, jHipsterProperties);
    }

    private static MimeMessage realMimeMessage() {
        return new MimeMessage(Session.getDefaultInstance(new Properties()));
    }

    @Test
    void sendEmail_rendersTemplateAndSendsViaJavaMailSender() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        when(templateEngine.process(eq("mail/notification"), any())).thenReturn("<p>Corps</p>");

        service.sendEmail("jean@example.com", "Sujet", "Corps");

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("jean@example.com");
        assertThat(sent.getSubject()).isEqualTo("Sujet");
        assertThat(sent.getFrom()[0].toString()).contains("prayerRosterBackend@example.com");
    }

    @Test
    void sendEmail_wrapsFailureAsMailSendException() {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        when(templateEngine.process(eq("mail/notification"), any())).thenReturn("<p>Corps</p>");
        doThrow(new MailSendException("boom")).when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> service.sendEmail("jean@example.com", "Sujet", "Corps")).isInstanceOf(MailSendException.class);
    }

    @Test
    void sendEmail_withoutAction_leavesActionVariablesNull() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        when(templateEngine.process(eq("mail/notification"), contextCaptor.capture())).thenReturn("<p>Corps</p>");

        service.sendEmail("jean@example.com", "Sujet", "Corps");

        Context context = contextCaptor.getValue();
        assertThat(context.getVariable("actionUrl")).isNull();
        assertThat(context.getVariable("actionLabel")).isNull();
    }

    @Test
    void sendEmail_withAction_passesTheLinkAndLabelToTheTemplate() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        when(templateEngine.process(eq("mail/notification"), contextCaptor.capture())).thenReturn("<p>Corps</p>");

        service.sendEmail("jean@example.com", "Sujet", "Corps", "https://app.example.com", "Se connecter");

        Context context = contextCaptor.getValue();
        assertThat(context.getVariable("actionUrl")).isEqualTo("https://app.example.com");
        assertThat(context.getVariable("actionLabel")).isEqualTo("Se connecter");
    }

    @Test
    void sendEmailAsync_sendsSuccessfully() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        when(templateEngine.process(eq("mail/notification"), any())).thenReturn("<p>Corps</p>");

        service.sendEmailAsync("jean@example.com", "Sujet", "Corps", "https://app.example.com", "Se connecter");

        verify(javaMailSender).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    void sendEmailAsync_swallowsAndLogsAFailureRatherThanPropagating() {
        when(javaMailSender.createMimeMessage()).thenReturn(realMimeMessage());
        when(templateEngine.process(eq("mail/notification"), any())).thenReturn("<p>Corps</p>");
        doThrow(new MailSendException("boom")).when(javaMailSender).send(any(MimeMessage.class));

        // Must not throw - an @Async void method's exception would otherwise be lost to the
        // uncaught-exception handler instead of being logged predictably.
        service.sendEmailAsync("jean@example.com", "Sujet", "Corps", null, null);
    }
}

package com.prayerroster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.domain.AllowedEmail;
import com.prayerroster.repository.AllowedEmailRepository;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailSendException;

@ExtendWith(MockitoExtension.class)
class AllowedEmailServiceTest {

    @Mock
    private AllowedEmailRepository allowedEmailRepository;

    @Mock
    private MailService mailService;

    @Mock
    private MessageSource messageSource;

    private ApplicationProperties applicationProperties;
    private AllowedEmailService service;

    @BeforeEach
    void setUp() {
        applicationProperties = new ApplicationProperties();
        applicationProperties.getFrontend().setBaseUrl("http://localhost:3000");
        service = new AllowedEmailService(allowedEmailRepository, mailService, messageSource, applicationProperties);

        lenient().when(messageSource.getMessage(eq("invitation.email.subject"), any(), eq(Locale.FRENCH))).thenReturn("Sujet");
        lenient().when(messageSource.getMessage(eq("invitation.email.body"), any(), eq(Locale.FRENCH))).thenReturn("Corps");
        lenient().when(messageSource.getMessage(eq("invitation.email.cta"), any(), eq(Locale.FRENCH))).thenReturn("Se connecter");
    }

    @Test
    void invite_storesTheEmailLowercasedAndTrimmed() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);
        when(allowedEmailRepository.save(any(AllowedEmail.class))).thenAnswer(inv -> inv.getArgument(0));

        service.invite("  Jean@Example.COM  ");

        ArgumentCaptor<AllowedEmail> captor = ArgumentCaptor.forClass(AllowedEmail.class);
        verify(allowedEmailRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("jean@example.com");
    }

    @Test
    void invite_sendsAnInvitationEmailWithASignInLink() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);
        when(allowedEmailRepository.save(any(AllowedEmail.class))).thenAnswer(inv -> inv.getArgument(0));

        service.invite("jean@example.com");

        verify(mailService).sendEmail("jean@example.com", "Sujet", "Corps", "http://localhost:3000", "Se connecter");
    }

    @Test
    void invite_omitsTheLinkWhenNoFrontendBaseUrlIsConfigured() {
        applicationProperties.getFrontend().setBaseUrl(null);
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);
        when(allowedEmailRepository.save(any(AllowedEmail.class))).thenAnswer(inv -> inv.getArgument(0));

        service.invite("jean@example.com");

        verify(mailService).sendEmail(eq("jean@example.com"), eq("Sujet"), eq("Corps"), isNull(), isNull());
    }

    @Test
    void invite_stillSucceedsWhenTheInvitationEmailFailsToSend() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(false);
        when(allowedEmailRepository.save(any(AllowedEmail.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new MailSendException("boom")).when(mailService).sendEmail(any(), any(), any(), any(), any());

        AllowedEmailDTO result = service.invite("jean@example.com");

        assertThat(result.email()).isEqualTo("jean@example.com");
    }

    @Test
    void resend_sendsTheInvitationEmailAgain() {
        AllowedEmail entity = new AllowedEmail();
        entity.setId(1L);
        entity.setEmail("jean@example.com");
        when(allowedEmailRepository.findById(1L)).thenReturn(java.util.Optional.of(entity));

        service.resend(1L);

        verify(mailService).sendEmail("jean@example.com", "Sujet", "Corps", "http://localhost:3000", "Se connecter");
    }

    @Test
    void resend_rejectsAnUnknownId() {
        when(allowedEmailRepository.findById(9L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.resend(9L)).isInstanceOf(EntityNotFoundException.class);

        verify(mailService, never()).sendEmail(any(), any(), any(), any(), any());
    }

    @Test
    void invite_rejectsADuplicate() {
        when(allowedEmailRepository.existsByEmailIgnoringCase("jean@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.invite("jean@example.com")).isInstanceOf(BadRequestAlertException.class);

        verify(allowedEmailRepository, never()).save(any());
    }

    @Test
    void findAll_mapsToDtos() {
        AllowedEmail entity = new AllowedEmail();
        entity.setId(1L);
        entity.setEmail("jean@example.com");
        when(allowedEmailRepository.findAll()).thenReturn(List.of(entity));

        List<AllowedEmailDTO> result = service.findAll();

        assertThat(result).singleElement().extracting(AllowedEmailDTO::email).isEqualTo("jean@example.com");
    }

    @Test
    void delete_removesAnExistingEntry() {
        when(allowedEmailRepository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(allowedEmailRepository).deleteById(1L);
    }

    @Test
    void delete_rejectsAnUnknownId() {
        when(allowedEmailRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(9L)).isInstanceOf(EntityNotFoundException.class);

        verify(allowedEmailRepository, never()).deleteById(any());
    }
}

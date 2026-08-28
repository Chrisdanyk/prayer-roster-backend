package com.prayerroster.service;

import com.prayerroster.config.ApplicationProperties;
import com.prayerroster.domain.AllowedEmail;
import com.prayerroster.repository.AllowedEmailRepository;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Manages the invite allowlist. Deleting an entry does not revoke someone who has already signed in -
 * the allowlist governs first admission only, and {@code User.active} governs thereafter. See
 * docs/phase1-architecture.md section 10.
 */
@Service
@Transactional
public class AllowedEmailService {

    private static final Logger LOG = LoggerFactory.getLogger(AllowedEmailService.class);
    private static final String ENTITY_NAME = "allowedEmail";

    private final AllowedEmailRepository allowedEmailRepository;
    private final MailService mailService;
    private final MessageSource messageSource;
    private final ApplicationProperties applicationProperties;

    public AllowedEmailService(
        AllowedEmailRepository allowedEmailRepository,
        MailService mailService,
        MessageSource messageSource,
        ApplicationProperties applicationProperties
    ) {
        this.allowedEmailRepository = allowedEmailRepository;
        this.mailService = mailService;
        this.messageSource = messageSource;
        this.applicationProperties = applicationProperties;
    }

    @Transactional(readOnly = true)
    public List<AllowedEmailDTO> findAll() {
        return allowedEmailRepository.findAll().stream().map(AllowedEmailDTO::from).toList();
    }

    public AllowedEmailDTO invite(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (allowedEmailRepository.existsByEmailIgnoringCase(normalized)) {
            throw new BadRequestAlertException("This email address is already invited", ENTITY_NAME, "duplicateEmail");
        }
        AllowedEmail allowedEmail = new AllowedEmail();
        allowedEmail.setEmail(normalized);
        AllowedEmailDTO dto = AllowedEmailDTO.from(allowedEmailRepository.save(allowedEmail));
        sendInvitationEmail(normalized);
        return dto;
    }

    /**
     * Best-effort: the address is invited either way, so a mail outage must not roll back the
     * allowlist entry - see {@link EmailNotificationService} for the same philosophy applied to
     * in-app notifications.
     */
    private void sendInvitationEmail(String email) {
        String subject = messageSource.getMessage("invitation.email.subject", null, Locale.FRENCH);
        String body = messageSource.getMessage("invitation.email.body", null, Locale.FRENCH);
        String cta = messageSource.getMessage("invitation.email.cta", null, Locale.FRENCH);
        String baseUrl = applicationProperties.getFrontend().getBaseUrl();
        String actionUrl = StringUtils.hasText(baseUrl) ? baseUrl : null;
        String actionLabel = actionUrl != null ? cta : null;
        try {
            mailService.sendEmail(email, subject, body, actionUrl, actionLabel);
        } catch (MailException e) {
            LOG.warn("Failed to send invitation email to {}", email, e);
        }
    }

    public void resend(Long id) {
        AllowedEmail allowedEmail = allowedEmailRepository
            .findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Allowed email not found: " + id));
        sendInvitationEmail(allowedEmail.getEmail());
    }

    public void delete(Long id) {
        if (!allowedEmailRepository.existsById(id)) {
            throw new EntityNotFoundException("Allowed email not found: " + id);
        }
        allowedEmailRepository.deleteById(id);
    }
}

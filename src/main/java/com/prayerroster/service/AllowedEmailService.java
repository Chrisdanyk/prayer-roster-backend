package com.prayerroster.service;

import com.prayerroster.domain.AllowedEmail;
import com.prayerroster.repository.AllowedEmailRepository;
import com.prayerroster.service.dto.AllowedEmailDTO;
import com.prayerroster.web.rest.errors.BadRequestAlertException;
import com.prayerroster.web.rest.errors.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the invite allowlist. Deleting an entry does not revoke someone who has already signed in -
 * the allowlist governs first admission only, and {@code User.active} governs thereafter. See
 * docs/phase1-architecture.md section 10.
 */
@Service
@Transactional
public class AllowedEmailService {

    private static final String ENTITY_NAME = "allowedEmail";

    private final AllowedEmailRepository allowedEmailRepository;

    public AllowedEmailService(AllowedEmailRepository allowedEmailRepository) {
        this.allowedEmailRepository = allowedEmailRepository;
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
        return AllowedEmailDTO.from(allowedEmailRepository.save(allowedEmail));
    }

    public void delete(Long id) {
        if (!allowedEmailRepository.existsById(id)) {
            throw new EntityNotFoundException("Allowed email not found: " + id);
        }
        allowedEmailRepository.deleteById(id);
    }
}

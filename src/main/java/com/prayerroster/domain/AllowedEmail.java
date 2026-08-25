package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An email address permitted to authenticate. Admission is invite-only: an identity with no local
 * {@link User} row may only be provisioned if its email appears here.
 * <p>
 * This governs <em>first admission only</em>. Once a {@code User} row exists, {@code User.active}
 * governs access, so removing an entry never locks out someone who has already signed in - keeping
 * one mechanism per concern rather than two revocation paths that can disagree. A separate table is
 * required because {@code User.id} is the Google {@code sub} claim, which is unknowable until first
 * sign-in. See docs/phase1-architecture.md section 10.
 */
@Entity
@Table(name = "allowed_email")
public class AllowedEmail extends AbstractAuditingEntity<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sequenceGenerator")
    @SequenceGenerator(name = "sequenceGenerator", sequenceName = "sequence_generator", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    /** Always stored lowercased - see {@code AllowedEmailService}. */
    @NotNull
    @Email
    @Size(max = 254)
    @Column(name = "email", length = 254, nullable = false, unique = true)
    private String email;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}

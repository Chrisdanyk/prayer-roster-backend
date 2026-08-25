package com.prayerroster.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The application's user, persisted separately from JHipster's stateless OAuth2 account handling
 * (JHipster 8.11.0's {@code oauth2} authentication type does not generate a persisted user entity -
 * see {@code docs/phase1-architecture.md} section 2). The id is the Google ID token's stable
 * {@code sub} claim, so no separate external-identity column is needed.
 */
@Entity
@Table(name = "app_user")
public class User extends AbstractAuditingEntity<String> {

    private static final long serialVersionUID = 1L;

    /** The Google ID token's {@code sub} claim - stable, never reassigned. */
    @Id
    @Column(name = "id", length = 100)
    private String id;

    @NotNull
    @Email
    @Size(max = 254)
    @Column(name = "email", length = 254, nullable = false, unique = true)
    private String email;

    @Size(max = 100)
    @Column(name = "first_name", length = 100)
    private String firstName;

    @Size(max = 100)
    @Column(name = "last_name", length = 100)
    private String lastName;

    /** The {@code picture} claim from the Google ID token; absent for accounts with no avatar. */
    @Size(max = 512)
    @Column(name = "image_url", length = 512)
    private String imageUrl;

    @NotNull
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @NotNull
    @Column(name = "can_moderate", nullable = false)
    private boolean canModerate = false;

    @NotNull
    @Column(name = "can_preach", nullable = false)
    private boolean canPreach = false;

    @NotNull
    @Size(min = 2, max = 10)
    @Column(name = "lang_key", length = 10, nullable = false)
    private String langKey = "fr";

    @NotNull
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isCanModerate() {
        return canModerate;
    }

    public void setCanModerate(boolean canModerate) {
        this.canModerate = canModerate;
    }

    public boolean isCanPreach() {
        return canPreach;
    }

    public void setCanPreach(boolean canPreach) {
        this.canPreach = canPreach;
    }

    public String getLangKey() {
        return langKey;
    }

    public void setLangKey(String langKey) {
        this.langKey = langKey;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "User{" + "id='" + id + "'" + ", email='" + email + "'" + '}';
    }
}

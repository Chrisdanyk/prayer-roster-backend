package com.prayerroster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to Prayer Roster Backend.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    private final Liquibase liquibase = new Liquibase();

    private final Security security = new Security();

    // jhipster-needle-application-properties-property

    public Liquibase getLiquibase() {
        return liquibase;
    }

    public Security getSecurity() {
        return security;
    }

    // jhipster-needle-application-properties-property-getter

    public static class Liquibase {

        private Boolean asyncStart = true;

        public Boolean getAsyncStart() {
            return asyncStart;
        }

        public void setAsyncStart(Boolean asyncStart) {
            this.asyncStart = asyncStart;
        }
    }

    public static class Security {

        /**
         * Email of the account to promote to SUPER_ADMIN the first time it is seen. Only applied
         * when the local {@code User} row is first created - never re-checked on later logins, so
         * a later manual demotion of this account is never silently undone by a restart.
         */
        private String initialSuperAdminEmail;

        public String getInitialSuperAdminEmail() {
            return initialSuperAdminEmail;
        }

        public void setInitialSuperAdminEmail(String initialSuperAdminEmail) {
            this.initialSuperAdminEmail = initialSuperAdminEmail;
        }
    }
    // jhipster-needle-application-properties-property-class
}

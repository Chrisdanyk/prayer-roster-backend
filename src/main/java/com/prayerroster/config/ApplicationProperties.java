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

    private final Google google = new Google();

    private final Frontend frontend = new Frontend();

    // jhipster-needle-application-properties-property

    public Liquibase getLiquibase() {
        return liquibase;
    }

    public Security getSecurity() {
        return security;
    }

    public Google getGoogle() {
        return google;
    }

    public Frontend getFrontend() {
        return frontend;
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
    /**
     * Credentials for the server-side authorization-code exchange. This class is bound with
     * {@code ignoreUnknownFields = false}, so any {@code application.google.*} key added to a yml
     * file must have a matching field here or the context will not start. See
     * docs/phase1-architecture.md section 10.
     */
    public static class Google {

        private String clientId;

        private String clientSecret;

        /** Must exactly match a redirect URI registered in the Google Cloud console. */
        private String redirectUri;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }
    }

    /**
     * When set, the Google callback redirects the browser here with a single-use handoff instead of
     * returning JSON. Left blank, the JSON branch stays active - which is how this project performs
     * live verification, since a full @SpringBootTest cannot boot here.
     */
    public static class Frontend {

        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    // jhipster-needle-application-properties-property-class
}

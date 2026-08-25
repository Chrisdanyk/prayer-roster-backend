package com.prayerroster.web.rest.errors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Google itself failed or answered unusably. Maps to 502 via JHipster's {@code ExceptionTranslator},
 * which reads {@code @ResponseStatus} - the caller did nothing wrong, so this is not a 400.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class GoogleAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GoogleAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

    public GoogleAuthenticationException(String message) {
        super(message);
    }
}

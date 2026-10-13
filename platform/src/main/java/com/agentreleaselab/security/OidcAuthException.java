package com.agentreleaselab.security;

/** OIDC token validation failure. Carries a stable machine-readable code;
 *  no token material or secret is ever included in the message. */
public class OidcAuthException extends RuntimeException {
    private final String code;

    public OidcAuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public OidcAuthException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}

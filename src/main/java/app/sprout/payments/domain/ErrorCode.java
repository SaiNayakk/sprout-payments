package app.sprout.payments.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the payments contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request isn't valid"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in to continue"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "Open a Sprout account first"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough money"),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Bad signature"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}

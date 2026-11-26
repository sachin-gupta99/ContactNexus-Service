package com.sachingupta.contactnexus.common;

/** Correct credentials, but the address hasn't been confirmed yet. Mapped to 403. */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Confirm your email address before signing in. Check your inbox for the link.");
    }
}

package com.sachingupta.contactnexus.email;

/**
 * Transactional email abstraction. The service picks the implementation via
 * {@code app.email.mode}:
 *
 * <ul>
 *   <li>{@code smtp} - {@link SmtpEmailSender} using Spring Mail (prod, Gmail SMTP).</li>
 *   <li>{@code console} (default) - {@link ConsoleEmailSender} that logs the message so a
 *       developer running the app locally can copy the reset link out of stdout without
 *       ever sending a real email.</li>
 * </ul>
 */
public interface EmailSender {
    void send(String to, String subject, String htmlBody);
}

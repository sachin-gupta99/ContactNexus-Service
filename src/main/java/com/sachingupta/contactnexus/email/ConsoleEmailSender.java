package com.sachingupta.contactnexus.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev-mode fallback: prints the email to stdout. Keeps local development usable
 * without Gmail credentials and makes the reset link trivially visible in logs.
 */
@Component
@ConditionalOnProperty(name = "app.email.mode", havingValue = "console", matchIfMissing = true)
public class ConsoleEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

    @Override
    public void send(String to, String subject, String htmlBody) {
        log.info("""

                --- EMAIL (console mode) ---
                To:      {}
                Subject: {}
                ----------------------------
                {}
                --- END EMAIL ---
                """, to, subject, htmlBody);
    }
}

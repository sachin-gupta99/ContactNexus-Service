package com.sachingupta.contactnexus.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Sends via Spring Mail. In production this points at Gmail SMTP configured
 * through {@code spring.mail.*}. Any send failure is logged and swallowed - the
 * caller (password reset flow) never reveals email delivery status to the client
 * to keep the always-200 enumeration defence intact.
 */
@Component
@ConditionalOnProperty(name = "app.email.mode", havingValue = "smtp")
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender javaMailSender;

    @Value("${app.email.from}")
    private String fromAddress;

    @Value("${app.email.from-name:ContactNexus}")
    private String fromName;

    @Override
    public void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            javaMailSender.send(message);
            log.debug("Email dispatched to {}", to);
        } catch (MessagingException | UnsupportedEncodingException | MailException ex) {
            // Deliberately swallow. See class javadoc.
            log.warn("Failed to send email to {}: {}", to, ex.getMessage());
        }
    }
}

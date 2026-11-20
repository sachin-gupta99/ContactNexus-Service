package com.sachingupta.contactnexus.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Sends via the SendGrid Mail Send v3 HTTPS API. Chosen over SMTP because
 * Render's free tier blocks outbound SMTP (ports 25 / 465 / 587) and this
 * provider works over port 443 like any other HTTPS call.
 *
 * <p>Uses Spring's {@link RestClient} directly - no SDK dependency, keeping
 * the pom light. Any send failure is logged and swallowed so the password-reset
 * flow never leaks delivery status to the client (enumeration-safe always-200).
 */
@Component
@ConditionalOnProperty(name = "app.email.mode", havingValue = "sendgrid")
public class SendGridEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailSender.class);
    private static final String API_URL = "https://api.sendgrid.com/v3/mail/send";

    private final RestClient restClient;
    private final String fromAddress;
    private final String fromName;

    public SendGridEmailSender(
            @Value("${app.email.sendgrid.api-key}") String apiKey,
            @Value("${app.email.from}") String fromAddress,
            @Value("${app.email.from-name:ContactNexus}") String fromName) {
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        // SendGrid Mail Send v3 payload: https://docs.sendgrid.com/api-reference/mail-send/mail-send
        Map<String, Object> payload = Map.of(
                "personalizations", List.of(Map.of("to", List.of(Map.of("email", to)))),
                "from", Map.of("email", fromAddress, "name", fromName),
                "subject", subject,
                "content", List.of(Map.of("type", "text/html", "value", htmlBody)));

        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("SendGrid accepted email to {}", to);
        } catch (RestClientException ex) {
            // Deliberately swallow. The caller (password reset) must not reveal
            // whether the email address is registered or whether delivery worked.
            log.warn("SendGrid send failed for {}: {}", to, ex.getMessage());
        }
    }
}

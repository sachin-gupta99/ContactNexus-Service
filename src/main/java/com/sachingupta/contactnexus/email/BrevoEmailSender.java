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
 * Sends via the Brevo (ex-Sendinblue) Transactional Email v3 HTTPS API.
 * Picked over Gmail SMTP because Render's free tier blocks outbound SMTP
 * (ports 25 / 465 / 587); the Brevo API uses port 443 like any other
 * HTTPS call and works from a free Render instance.
 *
 * <p>No SDK dependency - Spring's {@link RestClient} does the POST. Send
 * failures are logged and swallowed so the password-reset flow never leaks
 * delivery status to the client (the always-200 enumeration defence).
 */
@Component
@ConditionalOnProperty(name = "app.email.mode", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);
    private static final String API_URL = "https://api.brevo.com/v3/smtp/email";

    private final RestClient restClient;
    private final String fromAddress;
    private final String fromName;

    public BrevoEmailSender(
            @Value("${app.email.brevo.api-key}") String apiKey,
            @Value("${app.email.from}") String fromAddress,
            @Value("${app.email.from-name:ContactNexus}") String fromName) {
        this.restClient = RestClient.builder()
                .baseUrl(API_URL)
                // Brevo uses an `api-key` header, not Bearer.
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        // https://developers.brevo.com/reference/sendtransacemail
        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", fromName, "email", fromAddress),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", htmlBody);

        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Brevo accepted email to {}", to);
        } catch (RestClientException ex) {
            // Deliberately swallow. See class javadoc.
            log.warn("Brevo send failed for {}: {}", to, ex.getMessage());
        }
    }
}

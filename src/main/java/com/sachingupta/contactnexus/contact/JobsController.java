package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;

/**
 * Lets an external scheduler (a GitHub Actions cron) run daily jobs, because
 * Render's free instance sleeps and the in-app schedule doesn't fire while it
 * does. Calling it also wakes the service. Disabled (404) unless
 * {@code app.jobs.token} is set; the token is compared in constant time.
 */
@RestController
@RequestMapping("/api/v1/internal/jobs")
@RequiredArgsConstructor
public class JobsController {

    private final BirthdayService birthdays;

    @Value("${app.jobs.token:}")
    private String token;

    @PostMapping("/birthday-reminders")
    public Map<String, Object> birthdayReminders(@RequestHeader(value = "X-Jobs-Token", required = false) String supplied) {
        if (token == null || token.isBlank()) {
            throw new NotFoundException("Not found");
        }
        if (supplied == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid jobs token");
        }
        LocalDate tomorrow = LocalDate.now(birthdays.zone()).plusDays(1);
        return Map.of("date", tomorrow.toString(), "emailsSent", birthdays.sendRemindersFor(tomorrow));
    }
}

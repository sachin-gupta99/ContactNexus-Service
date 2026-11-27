package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.AfterCommit;
import com.sachingupta.contactnexus.contact.dto.UpcomingBirthday;
import com.sachingupta.contactnexus.email.EmailSender;
import com.sachingupta.contactnexus.user.User;
import com.sachingupta.contactnexus.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Upcoming birthdays, and the daily "X's birthday is tomorrow" email.
 *
 * <p>The job runs on a schedule inside the app, and can also be triggered over
 * HTTP ({@code JobsController}) because Render's free instance sleeps and an
 * in-process schedule doesn't fire while it's asleep. {@code reminder_log} makes
 * the two safe to combine: a reminder is recorded before it is sent and never
 * sent twice for the same contact and date.
 */
@Service
@RequiredArgsConstructor
public class BirthdayService {

    private static final Logger log = LoggerFactory.getLogger(BirthdayService.class);
    static final String KIND = "BIRTHDAY";

    private final ContactRepository contacts;
    private final UserRepository users;
    private final EmailSender emailSender;
    private final JdbcTemplate jdbc;
    private final ContactMapper mapper;

    @Value("${app.reminders.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${app.base-url}")
    private String baseUrl;

    public ZoneId zone() {
        return zone;
    }

    @Transactional(readOnly = true)
    public List<UpcomingBirthday> upcoming(Long ownerId, int days, LocalDate today) {
        List<UpcomingBirthday> out = new ArrayList<>();
        for (Contact c : contacts.findWithBirthday(ownerId)) {
            LocalDate next = nextOccurrence(c.getBirthdayMonth(), c.getBirthdayDay(), today);
            long inDays = ChronoUnit.DAYS.between(today, next);
            if (inDays <= days) {
                Integer turning = c.getBirthdayYear() == null ? null : next.getYear() - c.getBirthdayYear();
                out.add(new UpcomingBirthday(mapper.toResponse(c), next, inDays, turning));
            }
        }
        out.sort(Comparator.comparingLong(UpcomingBirthday::inDays)
                .thenComparing(b -> b.contact().firstName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * The next date (today or later) this birthday is celebrated. 29 February is
     * celebrated on 28 February in non-leap years.
     */
    static LocalDate nextOccurrence(int month, int day, LocalDate today) {
        LocalDate thisYear = onYear(month, day, today.getYear());
        return thisYear.isBefore(today) ? onYear(month, day, today.getYear() + 1) : thisYear;
    }

    private static LocalDate onYear(int month, int day, int year) {
        MonthDay md = MonthDay.of(month, day);
        return md.isValidYear(year) ? md.atYear(year) : LocalDate.of(year, 2, 28);
    }

    /** Month/day pairs celebrated on {@code date}, including 29 Feb on the 28th in non-leap years. */
    static List<MonthDay> celebratedOn(LocalDate date) {
        List<MonthDay> days = new ArrayList<>(List.of(MonthDay.from(date)));
        if (date.getMonthValue() == 2 && date.getDayOfMonth() == 28 && !date.isLeapYear()) {
            days.add(MonthDay.of(2, 29));
        }
        return days;
    }

    @Scheduled(cron = "${app.reminders.cron:0 0 8 * * *}", zone = "${app.reminders.zone:Asia/Kolkata}")
    public void scheduled() {
        int sent = sendRemindersFor(LocalDate.now(zone).plusDays(1));
        if (sent > 0) log.info("Sent {} birthday reminder email(s)", sent);
    }

    /**
     * Emails each owner (once) about contacts whose birthday is on {@code date}.
     * @return how many emails were sent
     */
    @Transactional
    public int sendRemindersFor(LocalDate date) {
        List<Contact> due = new ArrayList<>();
        for (MonthDay md : celebratedOn(date)) {
            due.addAll(contacts.findByBirthday(md.getMonthValue(), List.of(md.getDayOfMonth())));
        }
        if (due.isEmpty()) return 0;

        Map<Long, User> owners = users.findAllById(due.stream().map(Contact::getOwnerId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Map<User, List<Contact>> byOwner = new LinkedHashMap<>();
        for (Contact c : due) {
            User owner = owners.get(c.getOwnerId());
            if (owner == null || !owner.isBirthdayReminders() || !owner.isEmailVerified()) continue;
            // Claim the reminder first; a second run (or a concurrent one) gets 0 rows and skips it.
            int claimed = jdbc.update("""
                    insert into reminder_log (contact_id, kind, for_date, sent_at)
                    values (?, ?, ?, now()) on conflict do nothing""", c.getId(), KIND, date);
            if (claimed == 1) byOwner.computeIfAbsent(owner, u -> new ArrayList<>()).add(c);
        }

        byOwner.forEach((owner, list) -> {
            String to = owner.getEmail();
            String subject = list.size() == 1
                    ? "%s's birthday is tomorrow".formatted(displayName(list.get(0)))
                    : "%d birthdays tomorrow".formatted(list.size());
            String html = reminderHtml(owner.getName(), list, date);
            AfterCommit.run(() -> emailSender.send(to, subject, html));
        });
        return byOwner.size();
    }

    private String reminderHtml(String ownerName, List<Contact> list, LocalDate date) {
        StringBuilder items = new StringBuilder();
        for (Contact c : list) {
            String turning = c.getBirthdayYear() == null ? "" : " (turns %d)".formatted(date.getYear() - c.getBirthdayYear());
            items.append("<li><strong>").append(esc(displayName(c))).append("</strong>").append(turning);
            if (c.getPhone() != null && !c.getPhone().isBlank()) items.append(" - ").append(esc(c.getPhone()));
            items.append("</li>");
        }
        return """
                <p>Hi %s,</p>
                <p>Tomorrow is a birthday worth remembering:</p>
                <ul>%s</ul>
                <p><a href="%s/contacts">Open Contact Nexus</a></p>
                <p style="color:#6b7280;font-size:12px">You can turn these emails off in Settings.</p>
                """.formatted(esc(ownerName), items, baseUrl);
    }

    static String displayName(Contact c) {
        return c.getLastName() == null || c.getLastName().isBlank()
                ? c.getFirstName()
                : c.getFirstName() + " " + c.getLastName();
    }

    private static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

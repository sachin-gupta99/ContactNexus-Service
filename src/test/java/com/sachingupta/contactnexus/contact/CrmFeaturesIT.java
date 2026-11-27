package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.jobs.token=test-jobs-token")
class CrmFeaturesIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BirthdayService birthdays;

    TestUser owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = newUser();
    }

    private long create(String body) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/v1/contacts"), body).header("Authorization", owner.bearer()))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(r, "$.id")).longValue();
    }

    private String auth() {
        return owner.bearer();
    }

    // ------------------------------------------------------------ search

    @Test
    void typoTolerantSearchFindsCloseNames() throws Exception {
        create("""
                {"firstName":"Ravi","lastName":"Kumar","email":"rk@x.com"}""");
        create("""
                {"firstName":"Priya","lastName":"Patel","email":"pp@x.com"}""");
        create("""
                {"firstName":"Ramesh","email":"ramesh@x.com"}""");
        create("""
                {"firstName":"Ravi K","email":"rk2@x.com"}""");

        mvc.perform(get("/api/v1/contacts").param("q", "rvi").header("Authorization", auth()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Ravi", "Ravi K")));
        mvc.perform(get("/api/v1/contacts").param("q", "priay patle").header("Authorization", auth()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Priya")));
        // Two letters: plain "contains" only, no fuzzy noise.
        mvc.perform(get("/api/v1/contacts").param("q", "zz").header("Authorization", auth()))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // ------------------------------------------------------------ notes, timeline, birthdays

    @Test
    void timelineKeepsLastContactedInStep() throws Exception {
        long id = create("""
                {"firstName":"Tim","email":"tim@x.com","notes":"Met at the AWS meetup.\\nLikes hiking."}""");
        mvc.perform(get("/api/v1/contacts/" + id).header("Authorization", auth()))
                .andExpect(jsonPath("$.notes").value("Met at the AWS meetup.\nLikes hiking."))
                .andExpect(jsonPath("$.lastContactedAt").value(nullValue()));

        MvcResult older = mvc.perform(json(post("/api/v1/contacts/" + id + "/interactions"),
                        "{\"type\":\"MEETING\",\"note\":\"Coffee\",\"occurredAt\":\"2026-01-10T09:00:00Z\"}")
                        .header("Authorization", auth()))
                .andExpect(status().isCreated()).andReturn();
        MvcResult newer = mvc.perform(json(post("/api/v1/contacts/" + id + "/interactions"),
                        "{\"type\":\"CALL\",\"occurredAt\":\"2026-02-01T09:00:00Z\"}").header("Authorization", auth()))
                .andExpect(status().isCreated()).andReturn();
        mvc.perform(get("/api/v1/contacts/" + id).header("Authorization", auth()))
                .andExpect(jsonPath("$.lastContactedAt").value("2026-02-01T09:00:00Z"));
        mvc.perform(get("/api/v1/contacts/" + id + "/interactions").header("Authorization", auth()))
                .andExpect(jsonPath("$[*].type", contains("CALL", "MEETING")));
        create("{\"firstName\":\"Never\",\"email\":\"never@x.com\"}");
        mvc.perform(get("/api/v1/contacts").param("sort", "lastContactedAt,desc").header("Authorization", auth()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Tim", "Never")));

        Number newerId = read(newer, "$.id");
        mvc.perform(delete("/api/v1/contacts/" + id + "/interactions/" + newerId).header("Authorization", auth()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/contacts/" + id).header("Authorization", auth()))
                .andExpect(jsonPath("$.lastContactedAt").value("2026-01-10T09:00:00Z"));

        mvc.perform(json(post("/api/v1/contacts/" + id + "/interactions"),
                "{\"type\":\"CALL\",\"occurredAt\":\"2099-01-01T00:00:00Z\"}").header("Authorization", auth()))
                .andExpect(status().isBadRequest());
        TestUser other = newUser();
        mvc.perform(get("/api/v1/contacts/" + id + "/interactions").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        Number olderId = read(older, "$.id");
        mvc.perform(delete("/api/v1/contacts/" + id + "/interactions/" + olderId).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void birthdaysAreValidatedAndListedSoonestFirst() throws Exception {
        LocalDate today = LocalDate.now(birthdays.zone());
        LocalDate in3 = today.plusDays(3), in10 = today.plusDays(10);
        create("""
                {"firstName":"Soon","email":"soon@x.com","birthdayMonth":%d,"birthdayDay":%d,"birthdayYear":1990}"""
                .formatted(in3.getMonthValue(), in3.getDayOfMonth()));
        create("""
                {"firstName":"Later","email":"later@x.com","birthdayMonth":%d,"birthdayDay":%d}"""
                .formatted(in10.getMonthValue(), in10.getDayOfMonth()));

        mvc.perform(get("/api/v1/contacts/birthdays").param("days", "30").header("Authorization", auth()))
                .andExpect(jsonPath("$[*].contact.firstName", contains("Soon", "Later")))
                .andExpect(jsonPath("$[0].inDays").value(3))
                .andExpect(jsonPath("$[0].turning").value(in3.getYear() - 1990))
                .andExpect(jsonPath("$[1].turning").value(nullValue()));
        mvc.perform(get("/api/v1/contacts/birthdays").param("days", "5").header("Authorization", auth()))
                .andExpect(jsonPath("$[*].contact.firstName", contains("Soon")));

        for (String bad : List.of(
                "{\"firstName\":\"X\",\"email\":\"x@x.com\",\"birthdayMonth\":4,\"birthdayDay\":31}",
                "{\"firstName\":\"X\",\"email\":\"x@x.com\",\"birthdayMonth\":2,\"birthdayDay\":29,\"birthdayYear\":2023}",
                "{\"firstName\":\"X\",\"email\":\"x@x.com\",\"birthdayDay\":5}",
                "{\"firstName\":\"X\",\"email\":\"x@x.com\",\"birthdayMonth\":13,\"birthdayDay\":1}")) {
            mvc.perform(json(post("/api/v1/contacts"), bad).header("Authorization", auth()))
                    .andExpect(status().isBadRequest());
        }
        create("""
                {"firstName":"Leap","email":"leap@x.com","birthdayMonth":2,"birthdayDay":29}""");
    }

    @Test
    void remindersGoOutOncePerContactAndRespectThePreference() throws Exception {
        LocalDate date = LocalDate.of(2031, 7, 14);
        create("""
                {"firstName":"Ravi","email":"ravi@x.com","birthdayMonth":7,"birthdayDay":14,"birthdayYear":1995}""");
        TestUser optedOut = newUser();
        mvc.perform(json(put("/api/v1/users/me/preferences"), "{\"birthdayReminders\":false}")
                        .header("Authorization", optedOut.bearer()))
                .andExpect(jsonPath("$.birthdayReminders").value(false));
        mvc.perform(json(post("/api/v1/contacts"), """
                {"firstName":"Quiet","email":"q@x.com","birthdayMonth":7,"birthdayDay":14}""")
                .header("Authorization", optedOut.bearer())).andExpect(status().isCreated());

        clearInvocations(emailSender);
        assertThat(birthdays.sendRemindersFor(date)).isPositive();
        verify(emailSender).send(eq(owner.email()), eq("Ravi's birthday is tomorrow"), org.mockito.ArgumentMatchers.contains("turns 36"));
        verify(emailSender, never()).send(eq(optedOut.email()), anyString(), anyString());

        clearInvocations(emailSender);
        birthdays.sendRemindersFor(date); // second run (e.g. cron + external trigger)
        verify(emailSender, never()).send(eq(owner.email()), anyString(), anyString());
    }

    @Test
    void jobsEndpointNeedsTheToken() throws Exception {
        mvc.perform(post("/api/v1/internal/jobs/birthday-reminders").with(freshIp())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/internal/jobs/birthday-reminders").with(freshIp()).header("X-Jobs-Token", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/internal/jobs/birthday-reminders").with(freshIp()).header("X-Jobs-Token", "test-jobs-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(LocalDate.now(birthdays.zone()).plusDays(1).toString()));
    }

    // ------------------------------------------------------------ bulk + merge

    @Test
    void bulkActionsOnlyTouchYourContacts() throws Exception {
        long a = create("{\"firstName\":\"A\",\"email\":\"a@x.com\"}");
        long b = create("{\"firstName\":\"B\",\"email\":\"b@x.com\",\"tags\":[\"Old\"]}");
        TestUser other = newUser();
        MvcResult theirs = mvc.perform(json(post("/api/v1/contacts"), "{\"firstName\":\"T\",\"email\":\"t@x.com\"}")
                .header("Authorization", other.bearer())).andReturn();
        long t = ((Number) read(theirs, "$.id")).longValue();

        mvc.perform(json(post("/api/v1/contacts/bulk"), "{\"ids\":[%d,%d,%d],\"action\":\"FAVORITE\"}".formatted(a, b, t))
                        .header("Authorization", auth()))
                .andExpect(jsonPath("$.affected").value(2));
        mvc.perform(json(post("/api/v1/contacts/bulk"), "{\"ids\":[%d,%d],\"action\":\"ADD_TAGS\",\"tags\":[\"Team\"]}".formatted(a, b))
                .header("Authorization", auth())).andExpect(jsonPath("$.affected").value(2));
        mvc.perform(json(post("/api/v1/contacts/bulk"), "{\"ids\":[%d],\"action\":\"REMOVE_TAGS\",\"tags\":[\"old\"]}".formatted(b))
                .header("Authorization", auth())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/contacts/tags").header("Authorization", auth()))
                .andExpect(jsonPath("$[*].name", contains("Team")));
        mvc.perform(json(post("/api/v1/contacts/bulk"), "{\"ids\":[%d],\"action\":\"ADD_TAGS\"}".formatted(a))
                .header("Authorization", auth())).andExpect(status().isBadRequest());

        mvc.perform(json(post("/api/v1/contacts/bulk"), "{\"ids\":[%d,%d,%d],\"action\":\"DELETE\"}".formatted(a, b, t))
                .header("Authorization", auth())).andExpect(jsonPath("$.affected").value(2));
        mvc.perform(get("/api/v1/contacts").header("Authorization", auth())).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/contacts/" + t).header("Authorization", other.bearer())).andExpect(status().isOk());
    }

    @Test
    void mergeCombinesFieldsTagsTimelineAndFavourite() throws Exception {
        long keep = create("""
                {"firstName":"Ravi","email":"ravi@x.com","tags":["Work"]}""");
        long dup = create("""
                {"firstName":"Ravi K","lastName":"Kumar","email":"RAVI@x.com","phone":"98765 43210","tags":["Gym"]}""");
        mvc.perform(json(put("/api/v1/contacts/" + dup + "/favorite"), "{\"favorite\":true}").header("Authorization", auth()));
        mvc.perform(json(post("/api/v1/contacts/" + dup + "/interactions"),
                "{\"type\":\"CALL\",\"occurredAt\":\"2026-03-01T10:00:00Z\"}").header("Authorization", auth()));

        mvc.perform(json(post("/api/v1/contacts/merge"), """
                        {"keepId":%d,"mergeIds":[%d],
                         "values":{"firstName":"Ravi","lastName":"Kumar","email":"ravi@x.com","phone":"98765 43210"}}"""
                        .formatted(keep, dup)).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(keep))
                .andExpect(jsonPath("$.lastName").value("Kumar"))
                .andExpect(jsonPath("$.phone").value("98765 43210"))
                .andExpect(jsonPath("$.favorite").value(true))
                .andExpect(jsonPath("$.tags", containsInAnyOrder("Gym", "Work")))
                .andExpect(jsonPath("$.lastContactedAt").value("2026-03-01T10:00:00Z"));

        mvc.perform(get("/api/v1/contacts/" + dup).header("Authorization", auth())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/contacts/" + keep + "/interactions").header("Authorization", auth()))
                .andExpect(jsonPath("$[*].type", contains("CALL")));
        mvc.perform(get("/api/v1/contacts/duplicates").header("Authorization", auth()))
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(json(post("/api/v1/contacts/merge"), """
                {"keepId":%d,"mergeIds":[%d],"values":{"firstName":"X","email":"x@x.com"}}""".formatted(keep, keep))
                .header("Authorization", auth())).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ shareable card

    @Test
    void publicCardShowsOnlyChosenFieldsAndStopsWorkingWhenRotated() throws Exception {
        mvc.perform(json(put("/api/v1/users/me"), """
                {"name":"Test User","profile":{"work":"Engineer","phone":"555 0101","city":"Pune","github":"https://github.com/test"}}""")
                .header("Authorization", auth())).andExpect(status().isOk());

        mvc.perform(get("/api/v1/users/me/card-share").header("Authorization", auth()))
                .andExpect(jsonPath("$.enabled").value(false));
        MvcResult on = mvc.perform(json(put("/api/v1/users/me/card-share"), "{\"fields\":[\"work\",\"EMAIL\"]}")
                        .header("Authorization", auth()))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.fields", containsInAnyOrder("EMAIL", "WORK")))
                .andReturn();
        String token = read(on, "$.token");
        assertThat(token).hasSizeGreaterThanOrEqualTo(32);

        mvc.perform(get("/api/v1/public/cards/" + token).with(freshIp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test User"))
                .andExpect(jsonPath("$.work").value("Engineer"))
                .andExpect(jsonPath("$.email").value(owner.email()))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.city").value(nullValue()))
                .andExpect(jsonPath("$.github").value(nullValue()));

        mvc.perform(json(put("/api/v1/users/me/card-share"), "{\"fields\":[\"PASSWORD\"]}").header("Authorization", auth()))
                .andExpect(status().isBadRequest());

        MvcResult rotated = mvc.perform(post("/api/v1/users/me/card-share/rotate").header("Authorization", auth()))
                .andExpect(status().isOk()).andReturn();
        String fresh = read(rotated, "$.token");
        assertThat(fresh).isNotEqualTo(token);
        mvc.perform(get("/api/v1/public/cards/" + token).with(freshIp())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/cards/" + fresh).with(freshIp())).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/users/me/card-share").header("Authorization", auth())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/public/cards/" + fresh).with(freshIp())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from card_shares where user_id = ?", Long.class, owner.id())).isZero();
    }

    @Test
    void deletingAContactCascadesItsTimelineAndReminders() throws Exception {
        long id = create("""
                {"firstName":"Cas","email":"cas@x.com","birthdayMonth":3,"birthdayDay":3}""");
        mvc.perform(json(post("/api/v1/contacts/" + id + "/interactions"), "{\"type\":\"EMAIL\"}").header("Authorization", auth()));
        birthdays.sendRemindersFor(LocalDate.of(2032, 3, 3));
        assertThat(jdbc.queryForObject("select count(*) from reminder_log where contact_id = ?", Long.class, id)).isEqualTo(1);

        mvc.perform(delete("/api/v1/contacts/" + id).header("Authorization", auth())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from interactions where contact_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from reminder_log where contact_id = ?", Long.class, id)).isZero();
    }
}

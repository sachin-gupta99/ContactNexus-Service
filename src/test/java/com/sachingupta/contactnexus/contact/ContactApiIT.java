package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The queries and cascades that unit tests can't reach: these run against real Postgres. */
class ContactApiIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    TestUser owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = newUser();
    }

    private long create(TestUser u, String body) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/v1/contacts"), body).header("Authorization", u.bearer()))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) read(r, "$.id")).longValue();
    }

    private long create(String body) throws Exception {
        return create(owner, body);
    }

    @Test
    void searchMatchesNamesAndEmailsAndTreatsWildcardsLiterally() throws Exception {
        create("""
                {"firstName":"Promo","lastName":"100%","email":"promo@x.com"}""");
        create("""
                {"firstName":"Ann_Marie","email":"ann@x.com"}""");
        create("""
                {"firstName":"Bob","email":"bob@work.com"}""");

        mvc.perform(get("/api/v1/contacts").param("q", "100%").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Promo")));
        // "_" is a single-character wildcard in LIKE: unescaped, "a_n" would match "Ann".
        mvc.perform(get("/api/v1/contacts").param("q", "a_n").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mvc.perform(get("/api/v1/contacts").param("q", "ANN_").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Ann_Marie")));
        mvc.perform(get("/api/v1/contacts").param("q", "work.com").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Bob")));
    }

    @Test
    void tagFilterFavouritesAndSortWorkTogether() throws Exception {
        create("""
                {"firstName":"Zed","email":"zed@x.com","tags":["Work"]}""");
        long amy = create("""
                {"firstName":"Amy","email":"amy@x.com","tags":["work","Gym"]}""");
        create("""
                {"firstName":"Mia","email":"mia@x.com","tags":["Family"]}""");
        mvc.perform(json(put("/api/v1/contacts/" + amy + "/favorite"), "{\"favorite\":true}")
                .header("Authorization", owner.bearer())).andExpect(status().isOk());

        mvc.perform(get("/api/v1/contacts").param("tag", "WORK").param("sort", "firstName,desc")
                        .header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Zed", "Amy")));
        mvc.perform(get("/api/v1/contacts").param("sort", "favorite,desc").param("sort", "firstName,asc")
                        .header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Amy", "Mia", "Zed")));
        mvc.perform(get("/api/v1/contacts").param("favorite", "true").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("Amy")));
        mvc.perform(get("/api/v1/contacts/tags").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$[*].name", contains("Family", "Gym", "Work")))
                .andExpect(jsonPath("$[2].count").value(2));
        mvc.perform(get("/api/v1/contacts").param("sort", "ownerId,asc").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unusedTagsDisappearAndLinkRowsCascade() throws Exception {
        long id = create("""
                {"firstName":"Tia","email":"tia@x.com","tags":["Solo"]}""");
        assertThat(jdbc.queryForObject("select count(*) from tags where owner_id = ?", Long.class, owner.id())).isEqualTo(1);

        mvc.perform(delete("/api/v1/contacts/" + id).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from tags where owner_id = ?", Long.class, owner.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from contact_tags where contact_id = ?", Long.class, id)).isZero();
    }

    @Test
    void anotherUserCanNeitherSeeNorChangeYourContacts() throws Exception {
        long id = create("""
                {"firstName":"Private","email":"p@x.com"}""");
        TestUser other = newUser();

        mvc.perform(get("/api/v1/contacts/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/contacts/" + id), "{\"firstName\":\"Hacked\",\"email\":\"h@x.com\"}")
                .header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/contacts/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/contacts").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/contacts/" + id).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.firstName").value("Private"));
    }

    @Test
    void importSkipsExistingAndRepeatedEmailsAndKeepsTags() throws Exception {
        create("""
                {"firstName":"Old","email":"old@x.com"}""");
        mvc.perform(json(post("/api/v1/contacts/import"), """
                        {"contacts":[
                          {"firstName":"A","email":"OLD@x.com"},
                          {"firstName":"B","email":"new@x.com","tags":["Imported"]},
                          {"firstName":"C","email":"NEW@x.com"}]}""").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skippedDuplicates").value(2));
        mvc.perform(get("/api/v1/contacts").param("tag", "imported").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.content[*].firstName", contains("B")));
    }

    @Test
    void duplicatesAndStats() throws Exception {
        create("""
                {"firstName":"Ravi","email":"ravi@x.com","phone":"+91 98765 43210","tags":["Work"]}""");
        create("""
                {"firstName":"Ravi K","email":"RAVI@x.com"}""");
        create("""
                {"firstName":"R. Kumar","email":"rk@x.com","phone":"098765-43210"}""");

        mvc.perform(get("/api/v1/contacts/duplicates").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$[*].reason", containsInAnyOrder("email", "phone")));
        mvc.perform(get("/api/v1/contacts/stats").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.tags").value(1));
    }

    @Test
    void deletingTheAccountRemovesContactsTagsAndTokens() throws Exception {
        create("""
                {"firstName":"Gone","email":"gone@x.com","tags":["Bye"]}""");
        mvc.perform(json(delete("/api/v1/users/me"), "{\"password\":\"password123\"}").with(freshIp())
                .header("Authorization", owner.bearer())).andExpect(status().isNoContent());

        for (String table : List.of("contacts", "tags")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where owner_id = ?", Long.class, owner.id()))
                    .as(table).isZero();
        }
        for (String table : List.of("refresh_tokens", "email_verification_tokens", "password_reset_tokens")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, owner.id()))
                    .as(table).isZero();
        }
    }
}

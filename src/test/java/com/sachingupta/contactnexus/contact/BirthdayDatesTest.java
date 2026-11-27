package com.sachingupta.contactnexus.contact;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;

class BirthdayDatesTest {

    @Test
    void nextOccurrenceIsTodayOrLater() {
        LocalDate today = LocalDate.of(2026, 11, 27);
        assertThat(BirthdayService.nextOccurrence(11, 27, today)).isEqualTo(today);
        assertThat(BirthdayService.nextOccurrence(12, 25, today)).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(BirthdayService.nextOccurrence(1, 3, today)).isEqualTo(LocalDate.of(2027, 1, 3));
    }

    @Test
    void leapDayBirthdaysAreCelebratedOnThe28thInOtherYears() {
        assertThat(BirthdayService.nextOccurrence(2, 29, LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(BirthdayService.nextOccurrence(2, 29, LocalDate.of(2028, 1, 1))).isEqualTo(LocalDate.of(2028, 2, 29));

        assertThat(BirthdayService.celebratedOn(LocalDate.of(2027, 2, 28)))
                .containsExactlyInAnyOrder(MonthDay.of(2, 28), MonthDay.of(2, 29));
        assertThat(BirthdayService.celebratedOn(LocalDate.of(2028, 2, 28))).containsExactly(MonthDay.of(2, 28));
        assertThat(BirthdayService.celebratedOn(LocalDate.of(2028, 2, 29))).containsExactly(MonthDay.of(2, 29));
    }

    @Test
    void fuzzySearchNeedsThreeCharacters() {
        assertThat(ContactService.fuzzyTerm("ra")).isEmpty();
        assertThat(ContactService.fuzzyTerm("  RVI ")).isEqualTo("rvi");
        assertThat(ContactService.fuzzyTerm(null)).isEmpty();
    }
}

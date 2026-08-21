package io.github.jaymcole.housegraph.plugins.schedule;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeeklyScheduleTest {

    /** A Wednesday at 07:00 UTC, used as a fixed "now" throughout. */
    private static final ZonedDateTime WEDNESDAY_MORNING =
            ZonedDateTime.of(2026, 8, 19, 7, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void firesLaterTodayWhenTodayIsSelectedAndTimeHasNotPassed() {
        ZonedDateTime next = WeeklySchedule.nextFireAfter(WEDNESDAY_MORNING, days(DayOfWeek.WEDNESDAY), LocalTime.of(9, 0));

        assertEquals(ZonedDateTime.of(2026, 8, 19, 9, 0, 0, 0, ZoneOffset.UTC), next);
    }

    @Test
    void skipsToNextWeekWhenOnlyTodayIsSelectedAndTimeHasAlreadyPassed() {
        ZonedDateTime next = WeeklySchedule.nextFireAfter(WEDNESDAY_MORNING, days(DayOfWeek.WEDNESDAY), LocalTime.of(6, 0));

        assertEquals(ZonedDateTime.of(2026, 8, 26, 6, 0, 0, 0, ZoneOffset.UTC), next,
                "today's slot already passed, so the next Wednesday is a full week out");
    }

    @Test
    void picksTheNearestOfSeveralSelectedDays() {
        ZonedDateTime next = WeeklySchedule.nextFireAfter(
                WEDNESDAY_MORNING, days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), LocalTime.of(8, 0));

        assertEquals(ZonedDateTime.of(2026, 8, 21, 8, 0, 0, 0, ZoneOffset.UTC), next, "Friday is nearest");
    }

    @Test
    void treatsAnExactCurrentMomentAsAlreadyFired() {
        ZonedDateTime next = WeeklySchedule.nextFireAfter(WEDNESDAY_MORNING, days(DayOfWeek.WEDNESDAY), LocalTime.of(7, 0));

        assertEquals(ZonedDateTime.of(2026, 8, 26, 7, 0, 0, 0, ZoneOffset.UTC), next,
                "a candidate equal to 'now' isn't strictly after it, so it rolls to next week");
    }

    @Test
    void rejectsAnEmptyDaySet() {
        assertThrows(IllegalArgumentException.class,
                () -> WeeklySchedule.nextFireAfter(WEDNESDAY_MORNING, Set.of(), LocalTime.of(8, 0)));
    }

    private static Set<DayOfWeek> days(DayOfWeek... days) {
        Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : days) {
            set.add(day);
        }
        return set;
    }
}

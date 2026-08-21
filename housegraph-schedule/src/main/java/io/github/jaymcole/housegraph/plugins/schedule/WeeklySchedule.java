package io.github.jaymcole.housegraph.plugins.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Pure calculation of "when does a day-of-week + time-of-day schedule next fire", kept free of
 * JavaFX and any node/timer machinery so it can be tested directly against fixed instants rather
 * than through a running {@code Timeline} (same split as {@code GitRepoSync}/{@code GitSyncNode}).
 */
public final class WeeklySchedule {

    private WeeklySchedule() {
    }

    /**
     * The next moment at or after {@code from} (strictly after, when {@code from} itself lands
     * exactly on a scheduled fire) that falls on one of {@code days} at {@code timeOfDay}.
     * <p>
     * Checks {@code from}'s own day first, then each of the next 7 days — enough to guarantee a
     * match for any non-empty {@code days}, since the week repeats every 7 days.
     *
     * @param from      the moment to search forward from, in the zone the result should carry
     * @param days      the days of the week this schedule fires on; must not be empty
     * @param timeOfDay the time of day it fires on each selected day
     * @return the next matching moment, strictly after {@code from}
     * @throws IllegalArgumentException if {@code days} is empty
     */
    public static ZonedDateTime nextFireAfter(ZonedDateTime from, Set<DayOfWeek> days, LocalTime timeOfDay) {
        if (days.isEmpty()) {
            throw new IllegalArgumentException("Can't compute a next fire time with no days selected");
        }
        for (int offset = 0; offset <= 7; offset++) {
            ZonedDateTime candidate = from.toLocalDate().plusDays(offset).atTime(timeOfDay).atZone(from.getZone());
            if (days.contains(candidate.getDayOfWeek()) && candidate.isAfter(from)) {
                return candidate;
            }
        }
        // Unreachable: offsets 0..7 span a full week, so a non-empty day set always matches.
        throw new IllegalStateException("No matching day found within a full week");
    }
}

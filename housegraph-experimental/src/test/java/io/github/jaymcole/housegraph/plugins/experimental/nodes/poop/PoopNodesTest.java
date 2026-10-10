package io.github.jaymcole.housegraph.plugins.experimental.nodes.poop;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both nodes are pure functions of their rows, so they're driven the way the engine pulls a data
 * node: set inputs by port name, run {@code process()}, read outputs by port name. Timestamps are
 * built in the system zone the nodes read days in, and Now is always wired, so the day boundaries
 * under test are the same wherever this runs.
 */
class PoopNodesTest {

    /** A Saturday, at noon — late enough that "today" has room for a morning poop. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

    private static long millis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Map<String, Object> row(String userId, String userName, String didPoop, String quality,
                                           String size, LocalDateTime at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", userId);
        row.put("userName", userName);
        row.put("didPoop", didPoop);
        row.put("quality", quality);
        row.put("size", size);
        row.put("created_at", millis(at));
        return row;
    }

    /** A poop {@code daysAgo} days before NOW's date, at {@code hour}. */
    private static Map<String, Object> poop(String userId, String userName, String quality, String size,
                                            int daysAgo, int hour) {
        return row(userId, userName, "yes", quality, size, NOW.toLocalDate().minusDays(daysAgo).atTime(hour, 0));
    }

    private static void set(BaseNode node, String port, Object value) {
        for (NodeVariable variable : node.getInputs()) {
            if (variable.name.equals(port)) {
                variable.setValue(value);
                return;
            }
        }
        throw new AssertionError("no input named " + port);
    }

    private static Object get(BaseNode node, String port) {
        for (NodeVariable variable : node.getOutputs()) {
            if (variable.name.equals(port)) {
                return variable.getValue();
            }
        }
        throw new AssertionError("no output named " + port);
    }

    private static <T extends BaseNode> T run(T node, List<?> rows, String period) {
        set(node, "Rows", rows);
        set(node, "Period", period);
        set(node, "Now", millis(NOW));
        node.process(ProcessContext.uncancelled());
        return node;
    }

    @Nested
    class Stats {

        /** Jay: today, yesterday, two days ago (a 3-day streak), then a gap, then a 4-day run last week. */
        private List<Map<String, Object>> jay() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(poop("1", "Jay", "good", "undisclosed", 0, 8));
            rows.add(poop("1", "Jay", "Good", "Large", 1, 8));
            rows.add(poop("1", "Jay", "bad", "undisclosed", 1, 20));
            rows.add(poop("1", "Jay", "unsure", "undisclosed", 2, 8));
            rows.add(row("1", "Jay", "no", "unavailable", "undisclosed", NOW.minusDays(3).withHour(20)));
            for (int daysAgo = 6; daysAgo <= 9; daysAgo++) {
                rows.add(poop("1", "Jay", "GOOD", "Large", daysAgo, 8));
            }
            return rows;
        }

        @Test
        void countsEverythingForAllTime() {
            PoopStatsNode node = run(new PoopStatsNode(), jay(), "");

            assertEquals(8, get(node, "Poops"));
            assertEquals(1, get(node, "Skipped"));
            assertEquals(6, get(node, "Good"), "good, Good and GOOD are the same answer");
            assertEquals(1, get(node, "Bad"));
            assertEquals(1, get(node, "Unsure"));
            assertEquals(75, get(node, "Good Percent"));
            assertEquals(7, get(node, "Poop Days"));
            assertEquals(0.8, get(node, "Per Day"), "8 poops over the 10 days from the first log to today");
            assertEquals(0, get(node, "Previous Poops"), "all time has no period before it");
        }

        @Test
        void streaksAndHabitsComeFromTheLog() {
            PoopStatsNode node = run(new PoopStatsNode(), jay(), "all");

            assertEquals(3, get(node, "Current Streak"));
            assertEquals(4, get(node, "Longest Streak"));
            assertEquals(0, get(node, "Days Since Last"));
            assertEquals("Large", get(node, "Top Size"), "undisclosed is never the top size");
            assertEquals(8, get(node, "Peak Hour"));
        }

        @Test
        void aWeekNarrowsTheCountsButNotTheStreaks() {
            PoopStatsNode node = run(new PoopStatsNode(), jay(), "week");

            // The last 7 days are today and the six before it: days 0-6.
            assertEquals(5, get(node, "Poops"));
            assertEquals(4, get(node, "Poop Days"));
            assertEquals(3, get(node, "Previous Poops"), "days 7-13 held the rest of last week's run");
            assertEquals(4, get(node, "Longest Streak"), "a streak is a fact about the whole log");
            assertTrue(((String) get(node, "Summary")).contains("up 2 on the period before"));
        }

        @Test
        void aStreakSurvivesUntilTheDayIsOver() {
            List<Map<String, Object>> rows = List.of(poop("1", "Jay", "good", "Normal", 1, 8),
                    poop("1", "Jay", "good", "Normal", 2, 8));
            PoopStatsNode node = run(new PoopStatsNode(), rows, "");

            assertEquals(2, get(node, "Current Streak"), "nothing yet today is not a broken streak");
            assertEquals(1, get(node, "Days Since Last"));
        }

        @Test
        void aMissedDayBreaksTheStreak() {
            List<Map<String, Object>> rows = List.of(poop("1", "Jay", "good", "Normal", 2, 8));
            PoopStatsNode node = run(new PoopStatsNode(), rows, "");

            assertEquals(0, get(node, "Current Streak"));
            assertEquals(1, get(node, "Longest Streak"));
            assertEquals(2, get(node, "Days Since Last"));
        }

        @Test
        void anEmptyLogSaysSoRatherThanFailing() {
            PoopStatsNode node = run(new PoopStatsNode(), List.of(), null);

            assertEquals(0, get(node, "Poops"));
            assertEquals(0, get(node, "Good Percent"));
            assertEquals(-1, get(node, "Days Since Last"));
            assertEquals(-1, get(node, "Peak Hour"));
            assertEquals("", get(node, "Top Size"));
            assertTrue(((String) get(node, "Summary")).startsWith("No poops on record"));
        }

        @Test
        void rowsWithoutATimestampAreSkipped() {
            Map<String, Object> undated = new LinkedHashMap<>(poop("1", "Jay", "good", "Normal", 0, 8));
            undated.remove("created_at");
            PoopStatsNode node = run(new PoopStatsNode(), List.of(undated), "");

            assertEquals(0, get(node, "Poops"));
        }

        @Test
        void aPeriodItDoesNotUnderstandIsReportedNotIgnored() {
            PoopStatsNode node = new PoopStatsNode();
            set(node, "Rows", List.of());
            set(node, "Period", "fortnight");

            assertThrows(IllegalArgumentException.class, () -> node.process(ProcessContext.uncancelled()));
        }
    }

    @Nested
    class Leaderboard {

        private List<Map<String, Object>> household() {
            List<Map<String, Object>> rows = new ArrayList<>();
            // Jay: 3 poops this week, 1 good, on a 3-day streak.
            rows.add(poop("1", "Jay", "good", "Large", 0, 8));
            rows.add(poop("1", "Jay", "bad", "Large", 1, 8));
            rows.add(poop("1", "Jay", "bad", "Large", 2, 8));
            // Jess: 2 poops this week, both good, streak broken; logged under an older name first.
            rows.add(poop("2", "jess", "good", "Normal", 4, 8));
            rows.add(poop("2", "Jess", "Good", "Normal", 3, 8));
            // Nik: only a "no" check-in, and an old poop outside the week.
            rows.add(row("3", "Nik", "no", "unavailable", "undisclosed", NOW.minusDays(1)));
            rows.add(poop("3", "Nik", "good", "Small", 30, 8));
            return rows;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> standings(PoopLeaderboardNode node) {
            return (List<Map<String, Object>>) get(node, "Standings");
        }

        private List<Object> names(PoopLeaderboardNode node) {
            return standings(node).stream().map(standing -> standing.get("userName")).toList();
        }

        @Test
        void ranksByPoopsByDefaultAndKeepsEveryoneOnTheBoard() {
            PoopLeaderboardNode node = run(new PoopLeaderboardNode(), household(), "week");

            assertEquals(List.of("Jay", "Jess", "Nik"), names(node));
            assertEquals("Jay", get(node, "Leader"));
            assertEquals(3, get(node, "People"));
            assertEquals(1, standings(node).getFirst().get("rank"));
            assertEquals(0, standings(node).get(2).get("poops"), "nothing this week is still a place on the board");
        }

        @Test
        void ranksByQuality() {
            PoopLeaderboardNode node = new PoopLeaderboardNode();
            set(node, "Rank By", "quality");
            run(node, household(), "week");

            assertEquals(List.of("Jess", "Jay", "Nik"), names(node));
            assertEquals(100, standings(node).getFirst().get("goodPercent"));
        }

        @Test
        void ranksByStreakOverTheWholeLog() {
            PoopLeaderboardNode node = new PoopLeaderboardNode();
            set(node, "Rank By", "streak");
            run(node, household(), "week");

            assertEquals("Jay", get(node, "Leader"));
            assertEquals(3, standings(node).getFirst().get("currentStreak"));
            assertEquals(1, standings(node).get(2).get("longestStreak"),
                    "Nik's poop a month ago is outside the week but still his best streak");
        }

        @Test
        void namesEachPersonByTheirLatestName() {
            PoopLeaderboardNode node = run(new PoopLeaderboardNode(), household(), "");

            assertTrue(names(node).contains("Jess"));
            assertTrue(((String) get(node, "Text")).contains("🥇 Jay"));
        }

        @Test
        void anEmptyLogSaysSo() {
            PoopLeaderboardNode node = run(new PoopLeaderboardNode(), List.of(), "");

            assertEquals("", get(node, "Leader"));
            assertEquals(0, get(node, "People"));
            assertTrue(((String) get(node, "Text")).startsWith("Nobody has logged a poop yet"));
        }

        @Test
        void aRaceItDoesNotKnowIsReported() {
            PoopLeaderboardNode node = new PoopLeaderboardNode();
            set(node, "Rows", List.of());
            set(node, "Rank By", "speed");

            assertThrows(IllegalArgumentException.class, () -> node.process(ProcessContext.uncancelled()));
        }
    }

    @Nested
    class Periods {

        @Test
        void namedPeriodsAndNumbersOfDays() {
            assertEquals(java.util.Optional.of(7), PoopLog.periodDays("Week"));
            assertEquals(java.util.Optional.of(30), PoopLog.periodDays("month"));
            assertEquals(java.util.Optional.of(365), PoopLog.periodDays(" year "));
            assertEquals(java.util.Optional.of(14), PoopLog.periodDays("14"));
            assertEquals(java.util.Optional.empty(), PoopLog.periodDays("all"));
            assertEquals(java.util.Optional.empty(), PoopLog.periodDays(null));
            assertThrows(IllegalArgumentException.class, () -> PoopLog.periodDays("0"));
        }

        @Test
        void longestStreakSpansMonthBoundaries() {
            java.util.TreeSet<LocalDate> days = new java.util.TreeSet<>(List.of(
                    LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)));
            assertEquals(3, PoopLog.longestStreak(days));
        }
    }
}

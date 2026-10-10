package io.github.jaymcole.housegraph.plugins.experimental.nodes.poop;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The poop table as these nodes read it, and the arithmetic every node here shares.
 * <p>
 * <b>A row is a map</b>, exactly as <b>Find Rows</b> or <b>SQL Query</b> hands it back, with the
 * columns the daily check-in and {@code /i-pooped} write: {@code userId}, {@code userName},
 * {@code didPoop} ({@code yes}/{@code no}), {@code quality}, {@code size} and {@code created_at}
 * (epoch milliseconds, filled in by <b>Insert Row</b>). Everything is matched case-insensitively,
 * because the check-in buttons store {@code good} and {@code /i-pooped} stores {@code Good} — they
 * are the same answer, and an insight that counted them separately would be wrong about both.
 * A row with no readable {@code created_at} is skipped: there is no day to put it on.
 * <p>
 * <b>Days are calendar dates in {@link ZoneId#systemDefault()}</b>, the convention
 * {@code housegraph-datetime} uses, so "today" here is the same today Occurred On Date means.
 */
final class PoopLog {

    /** The {@code size} the check-in buttons store when nobody said; never a "top size". */
    private static final String UNDISCLOSED = "undisclosed";

    private PoopLog() {
    }

    /** One row of the poop table, read leniently. */
    record Entry(String userId, String userName, boolean pooped, String quality, String size, Instant at) {

        LocalDate day(ZoneId zone) {
            return at.atZone(zone).toLocalDate();
        }
    }

    /**
     * Every readable row, oldest first. Unwired, null, or non-map entries read as nothing rather than
     * failing — an empty table is an ordinary state for a new household, not an error.
     */
    static List<Entry> read(List<?> rows) {
        List<Entry> entries = new ArrayList<>();
        if (rows == null) {
            return entries;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Instant at = millis(map.get("created_at"));
            if (at == null) {
                continue;
            }
            entries.add(new Entry(
                    text(map.get("userId")),
                    text(map.get("userName")),
                    "yes".equals(lower(map.get("didPoop"))),
                    lower(map.get("quality")),
                    lower(map.get("size")),
                    at));
        }
        entries.sort(Comparator.comparing(Entry::at));
        return entries;
    }

    /**
     * How many days back from {@code today} a period reaches, inclusive of today, or empty for all
     * time. Accepts {@code week}, {@code month}, {@code year}, {@code all}, or a whole number of days
     * — the first four being what a restricted slash-command option offers.
     *
     * @throws IllegalArgumentException for anything else, so a typo is reported rather than silently
     *                                  read as all time
     */
    static Optional<Integer> periodDays(String period) {
        String value = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "", "all", "all time", "ever" -> {
                return Optional.empty();
            }
            case "day", "today" -> {
                return Optional.of(1);
            }
            case "week" -> {
                return Optional.of(7);
            }
            case "month" -> {
                return Optional.of(30);
            }
            case "year" -> {
                return Optional.of(365);
            }
            default -> {
                try {
                    int days = Integer.parseInt(value);
                    if (days > 0) {
                        return Optional.of(days);
                    }
                } catch (NumberFormatException ignored) {
                    // Falls through to the message below, which says what would have worked.
                }
                throw new IllegalArgumentException("Period \"" + period + "\" isn't one I understand — use week, "
                        + "month, year, all, or a number of days.");
            }
        }
    }

    /** How a period reads in a sentence: "this past week", "in the last 10 days", "all time". */
    static String describe(Optional<Integer> days) {
        if (days.isEmpty()) {
            return "all time";
        }
        return switch (days.get()) {
            case 1 -> "today";
            case 7 -> "this past week";
            case 30 -> "this past month";
            case 365 -> "this past year";
            default -> "in the last " + days.get() + " days";
        };
    }

    /** The entries whose day falls in {@code [today - days + 1, today]}; all of them for all time. */
    static List<Entry> within(List<Entry> entries, Optional<Integer> days, LocalDate today, ZoneId zone) {
        if (days.isEmpty()) {
            return entries;
        }
        return between(entries, today.minusDays(days.get() - 1L), today, zone);
    }

    /** The entries whose day falls in {@code [first, last]}. */
    static List<Entry> between(List<Entry> entries, LocalDate first, LocalDate last, ZoneId zone) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) {
            LocalDate day = entry.day(zone);
            if (!day.isBefore(first) && !day.isAfter(last)) {
                kept.add(entry);
            }
        }
        return kept;
    }

    static List<Entry> poops(Collection<Entry> entries) {
        return entries.stream().filter(Entry::pooped).toList();
    }

    static int countQuality(Collection<Entry> poops, String quality) {
        return (int) poops.stream().filter(entry -> quality.equals(entry.quality())).count();
    }

    /** {@code part} as a whole-number percentage of {@code whole}; 0 when {@code whole} is 0. */
    static int percent(int part, int whole) {
        return whole == 0 ? 0 : Math.round(100f * part / whole);
    }

    /** The distinct days with at least one poop, ascending. */
    static TreeSet<LocalDate> poopDays(Collection<Entry> entries, ZoneId zone) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (Entry entry : entries) {
            if (entry.pooped()) {
                days.add(entry.day(zone));
            }
        }
        return days;
    }

    /**
     * Consecutive days with a poop, ending today — or ending yesterday when today has none <em>yet</em>,
     * so a streak isn't reported broken at breakfast just because the evening check-in hasn't happened.
     */
    static int currentStreak(TreeSet<LocalDate> days, LocalDate today) {
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    static int longestStreak(TreeSet<LocalDate> days) {
        int longest = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : days) {
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            longest = Math.max(longest, run);
            previous = day;
        }
        return longest;
    }

    /** Whole days since the most recent poop day, or -1 if there has never been one. */
    static int daysSinceLast(TreeSet<LocalDate> days, LocalDate today) {
        if (days.isEmpty()) {
            return -1;
        }
        return (int) Math.max(0, days.last().until(today, ChronoUnit.DAYS));
    }

    /** The most common disclosed size, in the casing it was first stored with; "" if none was given. */
    static String topSize(Collection<Entry> poops) {
        return mostCommon(poops.stream()
                .map(Entry::size)
                .filter(size -> !size.isEmpty() && !UNDISCLOSED.equals(size))
                .toList())
                .map(PoopLog::capitalise)
                .orElse("");
    }

    /** The weekday with the most poops, e.g. "Tuesday"; "" if there were none. */
    static String favoriteDay(Collection<Entry> poops, ZoneId zone) {
        return mostCommon(poops.stream().map(entry -> entry.at().atZone(zone).getDayOfWeek()).toList())
                .map(day -> day.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .orElse("");
    }

    /** The hour of day (0-23) with the most poops, or -1 if there were none. */
    static int peakHour(Collection<Entry> poops, ZoneId zone) {
        return mostCommon(poops.stream().map(entry -> entry.at().atZone(zone).getHour()).toList())
                .orElse(-1);
    }

    /** "8 PM", "12 AM" — the way a person says an hour. */
    static String hourLabel(int hour) {
        int twelve = hour % 12 == 0 ? 12 : hour % 12;
        return twelve + (hour < 12 ? " AM" : " PM");
    }

    /** The latest non-blank name each user went by, keyed by user id, in first-seen order. */
    static Map<String, String> names(List<Entry> entries) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String name = entry.userName().isEmpty() ? names.getOrDefault(entry.userId(), "") : entry.userName();
            names.put(entry.userId(), name);
        }
        return names;
    }

    /** The entries of each user, keyed by user id, in first-seen order. */
    static Map<String, List<Entry>> byUser(List<Entry> entries) {
        return entries.stream().collect(Collectors.groupingBy(Entry::userId, LinkedHashMap::new, Collectors.toList()));
    }

    /** The most frequent value; ties go to the one seen first, so the answer is stable. */
    private static <K> Optional<K> mostCommon(List<K> values) {
        Map<K, Integer> counts = new LinkedHashMap<>();
        for (K value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        K best = null;
        int bestCount = 0;
        for (Map.Entry<K, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** "1 poop", "3 poops". */
    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static Instant millis(Object value) {
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value instanceof String text) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(text.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String lower(Object value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    /** The current day in {@code zone} for a "now" input, defaulting to the real clock when unwired. */
    static ZonedDateTime now(Long nowMillis, ZoneId zone) {
        long value = nowMillis == null || nowMillis <= 0 ? System.currentTimeMillis() : nowMillis;
        return Instant.ofEpochMilli(value).atZone(zone);
    }
}

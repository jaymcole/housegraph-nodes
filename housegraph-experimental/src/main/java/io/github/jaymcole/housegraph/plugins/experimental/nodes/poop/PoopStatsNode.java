package io.github.jaymcole.housegraph.plugins.experimental.nodes.poop;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.experimental.nodes.poop.PoopLog.Entry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * One person's poop log, summarised: how many, how good, how regular. Wire the rows <b>Find Rows</b>
 * returns for {@code userId = Sender ID} into <b>Rows</b> and the <b>Summary</b> straight into a
 * <b>Discord Reply</b>; everything the summary says is also on its own output for a graph that
 * wants to say it differently.
 * <p>
 * <b>Period narrows the counts, never the streaks.</b> Poops, quality, size, favourite day and peak
 * hour are taken over the period — {@code week}, {@code month} and {@code year} are the last 7, 30
 * and 365 days including today, a number is that many days, and blank or {@code all} is
 * everything. A streak is a fact about the whole log: a 40-day streak doesn't become a 7-day one
 * because someone asked about this week. With a bounded period, <b>Previous Poops</b> counts the
 * period of the same length just before it, so "up 3 on last week" is one subtraction away.
 * <p>
 * <b>A streak counts calendar days with at least one poop</b> and is still alive if the last one
 * was yesterday — the check-in runs in the evening, and a streak shouldn't read as broken all day
 * until it does. Check-ins answered "No" count toward <b>Skipped</b> and break nothing on their own;
 * it's the missing poop that breaks the streak.
 * <p>
 * <b>Now</b> is optional and defaults to the real clock. It exists so a graph — or a test — can ask
 * "as of when?" without waiting for that day to come round.
 * <p>
 * Rows are read exactly as {@link PoopLog} describes: case-insensitively, so the check-in's
 * {@code good} and {@code /i-pooped}'s {@code Good} are one answer.
 */
@Display.Name("Poop Stats")
@Display.Description("Summarises one person's poop log: counts, quality, streaks, habits.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"poop", "stats", "statistics", "streak", "insights", "summary", "daily", "habit", "bathroom"})
@Node.Type("experimental.PoopStatsNode")
public class PoopStatsNode extends BaseNode {

    private final NodeVariable<List<?>> rows = new NodeVariable<>("Rows", PoopRows.TYPE).required()
            .describedAs("Rows from the poop table — usually Find Rows filtered on userId. A list of maps "
                    + "with didPoop, quality, size and created_at.");
    private final NodeVariable<String> period = new NodeVariable<>("Period", String.class, true)
            .describedAs("week, month, year, all, or a number of days. Blank means all time. Narrows the "
                    + "counts; streaks always use the whole log.");
    private final NodeVariable<Long> now = new NodeVariable<>("Now", Long.class)
            .describedAs("Epoch milliseconds to treat as now. Unwired means the real clock.");

    private final NodeVariable<Integer> poops = new NodeVariable<>("Poops", Integer.class)
            .describedAs("Rows with didPoop = yes in the period.");
    private final NodeVariable<Integer> skipped = new NodeVariable<>("Skipped", Integer.class)
            .describedAs("Check-ins answered No in the period.");
    private final NodeVariable<Integer> good = new NodeVariable<>("Good", Integer.class);
    private final NodeVariable<Integer> bad = new NodeVariable<>("Bad", Integer.class);
    private final NodeVariable<Integer> unsure = new NodeVariable<>("Unsure", Integer.class);
    private final NodeVariable<Integer> goodPercent = new NodeVariable<>("Good Percent", Integer.class)
            .describedAs("Good poops as a whole-number percentage of all poops in the period; 0 with none.");
    private final NodeVariable<Integer> poopDays = new NodeVariable<>("Poop Days", Integer.class)
            .describedAs("Distinct days in the period with at least one poop.");
    private final NodeVariable<Double> perDay = new NodeVariable<>("Per Day", Double.class)
            .describedAs("Poops per day across the period. For all time, from the first logged day to today.");
    private final NodeVariable<Integer> previousPoops = new NodeVariable<>("Previous Poops", Integer.class)
            .describedAs("Poops in the equally long period just before this one. 0 for all time.");
    private final NodeVariable<Integer> currentStreak = new NodeVariable<>("Current Streak", Integer.class)
            .describedAs("Consecutive days with a poop, ending today or yesterday. Whole log, not the period.");
    private final NodeVariable<Integer> longestStreak = new NodeVariable<>("Longest Streak", Integer.class)
            .describedAs("The longest run of consecutive poop days ever. Whole log, not the period.");
    private final NodeVariable<Integer> daysSinceLast = new NodeVariable<>("Days Since Last", Integer.class)
            .describedAs("Whole days since the last poop day; 0 if today, -1 if never.");
    private final NodeVariable<String> topSize = new NodeVariable<>("Top Size", String.class)
            .describedAs("The most common size given in the period, ignoring undisclosed. Blank if none.");
    private final NodeVariable<String> favoriteDay = new NodeVariable<>("Favorite Day", String.class)
            .describedAs("The weekday with the most poops in the period, e.g. Tuesday. Blank if none.");
    private final NodeVariable<Integer> peakHour = new NodeVariable<>("Peak Hour", Integer.class)
            .describedAs("The hour of day (0-23) with the most poops in the period; -1 if none.");
    private final NodeVariable<String> summary = new NodeVariable<>("Summary", String.class)
            .describedAs("All of the above as a short message, ready for a Discord Reply.");

    @Override
    public void process(ProcessContext ctx) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = PoopLog.now(now.getValue(), zone).toLocalDate();
        Optional<Integer> days = PoopLog.periodDays(period.getValue());

        List<Entry> all = PoopLog.read(rows.getValue());
        List<Entry> inPeriod = PoopLog.within(all, days, today, zone);
        List<Entry> poopsInPeriod = PoopLog.poops(inPeriod);
        TreeSet<LocalDate> allPoopDays = PoopLog.poopDays(all, zone);

        int poopCount = poopsInPeriod.size();
        int goodCount = PoopLog.countQuality(poopsInPeriod, "good");
        int previous = days.map(length -> PoopLog.poops(PoopLog.between(all,
                today.minusDays(2L * length - 1), today.minusDays(length), zone)).size()).orElse(0);
        int current = PoopLog.currentStreak(allPoopDays, today);
        int longest = PoopLog.longestStreak(allPoopDays);
        int sinceLast = PoopLog.daysSinceLast(allPoopDays, today);
        int hour = PoopLog.peakHour(poopsInPeriod, zone);

        poops.setValue(poopCount);
        skipped.setValue(inPeriod.size() - poopCount);
        good.setValue(goodCount);
        bad.setValue(PoopLog.countQuality(poopsInPeriod, "bad"));
        unsure.setValue(PoopLog.countQuality(poopsInPeriod, "unsure"));
        goodPercent.setValue(PoopLog.percent(goodCount, poopCount));
        poopDays.setValue(PoopLog.poopDays(inPeriod, zone).size());
        perDay.setValue(perDay(poopCount, days, all, today, zone));
        previousPoops.setValue(previous);
        currentStreak.setValue(current);
        longestStreak.setValue(longest);
        daysSinceLast.setValue(sinceLast);
        topSize.setValue(PoopLog.topSize(poopsInPeriod));
        favoriteDay.setValue(PoopLog.favoriteDay(poopsInPeriod, zone));
        peakHour.setValue(hour);
        summary.setValue(summarise(days, previous));
    }

    /** Rounded to two places — "1.43 a day" is an insight, "1.4285714 a day" is noise. */
    private static double perDay(int poopCount, Optional<Integer> days, List<Entry> all, LocalDate today, ZoneId zone) {
        long span = days.map(Integer::longValue).orElseGet(() ->
                all.isEmpty() ? 0 : all.getFirst().day(zone).until(today, ChronoUnit.DAYS) + 1);
        return span <= 0 ? 0 : Math.round(100.0 * poopCount / span) / 100.0;
    }

    private String summarise(Optional<Integer> days, int previous) {
        String when = PoopLog.describe(days);
        int poopCount = poops.getValue();
        if (poopCount == 0 && longestStreak.getValue() == 0) {
            return "No poops on record yet. Log one with /i-pooped.";
        }

        List<String> lines = new ArrayList<>();
        lines.add("💩 **Poop stats — " + when + "**");
        if (poopCount == 0) {
            lines.add("No poops " + when + ".");
        } else {
            String trend = days.isEmpty() ? "" : " (" + trend(poopCount - previous) + ")";
            lines.add(PoopLog.plural(poopCount, "poop") + " over " + PoopLog.plural(poopDays.getValue(), "day")
                    + ", " + perDay.getValue() + " a day" + trend + ".");
            lines.add("Quality: " + good.getValue() + " good, " + bad.getValue() + " bad, " + unsure.getValue()
                    + " unsure — " + goodPercent.getValue() + "% good.");
            List<String> habits = new ArrayList<>();
            if (!topSize.getValue().isEmpty()) {
                habits.add("usually " + topSize.getValue());
            }
            habits.add("mostly on " + favoriteDay.getValue() + "s");
            habits.add("peak hour around " + PoopLog.hourLabel(peakHour.getValue()));
            lines.add("Habits: " + String.join(", ", habits) + ".");
        }
        if (skipped.getValue() > 0) {
            lines.add("Skipped days: " + skipped.getValue() + ".");
        }
        lines.add("🔥 Current streak: " + PoopLog.plural(currentStreak.getValue(), "day")
                + " (best " + PoopLog.plural(longestStreak.getValue(), "day") + ").");
        int since = daysSinceLast.getValue();
        if (since > 1) {
            lines.add("Last poop was " + since + " days ago.");
        }
        return String.join("\n", lines);
    }

    /** "up 3 on the period before", "same as the period before". */
    private static String trend(int change) {
        if (change == 0) {
            return "same as the period before";
        }
        return (change > 0 ? "up " : "down ") + Math.abs(change) + " on the period before";
    }

    @Override
    public void configureInputs() {
        addInput(rows);
        addInput(period);
        addInput(now);
    }

    @Override
    public void configureOutputs() {
        addOutput(poops);
        addOutput(skipped);
        addOutput(good);
        addOutput(bad);
        addOutput(unsure);
        addOutput(goodPercent);
        addOutput(poopDays);
        addOutput(perDay);
        addOutput(previousPoops);
        addOutput(currentStreak);
        addOutput(longestStreak);
        addOutput(daysSinceLast);
        addOutput(topSize);
        addOutput(favoriteDay);
        addOutput(peakHour);
        addOutput(summary);
    }
}

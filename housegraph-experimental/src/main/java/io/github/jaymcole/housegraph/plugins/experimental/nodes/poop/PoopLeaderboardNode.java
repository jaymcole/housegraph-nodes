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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The whole household's poop log, ranked. Wire every row of the poop table (<b>Find Rows</b> with
 * no conditions) into <b>Rows</b> and the <b>Text</b> into a <b>Discord Reply</b>.
 * <p>
 * <b>Rank By</b> picks the race: {@code poops} (most in the period, the default), {@code good}
 * (most good ones in the period), {@code quality} (highest share of good ones in the period), or
 * {@code streak} (longest current streak). Ties are broken by poops, then by name, so the order is
 * the same every time it's asked. <b>Period</b> means what it means on <b>Poop Stats</b> — and, the
 * same way, never narrows a streak.
 * <p>
 * Everyone who appears anywhere in the rows is ranked, including someone with nothing in the
 * period: being on the board with a zero is the point of a leaderboard. A person is named by the
 * latest {@code userName} they logged under; Text uses names rather than {@code <@id>} mentions so
 * asking for the board doesn't ping the whole house.
 * <p>
 * <b>Standings</b> is a list of maps — {@code rank}, {@code userId}, {@code userName},
 * {@code poops}, {@code good}, {@code goodPercent}, {@code currentStreak}, {@code longestStreak} —
 * for a graph that wants its own format; <b>Format Each</b> and <b>Join List</b> take it from there.
 */
@Display.Name("Poop Leaderboard")
@Display.Description("Ranks everyone in a poop log by poops, good poops, quality or streak.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"poop", "leaderboard", "ranking", "standings", "streak", "competition", "household", "stats"})
@Node.Type("experimental.PoopLeaderboardNode")
public class PoopLeaderboardNode extends BaseNode {

    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    private final NodeVariable<List<?>> rows = new NodeVariable<>("Rows", PoopRows.TYPE).required()
            .describedAs("Every row of the poop table — usually Find Rows with no conditions.");
    private final NodeVariable<String> period = new NodeVariable<>("Period", String.class, true)
            .describedAs("week, month, year, all, or a number of days. Blank means all time. Streaks always "
                    + "use the whole log.");
    private final NodeVariable<String> rankBy = new NodeVariable<>("Rank By", String.class, true)
            .describedAs("poops, good, quality or streak. Blank means poops.");
    private final NodeVariable<Long> now = new NodeVariable<>("Now", Long.class)
            .describedAs("Epoch milliseconds to treat as now. Unwired means the real clock.");

    private final NodeVariable<List<?>> standings = new NodeVariable<>("Standings", PoopRows.TYPE)
            .describedAs("One map per person, best first: rank, userId, userName, poops, good, goodPercent, "
                    + "currentStreak, longestStreak.");
    private final NodeVariable<String> leader = new NodeVariable<>("Leader", String.class)
            .describedAs("The name in first place; blank when nobody has logged anything.");
    private final NodeVariable<Integer> people = new NodeVariable<>("People", Integer.class);
    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class)
            .describedAs("The board as a short message, ready for a Discord Reply.");

    /** One person's line on the board, before it's ranked. */
    private record Standing(String userId, String userName, int poops, int good, int goodPercent,
                            int currentStreak, int longestStreak) {
    }

    private enum Race {
        POOPS("poops", "most poops"),
        GOOD("good", "most good poops"),
        QUALITY("quality", "best quality"),
        STREAK("streak", "longest current streak");

        final String key;
        final String title;

        Race(String key, String title) {
            this.key = key;
            this.title = title;
        }

        int score(Standing standing) {
            return switch (this) {
                case POOPS -> standing.poops();
                case GOOD -> standing.good();
                case QUALITY -> standing.goodPercent();
                case STREAK -> standing.currentStreak();
            };
        }

        String scoreText(Standing standing) {
            return switch (this) {
                case POOPS -> PoopLog.plural(standing.poops(), "poop") + " (" + standing.goodPercent() + "% good)";
                case GOOD -> standing.good() + " good of " + standing.poops();
                case QUALITY -> standing.goodPercent() + "% good of " + standing.poops();
                case STREAK -> PoopLog.plural(standing.currentStreak(), "day")
                        + " (best " + standing.longestStreak() + ")";
            };
        }

        static Race parse(String value) {
            String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                return POOPS;
            }
            for (Race race : values()) {
                if (race.key.equals(key)) {
                    return race;
                }
            }
            throw new IllegalArgumentException("Rank By \"" + value + "\" isn't one I understand — use poops, "
                    + "good, quality or streak.");
        }
    }

    @Override
    public void process(ProcessContext ctx) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = PoopLog.now(now.getValue(), zone).toLocalDate();
        Optional<Integer> days = PoopLog.periodDays(period.getValue());
        Race race = Race.parse(rankBy.getValue());

        List<Entry> all = PoopLog.read(rows.getValue());
        Map<String, String> names = PoopLog.names(all);

        List<Standing> board = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> user : PoopLog.byUser(all).entrySet()) {
            List<Entry> poops = PoopLog.poops(PoopLog.within(user.getValue(), days, today, zone));
            TreeSet<LocalDate> poopDays = PoopLog.poopDays(user.getValue(), zone);
            int good = PoopLog.countQuality(poops, "good");
            String name = names.get(user.getKey());
            board.add(new Standing(user.getKey(), name.isEmpty() ? user.getKey() : name, poops.size(), good,
                    PoopLog.percent(good, poops.size()), PoopLog.currentStreak(poopDays, today),
                    PoopLog.longestStreak(poopDays)));
        }
        board.sort(Comparator.comparingInt(race::score).reversed()
                .thenComparing(Comparator.comparingInt(Standing::poops).reversed())
                .thenComparing(Standing::userName, String.CASE_INSENSITIVE_ORDER));

        standings.setValue(toMaps(board));
        leader.setValue(board.isEmpty() ? "" : board.getFirst().userName());
        people.setValue(board.size());
        text.setValue(format(board, race, days));
    }

    private static List<Map<String, Object>> toMaps(List<Standing> board) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            Standing standing = board.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", i + 1);
            map.put("userId", standing.userId());
            map.put("userName", standing.userName());
            map.put("poops", standing.poops());
            map.put("good", standing.good());
            map.put("goodPercent", standing.goodPercent());
            map.put("currentStreak", standing.currentStreak());
            map.put("longestStreak", standing.longestStreak());
            maps.add(Collections.unmodifiableMap(map));
        }
        return List.copyOf(maps);
    }

    private static String format(List<Standing> board, Race race, Optional<Integer> days) {
        if (board.isEmpty()) {
            return "Nobody has logged a poop yet. Be the first with /i-pooped.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("🏆 **Poop leaderboard — " + race.title + ", " + PoopLog.describe(days) + "**");
        for (int i = 0; i < board.size(); i++) {
            String place = i < MEDALS.length ? MEDALS[i] : (i + 1) + ".";
            lines.add(place + " " + board.get(i).userName() + " — " + race.scoreText(board.get(i)));
        }
        return String.join("\n", lines);
    }

    @Override
    public void configureInputs() {
        addInput(rows);
        addInput(period);
        addInput(rankBy);
        addInput(now);
    }

    @Override
    public void configureOutputs() {
        addOutput(standings);
        addOutput(leader);
        addOutput(people);
        addOutput(text);
    }
}

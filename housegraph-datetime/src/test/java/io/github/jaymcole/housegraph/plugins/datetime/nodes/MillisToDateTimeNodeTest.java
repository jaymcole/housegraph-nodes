package io.github.jaymcole.housegraph.plugins.datetime.nodes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises {@link MillisToDateTimeNode} against the system's own time zone, not a fixed one. */
class MillisToDateTimeNodeTest {

    @Test
    void declaresOneInputAndSixOutputs() {
        MillisToDateTimeNode node = new MillisToDateTimeNode();

        assertEquals(List.of("Milliseconds"), Ports.inputNames(node));
        assertEquals(List.of("Year", "Month", "Day", "Hour", "Minute", "Second"), Ports.outputNames(node));
    }

    @Test
    void breaksATimestampIntoItsCalendarFields() {
        // Built in the system's own zone, and asserted against the same object's fields, so the
        // test holds regardless of which time zone it runs in.
        ZonedDateTime moment = ZonedDateTime.of(2026, 3, 5, 14, 37, 22, 0, ZoneId.systemDefault());
        MillisToDateTimeNode node = at(moment.toInstant().toEpochMilli());

        Ports.run(node);

        assertEquals(moment.getYear(), Ports.intOf(node, "Year"));
        assertEquals(moment.getMonthValue(), Ports.intOf(node, "Month"));
        assertEquals(moment.getDayOfMonth(), Ports.intOf(node, "Day"));
        assertEquals(moment.getHour(), Ports.intOf(node, "Hour"));
        assertEquals(moment.getMinute(), Ports.intOf(node, "Minute"));
        assertEquals(moment.getSecond(), Ports.intOf(node, "Second"));
    }

    @Test
    void unwiredMillisecondsReadsAsEpochZeroRatherThanFailing() {
        ZonedDateTime epoch = Instant.EPOCH.atZone(ZoneId.systemDefault());
        MillisToDateTimeNode node = new MillisToDateTimeNode();

        Ports.run(node);

        assertEquals(epoch.getYear(), Ports.intOf(node, "Year"));
        assertEquals(epoch.getMonthValue(), Ports.intOf(node, "Month"));
        assertEquals(epoch.getDayOfMonth(), Ports.intOf(node, "Day"));
    }

    private static MillisToDateTimeNode at(long millis) {
        MillisToDateTimeNode node = new MillisToDateTimeNode();
        Ports.set(node, "Milliseconds", millis);
        return node;
    }
}

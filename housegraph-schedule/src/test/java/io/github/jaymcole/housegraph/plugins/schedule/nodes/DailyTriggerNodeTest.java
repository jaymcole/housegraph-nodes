package io.github.jaymcole.housegraph.plugins.schedule.nodes;

import io.github.jaymcole.housegraph.graph.FlowPort;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the daily trigger's declared ports and its persistence of the selected days, time of
 * day, and whether it was armed - so it can auto-start on load (see {@code AutoStartable}). The
 * timer itself is a JavaFX {@code Timeline} built by {@code createNodeContent()}, and the actual
 * "when" math lives in the JavaFX-free {@code WeeklySchedule} (see {@code WeeklyScheduleTest}), so
 * this stays on the headless contract - ports and persistence - rather than driving the UI. The
 * Start/Stop cascade-suppression behaviour ({@code activateNone()}) needs a live {@code NodeGraph}
 * to observe, so it isn't exercised here either - only the port shapes are.
 */
class DailyTriggerNodeTest {

    @Test
    void exposesNamedStartAndStopFlowInputsAndOneUnnamedFlowOutput() {
        DailyTriggerNode node = new DailyTriggerNode();

        List<FlowPort> flowInputs = node.getFlowInputs();
        assertEquals(2, flowInputs.size(), "Start and Stop, and nothing else");
        assertEquals("Start", flowInputs.get(0).name);
        assertEquals("Stop", flowInputs.get(1).name);

        assertEquals(1, node.getFlowOutputs().size());
        assertEquals("", node.getFlowOutputs().get(0).name);
    }

    @Test
    void remainsAnExecutionEntryPointDespiteNowHavingFlowInputs() {
        assertTrue(new DailyTriggerNode().isExecutionEntryPoint(),
                "the buttons and the armed timer still self-trigger it directly, regardless of Start/Stop wiring");
    }

    @Test
    void defaultsToNoDaysSelectedAndEightAm() {
        DailyTriggerNode node = new DailyTriggerNode();

        assertTrue(node.selectedDays().isEmpty(), "nothing fires until the user picks a day");
        assertEquals(LocalTime.of(8, 0), node.timeOfDay());
    }

    @Test
    void aDisarmedTriggerWritesNoRunningFlag() {
        assertFalse(new DailyTriggerNode().saveState().containsKey("running"),
                "a trigger that isn't armed must not persist a running flag");
    }

    @Test
    void savedStateRoundTripsDaysAndTime() {
        DailyTriggerNode node = new DailyTriggerNode();
        node.selectedDays().add(DayOfWeek.MONDAY);
        node.selectedDays().add(DayOfWeek.FRIDAY);

        Map<String, String> state = node.saveState();
        assertEquals("MONDAY,FRIDAY", state.get("days"));
        assertEquals("08:00", state.get("time"));

        DailyTriggerNode reloaded = new DailyTriggerNode();
        reloaded.loadState(state);

        assertEquals(daysOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), reloaded.selectedDays());
        assertEquals(LocalTime.of(8, 0), reloaded.timeOfDay());
    }

    @Test
    void loadStateParsesACustomTime() {
        DailyTriggerNode node = new DailyTriggerNode();

        node.loadState(Map.of("days", "SUNDAY", "time", "17:45"));

        assertEquals(LocalTime.of(17, 45), node.timeOfDay());
        assertEquals(daysOf(DayOfWeek.SUNDAY), node.selectedDays());
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoStart() {
        DailyTriggerNode node = new DailyTriggerNode();
        assertFalse(node.wasRunning(), "a fresh node has no pending auto-start");

        node.loadState(Map.of("running", "true", "days", "MONDAY", "time", "08:00"));

        assertTrue(node.wasRunning(), "a graph saved while the schedule was armed reloads with auto-start pending");
    }

    private static Set<DayOfWeek> daysOf(DayOfWeek... days) {
        return EnumSet.copyOf(List.of(days));
    }
}

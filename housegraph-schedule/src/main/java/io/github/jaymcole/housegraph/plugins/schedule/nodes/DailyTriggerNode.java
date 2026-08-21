package io.github.jaymcole.housegraph.plugins.schedule.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.schedule.WeeklySchedule;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entry-point node: fires flow on a calendar schedule instead of a plain interval. Start arms it
 * against whichever of Mon-Sun are toggled on and the chosen time of day, firing once at that
 * time on every selected day; Stop disarms it. Purely a flow source, control-only per this
 * repository's node design rule — no data ports, no external action of its own.
 * <p>
 * Start/Stop are inline buttons only, no flow-in counterparts — nothing else can arm or disarm
 * this schedule mid-graph, which keeps it self-contained and, structurally, an
 * {@link #isExecutionEntryPoint() execution entry point} by the default rule (a flow-out with no
 * flow-in). {@code process()} does no work of its own; the armed timer calls {@link #execute()}
 * directly at each scheduled moment, and the engine's default (no {@link #activate} call) fires
 * the single flow-out every time.
 * <p>
 * The actual "when" is computed by {@link WeeklySchedule}, kept free of JavaFX so it can be tested
 * against fixed instants; this class only owns the day/time selection, the one-second-tick
 * {@link Timeline} that watches for the computed moment, and the inline UI.
 * <p>
 * If the schedule was armed when the graph was saved, it resumes automatically on load: the
 * running flag rides along in {@link #saveState()} and {@link #autoStartIfWasRunning()} presses
 * Start for the user once the graph is fully loaded (see {@link AutoStartable}).
 */
@Display.Name("Daily Trigger")
@Node.Type("schedule.DailyTriggerNode")
public class DailyTriggerNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final DayOfWeek[] DAY_ORDER = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
    };
    private static final String[] DAY_LABELS = {"M", "T", "W", "TH", "F", "SA", "SU"};
    private static final DateTimeFormatter NEXT_FIRE_FORMAT = DateTimeFormatter.ofPattern("EEE HH:mm");

    /** Days this schedule fires on. Mutated directly by the toggle buttons; empty until the user picks at least one. */
    private final Set<DayOfWeek> selectedDays = EnumSet.noneOf(DayOfWeek.class);
    private LocalTime timeOfDay = LocalTime.of(8, 0);
    private final ZoneId zone = ZoneId.systemDefault();

    private Timeline timeline;
    /** The moment the armed timer is waiting for; null when disarmed. */
    private ZonedDateTime nextFire;
    private ToggleButton[] dayButtons;
    private Spinner<Integer> hourSpinner;
    private Spinner<Integer> minuteSpinner;
    private Button startButton;
    private Button stopButton;
    private Label statusLabel;
    /** True when the schedule was armed at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        if (timeline != null) {
            state.put("running", "true");
        }
        if (!selectedDays.isEmpty()) {
            state.put("days", selectedDays.stream().map(Enum::name).collect(Collectors.joining(",")));
        }
        state.put("time", String.format("%02d:%02d", timeOfDay.getHour(), timeOfDay.getMinute()));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String days = state.get("days");
        if (days != null && !days.isBlank()) {
            selectedDays.clear();
            for (String name : days.split(",")) {
                selectedDays.add(DayOfWeek.valueOf(name.trim()));
            }
        }
        String time = state.get("time");
        if (time != null && !time.isBlank()) {
            timeOfDay = LocalTime.parse(time.trim());
        }
        wasRunning = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasRunning) {
            start();
        }
    }

    /** Test seam: whether the loaded graph had this schedule armed, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
    }

    /** Test seam: the currently selected days, independent of the UI. */
    Set<DayOfWeek> selectedDays() {
        return selectedDays;
    }

    /** Test seam: the currently selected time of day, independent of the UI. */
    LocalTime timeOfDay() {
        return timeOfDay;
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(new FlowPort("", FlowPort.Direction.OUT));
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        HBox dayRow = new HBox(2);
        dayButtons = new ToggleButton[DAY_ORDER.length];
        for (int i = 0; i < DAY_ORDER.length; i++) {
            DayOfWeek day = DAY_ORDER[i];
            ToggleButton button = new ToggleButton(DAY_LABELS[i]);
            button.setSelected(selectedDays.contains(day));
            button.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4;");
            button.setOnAction(event -> {
                if (button.isSelected()) {
                    selectedDays.add(day);
                } else {
                    selectedDays.remove(day);
                }
            });
            dayButtons[i] = button;
            dayRow.getChildren().add(button);
        }

        hourSpinner = new Spinner<>(0, 23, timeOfDay.getHour());
        hourSpinner.setEditable(true);
        hourSpinner.setPrefWidth(55);
        hourSpinner.valueProperty().addListener((obs, oldValue, newValue) -> timeOfDay = timeOfDay.withHour(newValue));

        minuteSpinner = new Spinner<>(0, 59, timeOfDay.getMinute());
        minuteSpinner.setEditable(true);
        minuteSpinner.setPrefWidth(55);
        minuteSpinner.valueProperty().addListener((obs, oldValue, newValue) -> timeOfDay = timeOfDay.withMinute(newValue));

        HBox timeRow = new HBox(4, hourSpinner, new Label(":"), minuteSpinner);
        timeRow.setAlignment(Pos.CENTER_LEFT);

        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(event -> start());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stop());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        HBox buttons = new HBox(6, startButton, stopButton);
        return new VBox(4, dayRow, timeRow, buttons, statusLabel);
    }

    private void start() {
        armTimer();
    }

    private void armTimer() {
        if (timeline != null) {
            return;
        }
        if (selectedDays.isEmpty()) {
            statusLabel.setText("Select at least one day first");
            return;
        }

        scheduleNextFire();

        // One-second ticks watching for the computed target, rather than a single KeyFrame sized
        // to the gap - the gap can be up to a week, and re-arming after every fire this way needs
        // no special-casing for "the next one is also today" vs. "next week".
        timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        setControlsDisabled(true);
        startButton.setDisable(true);
        stopButton.setDisable(false);
    }

    private void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        nextFire = null;
        setControlsDisabled(false);
        startButton.setDisable(false);
        stopButton.setDisable(true);
        statusLabel.setText("Stopped");
    }

    private void tick() {
        if (nextFire == null || ZonedDateTime.now(zone).isBefore(nextFire)) {
            return;
        }
        execute();
        scheduleNextFire();
    }

    private void scheduleNextFire() {
        nextFire = WeeklySchedule.nextFireAfter(ZonedDateTime.now(zone), selectedDays, timeOfDay);
        if (statusLabel != null) {
            statusLabel.setText("Next: " + NEXT_FIRE_FORMAT.format(nextFire));
        }
    }

    private void setControlsDisabled(boolean disabled) {
        for (ToggleButton button : dayButtons) {
            button.setDisable(disabled);
        }
        hourSpinner.setDisable(disabled);
        minuteSpinner.setDisable(disabled);
    }

    /**
     * Stops the timer when the node is removed from the graph (deleted, replaced by a load, or
     * app shutdown) so it can't keep firing as a zombie. Only the timer is touched - not the
     * controls/label - since the node's UI is going away and, in a headless context, may never
     * have been built.
     */
    @Override
    protected void onRemoved() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }
}

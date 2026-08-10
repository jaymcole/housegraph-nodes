package io.github.jaymcole.housegraph.plugins.github.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.github.GitRepoSync;
import io.github.jaymcole.housegraph.sdk.AutoStartable;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a local folder in sync with a git repository: Start clones it (if the folder is empty)
 * and then, every <b>Interval</b> seconds, checks the remote for new commits and pulls them in
 * if the folder is behind (see {@link GitRepoSync} — a hard reset onto the remote's tracking
 * branch, not a merge, since this is meant for an unattended checkout something else runs off
 * of). The flow-out fires only on a tick that actually changed the folder's contents, with the
 * commit it now points at set on the <b>Commit</b> output — so a build/restart/notify step can be
 * chained after it without re-running on every no-op poll.
 * <p>
 * Modeled on {@code TriggerRepeatingNode} from the host app: liveness is user-driven
 * (Start/Stop) via its own timer rather than tied to graph flow, and — like the Discord bot and
 * web server resource nodes — if it was running when the graph was saved, it resumes
 * automatically on load (see {@link AutoStartable}).
 */
@Display.Name("Git Sync")
@Node.Type("github.GitSyncNode")
public class GitSyncNode extends BaseNode implements NodeContentProvider, AutoStartable {

    private static final Logger log = Log.get(GitSyncNode.class);

    private final NodeVariable<String> repositoryUrl = new NodeVariable<>("Repository URL", String.class, true).required();
    private final NodeVariable<String> localPath = new NodeVariable<>("Local Path", String.class, true).required();
    private final NodeVariable<Integer> intervalSeconds = new NodeVariable<>("Interval (s)", Integer.class, true).required();

    private final NodeVariable<String> commitId = new NodeVariable<>("Commit", String.class);

    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Timeline timeline;
    /** Guards against a second tick starting while a slow clone/fetch is still running. */
    private final AtomicBoolean syncInFlight = new AtomicBoolean(false);
    private int intervalValue;
    private int remainingSeconds;
    /** True when the timer was running at the moment the loaded graph was saved; drives {@link #autoStartIfWasRunning()}. */
    private boolean wasRunning;

    private Button startButton;
    private Button stopButton;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
        // Outputs are set from the sync result just before execute(); nothing to compute here.
    }

    @Override
    public void configureInputs() {
        addInput(repositoryUrl);
        addInput(localPath);
        addInput(intervalSeconds);
    }

    @Override
    public void configureOutputs() {
        addOutput(commitId);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        if (timeline != null) {
            state.put("running", "true");
        }
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        wasRunning = Boolean.parseBoolean(state.get("running"));
    }

    @Override
    public void autoStartIfWasRunning() {
        if (wasRunning) {
            start();
        }
    }

    /** Test seam: whether the loaded graph had this sync running, i.e. auto-start is pending. */
    boolean wasRunning() {
        return wasRunning;
    }

    /**
     * Stops the timer when the node is removed from the graph (deleted, replaced by a load, or
     * app shutdown) so it can't keep polling as a zombie.
     */
    @Override
    protected void onRemoved() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        startButton = new Button("Start");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(event -> start());

        stopButton = new Button("Stop");
        stopButton.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stop());

        statusLabel = new Label("Stopped");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(200);

        HBox buttons = new HBox(6, startButton, stopButton);
        return new VBox(4, buttons, statusLabel);
    }

    private void start() {
        if (timeline != null) {
            return;
        }
        // Pull Repository URL/Local Path/Interval through their data edges (if any) before
        // reading them - a connected value only lands in these variables once the graph
        // actually resolves this node, which nothing has done yet at this point.
        beginProcessing();
        String url = repositoryUrl.getValue();
        String path = localPath.getValue();
        Integer seconds = intervalSeconds.getValue();
        if (url == null || url.isBlank()) {
            statusLabel.setText("Enter a repository URL first");
            return;
        }
        if (path == null || path.isBlank()) {
            statusLabel.setText("Enter a local folder path first");
            return;
        }
        if (seconds == null || seconds <= 0) {
            statusLabel.setText("Enter a positive interval first");
            return;
        }

        intervalValue = seconds;
        remainingSeconds = seconds;
        updateCountdownLabel();

        // One-second ticks driving a countdown, rather than a single seconds-long KeyFrame, so
        // the remaining time can be shown and updated live.
        timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> countdownTick(url, path)));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        startButton.setDisable(true);
        stopButton.setDisable(false);
    }

    private void stop() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        startButton.setDisable(false);
        stopButton.setDisable(true);
        statusLabel.setText("Stopped");
    }

    private void countdownTick(String url, String path) {
        remainingSeconds--;
        if (remainingSeconds <= 0) {
            remainingSeconds = intervalValue;
            runSync(url, path);
        }
        updateCountdownLabel();
    }

    private void updateCountdownLabel() {
        statusLabel.setText("Next check in " + remainingSeconds + "s");
    }

    /**
     * Runs one sync pass off the FX thread — cloning/fetching can block on the network — firing
     * this node's flow-out only when it actually changed the folder's contents.
     */
    private void runSync(String url, String path) {
        if (!syncInFlight.compareAndSet(false, true)) {
            return; // previous check is still running (slow network) - skip this tick rather than overlap
        }
        Thread thread = new Thread(() -> {
            try {
                GitRepoSync.Result result = GitRepoSync.sync(url, Path.of(path));
                if (result.changed()) {
                    fireChanged(result.commitId());
                }
                Platform.runLater(() -> onSyncFinished(result, null));
            } catch (Exception ex) {
                log.error("Git sync failed for {}", url, ex);
                Platform.runLater(() -> onSyncFinished(null, ex));
            } finally {
                syncInFlight.set(false);
            }
        }, "git-sync-" + getName());
        thread.setDaemon(true);
        thread.start();
    }

    private void fireChanged(String newCommitId) {
        try {
            execute(() -> commitId.setValue(newCommitId));
        } catch (IllegalStateException e) {
            // The node was removed just as the sync finished; nothing to fire into.
        }
    }

    private void onSyncFinished(GitRepoSync.Result result, Exception error) {
        if (statusLabel == null || timeline == null) {
            return; // stopped (or never had a UI built) while the sync was in flight
        }
        if (error != null) {
            statusLabel.setText("Sync failed - " + error.getMessage());
        } else if (result.changed()) {
            statusLabel.setText("Pulled " + shortSha(result.commitId()));
        }
        // else: leave the "Next check in Ns" countdown label as-is - nothing changed.
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }
}

package io.github.jaymcole.housegraph.plugins.github.nodes;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the git sync node persists whether its timer was running so it can auto-start on load
 * (see {@code AutoStartable}). Stays on the headless persistence contract - like the host app's
 * {@code TriggerRepeatingNodeTest} - since actually starting spins up a JavaFX Timeline and talks
 * to a git remote.
 */
class GitSyncNodeTest {

    @Test
    void aStoppedSyncWritesNoRunningFlag() {
        assertFalse(new GitSyncNode().saveState().containsKey("running"),
                "a sync whose timer isn't running must not persist a running flag");
    }

    @Test
    void aRunningFlagInSavedStateSchedulesAutoStart() {
        GitSyncNode node = new GitSyncNode();
        assertFalse(node.wasRunning(), "a fresh node has no pending auto-start");

        node.loadState(Map.of("running", "true"));

        assertTrue(node.wasRunning(), "a graph saved while the timer ran reloads with auto-start pending");
    }
}

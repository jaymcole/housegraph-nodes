package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import javafx.application.Platform;
import javafx.scene.control.Label;

/**
 * The little grey line under a node, and the two rules about writing to it.
 * <p>
 * <b>It has to tolerate there being no UI.</b> {@code createNodeContent()} runs only when something
 * draws the node, so the label is null in a headless run and in a graph used from inside another
 * graph — and a node whose status update assumed otherwise throws out of its own {@code process()}.
 * <p>
 * <b>And it has to tolerate not being on the FX thread.</b> {@code onExecuted()} arrives on it, but
 * the buttons in this library start work on background threads that report back when they finish.
 * One helper for both beats a dozen copies of the same four lines. (The API this library pins has
 * no {@code BaseNode.present(Runnable)}; when it is rebuilt against one that does, this is the
 * class that goes.)
 * <p>
 * Public rather than package-private because this library's nodes live in subpackages of this one —
 * {@code .market}, {@code .portfolio}, {@code .orders} — so that they land in submenus of the Add
 * Node menu. It is a helper for those nodes, not API for anybody else.
 */
public final class Status {

    private Status() {
    }

    /** A status label in the style the rest of HouseGraph's nodes use. */
    public static Label label(String initialText) {
        Label label = new Label(initialText);
        label.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        label.setWrapText(true);
        return label;
    }

    /**
     * Sets the text, from whichever thread, and does nothing at all if the node was never drawn.
     *
     * @param label the node's status label, possibly null
     * @param text  what to show
     */
    public static void set(Label label, String text) {
        if (label == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            label.setText(text);
        } else {
            Platform.runLater(() -> label.setText(text));
        }
    }
}

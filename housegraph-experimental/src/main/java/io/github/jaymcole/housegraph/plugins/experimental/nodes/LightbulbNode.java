package io.github.jaymcole.housegraph.plugins.experimental.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * No data ports, and no branching: control reaches the flow-in and moves straight to the
 * flow-out, every time — the point of the node is entirely the bulb it renders (see
 * {@link NodeContentProvider}). Each execution snaps the bulb to full brightness and lets it fade
 * back to dim over {@link #FADE_DURATION}, so a graph's flow becomes something you watch happen
 * on the canvas instead of something you infer from logs.
 */
@Display.Name("Lightbulb")
@Node.Type("experimental.LightbulbNode")
public class LightbulbNode extends BaseNode implements NodeContentProvider {

    private static final Color LIT = Color.web("#fff59d");
    private static final Color UNLIT = Color.web("#4a4632");
    private static final Duration FADE_DURATION = Duration.seconds(1.2);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private Timeline fade;

    @Override
    public void process(ProcessContext ctx) {
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        Circle bulb = new Circle(16, UNLIT);
        bulb.setStroke(Color.web("#8a8666"));
        bulb.setStrokeWidth(1.5);

        DropShadow glow = new DropShadow(0, LIT);
        bulb.setEffect(glow);

        Rectangle base = new Rectangle(14, 10, Color.web("#8a8666"));
        base.setArcWidth(4);
        base.setArcHeight(4);

        VBox bulbGraphic = new VBox(-2, bulb, base);
        bulbGraphic.setAlignment(Pos.CENTER);

        // Snaps to lit at t=0 and eases back down to unlit — playFromStart() on every execution
        // restarts it from that snap, so a run mid-cascade re-illuminates instead of blending.
        fade = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(bulb.fillProperty(), LIT, Interpolator.EASE_OUT),
                        new KeyValue(glow.radiusProperty(), 20, Interpolator.EASE_OUT)),
                new KeyFrame(FADE_DURATION,
                        new KeyValue(bulb.fillProperty(), UNLIT, Interpolator.EASE_OUT),
                        new KeyValue(glow.radiusProperty(), 0, Interpolator.EASE_OUT)));

        return bulbGraphic;
    }

    @Override
    protected void onExecuted() {
        if (fade != null) {
            fade.stop();
            fade.playFromStart();
        }
    }
}

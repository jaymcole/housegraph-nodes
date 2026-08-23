package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Template;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.TextArea;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a message from a template, with one input port per placeholder. Type
 * <i>"New commit {commit} on {repo}"</i> into the field and the node grows a <b>commit</b> port
 * and a <b>repo</b> port to wire values into.
 * <p>
 * <b>This is the node most graphs here were missing.</b> Composing a sentence out of two facts
 * otherwise means a chain of concatenations, one node per fragment, with the sentence itself
 * scattered across all of them and unreadable on the canvas. Here the sentence is the node: you
 * read the message as written, and the wires say where its facts come from.
 * <p>
 * <b>The slots are typed {@link Object} on purpose.</b> The host's hidden conversion matrix
 * deliberately does not convert anything to {@code String}, so a String-typed slot would refuse a
 * number or a boolean and force a converter node in front of every one of them. An
 * {@code Object} slot accepts any output at all and stringifies it here. A slot nobody wired
 * renders as empty, so a half-built template still produces a readable message.
 * <p>
 * <b>The template is node state, not an input port</b>, because the ports are derived from it —
 * they have to be known while the graph is being edited, not just while it runs. If the message
 * itself needs to be chosen at runtime, feed a template through <b>Replace Text</b> instead. Ports
 * rebuild when the field loses focus rather than on every keystroke, which is what stops a wire
 * being torn out halfway through typing a placeholder's name.
 * <p>
 * See {@link Template} for the grammar: repeating a name substitutes it in both places for one
 * port, doubled braces are literal ones, and anything that is not a well-formed placeholder is
 * left as typed.
 */
@Display.Name("Format Text")
@Display.Description("Builds a message from a template, with one input per placeholder.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"format", "template", "message", "compose", "interpolate", "placeholder", "concatenate", "build", "text", "string"})
@Node.Type("string.FormatTextNode")
public class FormatTextNode extends BaseNode implements NodeContentProvider {

    /** The key this node's template is saved under. */
    private static final String TEMPLATE_KEY = "template";

    private String template = "";

    /** One entry per placeholder, in template order — rebuilt by {@link #configureInputs()}. */
    private final Map<String, NodeVariable<Object>> slots = new LinkedHashMap<>();

    private final NodeVariable<String> result = new NodeVariable<>("Text", String.class);

    @Override
    public void process(ProcessContext ctx) {
        Map<String, Object> values = new LinkedHashMap<>();
        slots.forEach((name, slot) -> values.put(name, slot.getValue()));
        result.setValue(Template.of(template).render(values));
    }

    @Override
    public void configureInputs() {
        // Derived, not declared: the host builds ports lazily on the first getInputs(), and calls
        // loadState() before that on a graph load, so the saved template is already in place here.
        slots.clear();
        for (String name : Template.of(template).names()) {
            NodeVariable<Object> slot = new NodeVariable<>(name, Object.class, false);
            slots.put(name, slot);
            addInput(slot);
        }
    }

    @Override
    public void configureOutputs() {
        addOutput(result);
    }

    @Override
    public Map<String, String> saveState() {
        return Map.of(TEMPLATE_KEY, template);
    }

    @Override
    public void loadState(Map<String, String> state) {
        template = state == null ? "" : state.getOrDefault(TEMPLATE_KEY, "");
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        TextArea field = new TextArea(template);
        field.setPromptText("Hello {name}, you have {count} messages");
        field.setPrefRowCount(3);
        field.setPrefColumnCount(24);
        field.setWrapText(true);
        // On focus lost, not on every keystroke: rebuilding ports mid-word would delete the port
        // being typed - and every edge already wired to it - between one character and the next.
        field.focusedProperty().addListener((property, hadFocus, hasFocus) -> {
            if (!hasFocus) {
                applyTemplate(field.getText());
            }
        });
        return field;
    }

    /**
     * Adopts an edited template and rebuilds the ports it names. A no-op when the text is
     * unchanged, so merely clicking through the field costs no edges: {@code rebuildPorts()}
     * recreates every port, and the host reconnects edges to the survivors by name.
     *
     * @param edited the template as the field now reads, possibly null
     */
    private void applyTemplate(String edited) {
        String updated = edited == null ? "" : edited;
        if (updated.equals(template)) {
            return;
        }
        template = updated;
        rebuildPorts();
    }
}

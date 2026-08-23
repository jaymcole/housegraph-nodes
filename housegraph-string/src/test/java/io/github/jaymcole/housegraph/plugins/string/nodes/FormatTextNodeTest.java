package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link FormatTextNode} headlessly. The template is node state rather than a port, so
 * these tests set it the way a graph load does — {@code loadState()} before anything touches the
 * ports, which is the order {@code GraphFileIO} uses and the reason the derived inputs come back
 * after a reload. The inline text area is never built, so no JavaFX toolkit is needed.
 * <p>
 * The grammar itself is covered by {@code TemplateTest}; what matters here is that the ports
 * follow it.
 */
class FormatTextNodeTest {

    @Test
    void itGrowsOneInputPerPlaceholderNamedAsTheTemplateSpellsIt() {
        FormatTextNode node = templated("New commit {commit} on {repo}");

        assertEquals(List.of("commit", "repo"), Ports.inputNames(node));
        assertEquals(List.of("Text"), Ports.outputNames(node));
    }

    @Test
    void aTemplateWithNoPlaceholdersHasNoInputsAndStillRendersItsText() {
        FormatTextNode node = templated("all quiet");

        assertTrue(node.getInputs().isEmpty());
        Ports.run(node);
        assertEquals("all quiet", Ports.get(node, "Text"));
    }

    @Test
    void itRendersTheTemplateFromWhateverIsWiredIntoTheSlots() {
        FormatTextNode node = templated("New commit {commit} on {repo}");
        Ports.set(node, "commit", "a1b2c3d");
        Ports.set(node, "repo", "housegraph-nodes");

        Ports.run(node);

        assertEquals("New commit a1b2c3d on housegraph-nodes", Ports.get(node, "Text"));
    }

    @Test
    void aSlotAcceptsAValueThatIsNotTextBecauseTheSlotsAreTypedObject() {
        FormatTextNode node = templated("{count} alerts, urgent: {urgent}");
        Ports.set(node, "count", 3);
        Ports.set(node, "urgent", true);

        Ports.run(node);

        assertEquals("3 alerts, urgent: true", Ports.get(node, "Text"));
        assertEquals(Object.class, node.getInputs().get(0).type,
                "an Object slot is what lets a number be wired straight in - the host converts nothing to String");
    }

    @Test
    void anUnwiredSlotRendersEmptySoAHalfBuiltTemplateStillReads() {
        FormatTextNode node = templated("New commit {commit} on {repo}");
        Ports.set(node, "commit", "a1b2c3d");

        Ports.run(node);

        assertEquals("New commit a1b2c3d on ", Ports.get(node, "Text"));
    }

    @Test
    void theTemplateSurvivesASaveAndReload() {
        FormatTextNode saved = templated("hi {name}");

        FormatTextNode reloaded = new FormatTextNode();
        reloaded.loadState(saved.saveState());

        assertEquals(List.of("name"), Ports.inputNames(reloaded));
    }

    @Test
    void aNodeThatWasNeverGivenATemplateHasNoInputsAndRendersEmpty() {
        FormatTextNode node = new FormatTextNode();

        assertTrue(node.getInputs().isEmpty());
        Ports.run(node);
        assertEquals("", Ports.get(node, "Text"));
    }

    /** Builds a node with a template in place, the way a graph load does: state first, ports after. */
    private static FormatTextNode templated(String template) {
        FormatTextNode node = new FormatTextNode();
        node.loadState(Map.of("template", template));
        return node;
    }
}

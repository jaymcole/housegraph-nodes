package io.github.jaymcole.housegraph.plugins.string.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link ChangeCaseNode}'s ports and its handling of an authored case. */
class ChangeCaseNodeTest {

    @Test
    void declaresTextAndCaseInputsAResultOutputAndNoFlowPorts() {
        ChangeCaseNode node = new ChangeCaseNode();

        assertEquals(List.of("Text", "Case"), Ports.inputNames(node));
        assertEquals(List.of("Result"), Ports.outputNames(node));
        assertTrue(node.getFlowInputs().isEmpty(), "a pure text transform has nothing to trigger it");
        assertTrue(node.getFlowOutputs().isEmpty(), "a pure text transform has no outcome to report");
    }

    @Test
    void itArrivesWithACaseAlreadyFilledInSoTheFieldShowsTheVocabulary() {
        ChangeCaseNode node = new ChangeCaseNode();
        Ports.set(node, "Text", "front door");

        Ports.run(node);

        assertEquals("FRONT DOOR", Ports.get(node, "Result"));
    }

    @Test
    void itAppliesTheAuthoredCase() {
        ChangeCaseNode node = new ChangeCaseNode();
        Ports.set(node, "Text", "front DOOR camera");
        Ports.set(node, "Case", "title");

        Ports.run(node);

        assertEquals("Front Door Camera", Ports.get(node, "Result"));
    }

    @Test
    void anUnrecognisedCaseFailsTheNodeRatherThanQuietlyPickingOne() {
        ChangeCaseNode node = new ChangeCaseNode();
        Ports.set(node, "Text", "front door");
        Ports.set(node, "Case", "shouty");

        assertThrows(IllegalArgumentException.class, () -> Ports.run(node));
    }
}

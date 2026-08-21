package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code DiscordSendButtonsNode} is a plain pull-based action node, same shape as
 * {@code DiscordSendMessageNode} (see its test for why sending itself isn't exercised here —
 * it needs a live JDA connection).
 */
class DiscordSendButtonsNodeTest {

    @Test
    void declaresBotMessageChannelAndFiveButtonSlotsInOrder() {
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        assertEquals(List.of("Bot", "Message", "Channel",
                        "Button 1 Label", "Button 1 Id",
                        "Button 2 Label", "Button 2 Id",
                        "Button 3 Label", "Button 3 Id",
                        "Button 4 Label", "Button 4 Id",
                        "Button 5 Label", "Button 5 Id"),
                node.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void processingWithNothingWiredIsAQuietNoOp() {
        DiscordSendButtonsNode node = new DiscordSendButtonsNode();

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()),
                "with no bot wired there is nothing to send to");
    }
}

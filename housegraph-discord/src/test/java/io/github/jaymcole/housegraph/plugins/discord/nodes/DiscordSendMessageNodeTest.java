package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code DiscordSendMessageNode} is a plain pull-based action node — its {@code Bot} input
 * resolves through the normal input-edge machinery on every {@code process()} call, same as
 * {@code Message}/{@code Channel}, deliberately (see the class doc for why an eager
 * {@code onInputEdgeAdded} capture, as {@code DiscordSendButtonsNode} needs for its click
 * subscription, is the wrong tool here). Actually sending isn't exercised here since
 * {@code DiscordBot#sendMessage} needs a live JDA connection.
 */
class DiscordSendMessageNodeTest {

    @Test
    void declaresBotMessageAndChannelInputsInOrder() {
        DiscordSendMessageNode node = new DiscordSendMessageNode();

        assertEquals(List.of("Bot", "Message", "Channel"),
                node.getInputs().stream().map(v -> v.name).toList());
    }

    @Test
    void processingWithNothingWiredIsAQuietNoOp() {
        DiscordSendMessageNode node = new DiscordSendMessageNode();

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()),
                "with no bot wired there is nothing to send to");
    }
}

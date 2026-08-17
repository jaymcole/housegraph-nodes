package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.web.WebHookReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the answering half of the hold-and-reply pair: it calls the wired {@link WebHookReply}
 * with its configured status/content-type/body, defaulting the ones left unset.
 */
class WebHookReplyNodeTest {

    @SuppressWarnings("unchecked")
    private static NodeVariable<WebHookReply> replyInput(WebHookReplyNode node) {
        return node.getInputs().stream()
                .filter(input -> input.name.equals("Reply"))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> NodeVariable<T> input(WebHookReplyNode node, String name) {
        return node.getInputs().stream()
                .filter(candidate -> candidate.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void defaultsToStatusTwoHundredAndJsonContentType() {
        WebHookReplyNode node = new WebHookReplyNode();
        int[] repliedStatus = new int[1];
        String[] repliedType = new String[1];
        String[] repliedBody = new String[1];
        replyInput(node).setValue((status, contentType, body) -> {
            repliedStatus[0] = status;
            repliedType[0] = contentType;
            repliedBody[0] = body;
        });
        WebHookReplyNodeTest.<String>input(node, "Body").setValue("hello");

        node.process(ProcessContext.uncancelled());

        assertEquals(200, repliedStatus[0]);
        assertEquals("application/json; charset=utf-8", repliedType[0]);
        assertEquals("hello", repliedBody[0]);
    }

    @Test
    void repliesWithConfiguredStatusAndContentType() {
        WebHookReplyNode node = new WebHookReplyNode();
        int[] repliedStatus = new int[1];
        String[] repliedType = new String[1];
        replyInput(node).setValue((status, contentType, body) -> {
            repliedStatus[0] = status;
            repliedType[0] = contentType;
        });
        WebHookReplyNodeTest.<Integer>input(node, "Status").setValue(404);
        WebHookReplyNodeTest.<String>input(node, "Content-Type").setValue("text/plain; charset=utf-8");

        node.process(ProcessContext.uncancelled());

        assertEquals(404, repliedStatus[0]);
        assertEquals("text/plain; charset=utf-8", repliedType[0]);
    }

    @Test
    void aNullBodyRepliesWithAnEmptyStringRatherThanNull() {
        WebHookReplyNode node = new WebHookReplyNode();
        String[] repliedBody = {"unset"};
        replyInput(node).setValue((status, contentType, body) -> repliedBody[0] = body);

        node.process(ProcessContext.uncancelled());

        assertEquals("", repliedBody[0]);
    }

    @Test
    void doesNothingWhenNoReplyHandleIsWired() {
        WebHookReplyNode node = new WebHookReplyNode();

        assertDoesNotThrow(() -> node.process(ProcessContext.uncancelled()));
    }

    @Test
    void statusAndContentTypeInputsDefaultToSensibleValues() {
        WebHookReplyNode node = new WebHookReplyNode();

        assertEquals(200, WebHookReplyNodeTest.<Integer>input(node, "Status").getValue());
        assertEquals("application/json; charset=utf-8",
                WebHookReplyNodeTest.<String>input(node, "Content-Type").getValue());
        assertNull(WebHookReplyNodeTest.<String>input(node, "Body").getValue());
    }
}

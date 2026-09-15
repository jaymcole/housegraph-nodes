package io.github.jaymcole.housegraph.plugins.alpaca.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.market.GetBarsNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.market.GetQuoteNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.market.MarketClockNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders.CancelOrderNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders.GetRecentOrdersNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders.OrderStatusNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders.PlaceOrderNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio.AccountSummaryNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio.ClosePositionNode;
import io.github.jaymcole.housegraph.plugins.alpaca.nodes.portfolio.GetPositionsNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checklist at the bottom of {@code docs/shared/node-library-rules.md}, as a test.
 * <p>
 * Every rule it covers has a <b>silent</b> failure mode — a node that never appears in the Add Node
 * menu, a node findable only by someone who already knows its name, a saved graph that cannot find
 * its nodes after a class is renamed. None of them shows up in a build, in a review, or in any other
 * test here, which is exactly why they are worth pinning down mechanically.
 */
class NodeTaggingTest {

    /** Every node class in this library. A new node has to be added here, which is the point. */
    static List<Class<? extends BaseNode>> nodeClasses() {
        return List.of(
                AlpacaAccountNode.class,
                AlpacaAccountRefNode.class,
                GetQuoteNode.class,
                GetBarsNode.class,
                MarketClockNode.class,
                AccountSummaryNode.class,
                GetPositionsNode.class,
                ClosePositionNode.class,
                PlaceOrderNode.class,
                OrderStatusNode.class,
                CancelOrderNode.class,
                GetRecentOrdersNode.class);
    }

    @ParameterizedTest
    @MethodSource("nodeClasses")
    void isNamedAndDescribed(Class<? extends BaseNode> type) {
        assertNotNull(type.getAnnotation(Display.Name.class), type + " has no @Display.Name");
        Display.Description description = type.getAnnotation(Display.Description.class);
        assertNotNull(description, type + " has no @Display.Description");
        assertTrue(description.value().endsWith("."), type + "'s description should be a sentence");
    }

    @ParameterizedTest
    @MethodSource("nodeClasses")
    void carriesATypeIdPrefixedWithTheLibrary(Class<? extends BaseNode> type) {
        // Without it the save-file id is the simple class name, so renaming the class strands every
        // saved graph - and an unprefixed id is one collision away from resolving to another
        // library's node.
        Node.Type id = type.getAnnotation(Node.Type.class);
        assertNotNull(id, type + " has no @Node.Type");
        assertTrue(id.value().startsWith("alpaca."), type + " has the unprefixed id " + id.value());
        assertEquals("alpaca." + type.getSimpleName(), id.value());
    }

    @Test
    void noTwoNodesShareATypeId() {
        Set<String> ids = new HashSet<>();
        for (Class<? extends BaseNode> type : nodeClasses()) {
            assertTrue(ids.add(type.getAnnotation(Node.Type.class).value()),
                    type + " reuses another node's id");
        }
    }

    @Test
    void aNodeInASubpackageIsStillFoundByOneScanRoot() {
        // The build declares one nodePackage and lets HouseGraph scan it recursively, which is what
        // turns .market / .portfolio / .orders into submenus. Naming them individually would make
        // each its own root and collapse the menu back to flat - so this pins the arrangement the
        // build.gradle comment describes.
        for (Class<? extends BaseNode> type : nodeClasses()) {
            assertTrue(type.getPackageName()
                            .startsWith("io.github.jaymcole.housegraph.plugins.alpaca.nodes"),
                    type + " is outside the scanned package root");
        }
    }

    @ParameterizedTest
    @MethodSource("nodeClasses")
    void carriesAKindSoKindSearchesFindIt(Class<? extends BaseNode> type) {
        // A node with no @Node.Kind matches no kind: search at all; nothing is inferred from the
        // category path.
        assertNotNull(type.getAnnotation(Node.Kind.class), type + " has no @Node.Kind");
    }

    @ParameterizedTest
    @MethodSource("nodeClasses")
    void carriesKeywordsIncludingTheOneEverybodyWouldSearchFor(Class<? extends BaseNode> type) {
        Node.Keywords keywords = type.getAnnotation(Node.Keywords.class);
        assertNotNull(keywords, type + " has no @Node.Keywords");
        assertTrue(keywords.value().length >= 5,
                type + " has only " + keywords.value().length + " keywords");
        assertTrue(List.of(keywords.value()).contains("alpaca"),
                type + " does not answer to a search for \"alpaca\"");
    }
}

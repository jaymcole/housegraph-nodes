package io.github.jaymcole.housegraph.plugins.string.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.string.Patterns;
import io.github.jaymcole.housegraph.plugins.string.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Matches text against a regular expression, reporting whether it matched, what matched, and
 * whatever its capture groups caught.
 * <p>
 * <b>This is the node that pulls fields out of text nothing else can parse</b> — a temperature out
 * of a sensor line, a ticket number out of a commit subject, an argument out of a chat command.
 * Wire <b>Matched</b> into an If Bool to run a branch only when the text was the shape you
 * expected, and read the pieces off <b>Groups</b>.
 * <p>
 * <b>It searches rather than matching the whole string.</b> A pattern of {@code \d+} matches "at
 * 21 degrees" — anchor it with {@code ^} and {@code $} if you meant the entire text and nothing
 * else. Matching is case-sensitive; put {@code (?i)} at the front of the pattern to make it
 * insensitive, which keeps that decision visible in the pattern instead of hidden in a field
 * somewhere else on the node.
 * <p>
 * <b>Groups</b> holds the capture groups of the first match, in order, group 1 first — the whole
 * match is on <b>Match</b> and is not repeated there. A group that took part in no match (an
 * optional one that did not fire) reads as empty text rather than null, so a For Each over Groups
 * never hands its body a null. When nothing matched at all, Match is empty and Groups is empty.
 * <p>
 * A pattern that will not compile <b>fails the node</b> rather than silently never matching; see
 * {@link Patterns}.
 */
@Display.Name("Regex Match")
@Display.Description("Matches text against a regular expression and captures its groups.")
@Node.Kind(NodeKind.DATA)
@Node.Keywords({"regex", "regexp", "pattern", "match", "capture", "group", "extract", "parse", "text", "string"})
@Node.Type("string.RegexMatchNode")
public class RegexMatchNode extends BaseNode {

    private final NodeVariable<String> text = new NodeVariable<>("Text", String.class, true).required();
    private final NodeVariable<String> pattern = new NodeVariable<>("Pattern", String.class, true).required();

    private final NodeVariable<Boolean> matched = new NodeVariable<>("Matched", Boolean.class);
    private final NodeVariable<String> match = new NodeVariable<>("Match", String.class);
    private final NodeVariable<List<?>> groups = new NodeVariable<>("Groups", Texts.LIST_TYPE);

    @Override
    public void process(ProcessContext ctx) {
        Matcher matcher = Patterns.compile(pattern.getValue()).matcher(Texts.orEmpty(text.getValue()));
        if (!matcher.find()) {
            matched.setValue(false);
            match.setValue("");
            groups.setValue(List.of());
            return;
        }

        List<Object> captured = new ArrayList<>();
        for (int group = 1; group <= matcher.groupCount(); group++) {
            // An optional group that did not participate returns null; empty text keeps every
            // downstream node - a For Each, a Join - free of null handling.
            captured.add(Texts.orEmpty(matcher.group(group)));
        }

        matched.setValue(true);
        match.setValue(matcher.group());
        groups.setValue(List.copyOf(captured));
    }

    @Override
    public void configureInputs() {
        addInput(text);
        addInput(pattern);
    }

    @Override
    public void configureOutputs() {
        addOutput(matched);
        addOutput(match);
        addOutput(groups);
    }
}

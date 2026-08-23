package io.github.jaymcole.housegraph.plugins.string;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code {placeholder}} grammar behind Format Text, parsed once into literal and placeholder
 * segments so the node can do two different things with the same parse: name its input ports from
 * {@link #names()} while the graph is being edited, and {@link #render} a message from those
 * ports' values while it runs.
 * <p>
 * <b>The grammar.</b> A name in braces &#8212; <code>&#123;user&#125;</code> &#8212; is a
 * placeholder, and the name is trimmed, so a slot typed with spaces around it is the same slot.
 * Repeating a name substitutes the same value in every position and still costs one port. A
 * doubled brace, <code>&#123;&#123;</code> or <code>&#125;&#125;</code>, is a literal one, which
 * is what makes JSON and CSS templatable here at all. Anything that is not a well-formed
 * placeholder is literal text: an unclosed brace, an empty pair of them, and a lone closing brace
 * all render exactly as typed rather than failing the node. A template is authored by hand and
 * previewed on the canvas, so being forgiving about a half-typed brace beats erroring while it is
 * still being typed.
 * <p>
 * <b>Port order is first-appearance order</b>, and duplicates collapse. That matters beyond
 * tidiness: the host reconnects edges to surviving ports by name and position when a node rebuilds
 * its ports, so editing the tail of a template must not renumber the slots already wired at its
 * head.
 */
public final class Template {

    /** One piece of a parsed template: literal text, or a placeholder naming an input port. */
    private record Segment(String text, boolean placeholder) {
    }

    private final List<Segment> segments;
    private final List<String> names;

    private Template(List<Segment> segments, List<String> names) {
        this.segments = segments;
        this.names = names;
    }

    /**
     * Parses a template. Never throws and never returns null — an unparseable brace is literal
     * text (see the class header), and a null or empty template yields one with no placeholders
     * that renders as {@code ""}.
     *
     * @param text the authored template, possibly null
     * @return the parsed template, never null
     */
    public static Template of(String text) {
        List<Segment> segments = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String source = Texts.orEmpty(text);
        StringBuilder literal = new StringBuilder();

        int i = 0;
        while (i < source.length()) {
            char current = source.charAt(i);
            if (current == '{' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                literal.append('{');
                i += 2;
            } else if (current == '}' && i + 1 < source.length() && source.charAt(i + 1) == '}') {
                literal.append('}');
                i += 2;
            } else if (current == '{') {
                int close = source.indexOf('}', i + 1);
                String name = close < 0 ? "" : source.substring(i + 1, close).trim();
                if (name.isEmpty()) {
                    // An unclosed or empty brace is literal text, not an error - see the header.
                    literal.append(current);
                    i++;
                } else {
                    if (literal.length() > 0) {
                        segments.add(new Segment(literal.toString(), false));
                        literal.setLength(0);
                    }
                    segments.add(new Segment(name, true));
                    seen.add(name);
                    i = close + 1;
                }
            } else {
                literal.append(current);
                i++;
            }
        }
        if (literal.length() > 0) {
            segments.add(new Segment(literal.toString(), false));
        }
        return new Template(List.copyOf(segments), List.copyOf(seen));
    }

    /**
     * The distinct placeholder names, in the order they first appear — one per input port the
     * node exposes. See the class header for why the order is load-bearing.
     *
     * @return the placeholder names, never null, possibly empty
     */
    public List<String> names() {
        return names;
    }

    /**
     * Renders the template, substituting each placeholder with its value from {@code values}. A
     * name with no entry — or a null one — substitutes {@code ""}, so a template whose slot is
     * unwired renders the rest of the message rather than the word {@code "null"} or nothing at
     * all.
     *
     * @param values the value for each placeholder name, keyed as {@link #names()} spells them
     * @return the rendered text, never null
     */
    public String render(Map<String, ?> values) {
        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.placeholder()) {
                result.append(Texts.text(values == null ? null : values.get(segment.text())));
            } else {
                result.append(segment.text());
            }
        }
        return result.toString();
    }
}

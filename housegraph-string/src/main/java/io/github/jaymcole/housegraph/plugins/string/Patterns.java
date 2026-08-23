package io.github.jaymcole.housegraph.plugins.string;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How every regex node in this library compiles its Pattern input.
 * <p>
 * <b>A malformed pattern fails the node</b> rather than quietly matching nothing. It is a
 * configuration mistake, not surprising data: a pattern that will never compile will never compile
 * on any input, so the honest report is a failed node on the canvas the first time it runs, with
 * the offending pattern and the regex engine's own description of what is wrong with it. Silently
 * returning "no match" would leave a graph looking healthy while its branch never fires.
 * <p>
 * Patterns are compiled per invocation rather than cached. The Pattern input can be wired from
 * upstream, so it is not a constant, and {@link Pattern#compile} on a short pattern is far cheaper
 * than the cache-invalidation bug that pretending otherwise would eventually cost.
 */
public final class Patterns {

    private Patterns() {
    }

    /**
     * Compiles an authored regular expression.
     *
     * @param pattern the authored pattern, possibly null or blank
     * @return the compiled pattern, never null
     * @throws IllegalArgumentException if the pattern is absent or will not compile
     */
    public static Pattern compile(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("A regular expression is required, but the Pattern input is empty");
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "\"" + pattern + "\" is not a valid regular expression: " + e.getDescription(), e);
        }
    }
}

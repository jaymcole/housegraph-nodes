package io.github.jaymcole.housegraph.plugins.discord;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts message text down to what Discord will actually accept: a message body may not exceed
 * {@value #MAX_LENGTH} characters, and anything longer is rejected outright.
 * <p>
 * <b>Why this is a split rather than a truncate.</b> The text a graph sends is usually something
 * another node produced — an LLM answer, a file listing, a database dump — and the length of it
 * is not something the person wiring the graph chose or can see. Before this, the whole run
 * failed on a long answer ({@code IllegalArgumentException: Content may not be longer than 2000
 * characters!}) and nothing at all reached Discord; truncating instead would have delivered a
 * sentence that stops mid-word with no sign that the rest existed. Splitting is the only one of
 * the three that keeps what the graph produced. A person who wants a shorter message can still
 * shorten it upstream; nobody can un-truncate a message that was already sent.
 * <p>
 * <b>Where it cuts.</b> Greedily at the last boundary that fits, preferring the one that reads
 * best: a blank line, else a line break, else a space. Only text with no break at all in
 * {@value #MAX_LENGTH} characters — a base64 blob, a single enormous word — is cut mid-word, and
 * then never between the two halves of a surrogate pair, which would send half of an emoji.
 * Whitespace at the seam is dropped, so a continuation doesn't open with a blank line.
 * <p>
 * Markdown that spans the seam (an unclosed code fence, say) will render as two separate blocks.
 * That is accepted: closing and reopening spans would mean parsing Markdown here, and a split is
 * only reached by text long enough that its shape was never going to survive one message anyway.
 */
public final class DiscordMessages {

    /**
     * Discord's ceiling on one message body. Counted the way the limit is enforced — in
     * {@code String} characters, which is also what JDA checks before it will send.
     */
    public static final int MAX_LENGTH = 2000;

    private DiscordMessages() {
    }

    /**
     * Splits {@code text} into message bodies Discord will accept, in the order they should be
     * sent.
     *
     * @param text the message text; null is treated as empty
     * @return one element for text that already fits (including empty text, so a caller sending
     *         attachments with no words still has a message to send), otherwise the pieces it was
     *         broken into; never empty
     */
    public static List<String> split(String text) {
        return split(text, MAX_LENGTH);
    }

    /**
     * Splits {@code text} into pieces of at most {@code limit} characters — {@link #split(String)}
     * with the limit spelled out, for tests and for any caller Discord gives a different ceiling.
     *
     * @param text  the message text; null is treated as empty
     * @param limit the most characters a piece may have; at least 1
     * @return the pieces, in order; never empty
     * @throws IllegalArgumentException if {@code limit} is below 1
     */
    public static List<String> split(String text, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("A message limit must be at least 1 character, not " + limit);
        }
        String content = text == null ? "" : text;
        if (content.length() <= limit) {
            return List.of(content);
        }

        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            if (content.length() - start <= limit) {
                add(pieces, content.substring(start));
                break;
            }
            int cut = breakBefore(content, start, start + limit);
            add(pieces, content.substring(start, cut));
            // Past the whitespace the cut was made at, so the next piece starts on words rather
            // than on the blank line that ended the last one.
            start = cut;
            while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
                start++;
            }
        }
        // Only reachable if the text was whitespace all the way through, which stripping left
        // with nothing to send. An empty body is still a message when attachments ride with it.
        return pieces.isEmpty() ? List.of("") : List.copyOf(pieces);
    }

    /** Adds {@code piece} without its trailing whitespace, unless that leaves nothing to send. */
    private static void add(List<String> pieces, String piece) {
        String trimmed = piece.stripTrailing();
        if (!trimmed.isEmpty()) {
            pieces.add(trimmed);
        }
    }

    /**
     * Where to cut {@code content} so that {@code [start, cut)} is at most {@code end - start}
     * characters: the last blank line, line break or space that fits, in that order of preference,
     * and failing all three the hard limit itself.
     *
     * @return an index strictly greater than {@code start}, so a caller looping on it always
     *         advances
     */
    private static int breakBefore(String content, int start, int end) {
        int paragraph = content.lastIndexOf("\n\n", end - 2);
        if (paragraph >= start) {
            return paragraph + 2;
        }
        int line = content.lastIndexOf('\n', end - 1);
        if (line >= start) {
            return line + 1;
        }
        for (int i = end - 1; i > start; i--) {
            if (Character.isWhitespace(content.charAt(i))) {
                return i + 1;
            }
        }
        // Nothing to break on in a whole message's worth of characters. Cut at the limit, but not
        // through a surrogate pair: half of one is not a character Discord (or anything else) can
        // render, and the other half would open the next message.
        boolean splitsAPair = Character.isHighSurrogate(content.charAt(end - 1))
                && Character.isLowSurrogate(content.charAt(end));
        // Backing off is only possible while it still leaves a character to send; at a limit of
        // one there is nowhere to go, and half a pair beats looping forever on an empty piece.
        return splitsAPair && end - 1 > start ? end - 1 : end;
    }
}

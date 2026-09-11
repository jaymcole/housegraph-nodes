package io.github.jaymcole.housegraph.plugins.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a too-long message turns into. The property every one of these is really guarding is that
 * nothing a graph produced is dropped on the way to Discord — the splitter exists because the
 * alternative was the whole send failing on a length nobody chose — so most of them put the pieces
 * back together and compare.
 * <p>
 * The small-limit cases use {@link DiscordMessages#split(String, int)} so the fixtures stay
 * readable; the boundary rules they exercise are the same ones the real
 * {@value DiscordMessages#MAX_LENGTH} limit gets.
 */
class DiscordMessagesTest {

    @Test
    void textThatAlreadyFitsIsOneMessage() {
        assertEquals(List.of("hello there"), DiscordMessages.split("hello there"));
        assertEquals(List.of("a".repeat(DiscordMessages.MAX_LENGTH)),
                DiscordMessages.split("a".repeat(DiscordMessages.MAX_LENGTH)),
                "exactly the limit still fits");
    }

    @Test
    void emptyTextIsStillOneMessage() {
        // A send with attachments and no words has to have a message to hang them on.
        assertEquals(List.of(""), DiscordMessages.split(""));
        assertEquals(List.of(""), DiscordMessages.split(null));
    }

    @Test
    void oneCharacterOverTheLimitBecomesTwoMessages() {
        List<String> messages = DiscordMessages.split("a".repeat(DiscordMessages.MAX_LENGTH + 1));
        assertEquals(2, messages.size());
        assertEquals(DiscordMessages.MAX_LENGTH, messages.get(0).length());
        assertEquals("a", messages.get(1));
    }

    @Test
    void noMessageIsEverOverTheLimit() {
        // The failure that started this: a long LLM answer, headings and bullets and all.
        String answer = ("### Buying a school bus\n"
                + "- **Capacity**: how many students do you need to carry?\n"
                + "- **Routes**: what does the typical route look like?\n\n").repeat(120);
        List<String> messages = DiscordMessages.split(answer);

        assertTrue(messages.size() > 1, "the fixture has to be long enough to split");
        for (String message : messages) {
            assertTrue(message.length() <= DiscordMessages.MAX_LENGTH,
                    "a message Discord would reject at " + message.length() + " characters");
            assertFalse(message.isEmpty(), "an empty message is one Discord rejects too");
        }
    }

    @Test
    void aBlankLineIsPreferredToALaterLineBreakThatWouldAlsoHaveFit() {
        // Both breaks fit inside 45 characters; the paragraph one is the one that reads as a seam.
        String text = "A".repeat(20) + "\n\n" + "B".repeat(20) + "\n" + "C".repeat(20);

        assertEquals(List.of("A".repeat(20), "B".repeat(20) + "\n" + "C".repeat(20)),
                DiscordMessages.split(text, 45));
    }

    @Test
    void asManyWholeParagraphsAsFitGoInOneMessage() {
        String paragraphs = "A".repeat(30) + "\n\n" + "B".repeat(30) + "\n\n" + "C".repeat(30);

        assertEquals(List.of("A".repeat(30) + "\n\n" + "B".repeat(30), "C".repeat(30)),
                DiscordMessages.split(paragraphs, 70),
                "splitting is greedy: a message is only ended when the next piece would not fit");
        assertEquals(List.of("A".repeat(30), "B".repeat(30), "C".repeat(30)),
                DiscordMessages.split(paragraphs, 40),
                "with only room for one, each paragraph is its own message");
    }

    @Test
    void aLineBreakIsUsedWhenNoBlankLineFits() {
        String lines = "A".repeat(30) + "\n" + "B".repeat(30) + "\n" + "C".repeat(30);

        assertEquals(List.of("A".repeat(30) + "\n" + "B".repeat(30), "C".repeat(30)),
                DiscordMessages.split(lines, 70),
                "as many whole lines per message as fit, and never mid-line");
    }

    @Test
    void wordsAreNotCutInHalfWhenThereIsASpaceToCutAt() {
        List<String> messages = DiscordMessages.split("alpha bravo charlie delta echo", 12);

        assertEquals(List.of("alpha bravo", "charlie", "delta echo"), messages);
    }

    @Test
    void whitespaceAtTheSeamIsDroppedRatherThanOpeningTheNextMessage() {
        List<String> messages = DiscordMessages.split("A".repeat(30) + "\n\n\n\n\n" + "B".repeat(30), 40);

        assertEquals(List.of("A".repeat(30), "B".repeat(30)), messages);
    }

    @Test
    void textWithNothingToBreakOnIsCutAtTheLimitAndLosesNothing() {
        String blob = "z".repeat(DiscordMessages.MAX_LENGTH * 2 + 500);
        List<String> messages = DiscordMessages.split(blob);

        assertEquals(3, messages.size());
        assertEquals(DiscordMessages.MAX_LENGTH, messages.get(0).length());
        assertEquals(DiscordMessages.MAX_LENGTH, messages.get(1).length());
        assertEquals(500, messages.get(2).length());
        assertEquals(blob, String.join("", messages), "a hard cut still keeps every character");
    }

    @Test
    void aHardCutNeverSplitsAnEmojiInHalf() {
        // 1500 grinning faces: two characters each, no whitespace anywhere to break on, so every
        // cut lands in the middle of the text and an unguarded one would land between a surrogate
        // pair — half a character, which renders as nothing on either side.
        String emoji = "😀".repeat(1500);
        List<String> messages = DiscordMessages.split(emoji);

        for (String message : messages) {
            assertTrue(message.length() <= DiscordMessages.MAX_LENGTH);
            assertFalse(Character.isHighSurrogate(message.charAt(message.length() - 1)),
                    "a message must not end on half an emoji");
            assertFalse(Character.isLowSurrogate(message.charAt(0)),
                    "a message must not open on half an emoji");
        }
        assertEquals(emoji, String.join("", messages));
    }

    @Test
    void textThatIsNothingButWhitespaceCollapsesToOneEmptyMessage() {
        assertEquals(List.of(""), DiscordMessages.split(" ".repeat(DiscordMessages.MAX_LENGTH * 2)));
    }

    @Test
    void aLimitOfOneTerminatesEvenOnTextThereIsNoRoomToCutSafelyIn() {
        // Nothing can keep a surrogate pair whole inside a single character, and the splitter has
        // to make progress rather than loop on a piece it cannot shorten.
        assertEquals(6, DiscordMessages.split("😀".repeat(3), 1).size());
    }

    @Test
    void aLimitBelowOneIsRejectedRatherThanLoopingForever() {
        assertThrows(IllegalArgumentException.class, () -> DiscordMessages.split("anything", 0));
    }
}

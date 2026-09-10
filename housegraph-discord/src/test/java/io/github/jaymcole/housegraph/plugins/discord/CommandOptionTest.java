package io.github.jaymcole.housegraph.plugins.discord;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies a {@link CommandOption} can only hold a combination Discord would actually take.
 * Everything here is normalization the record does for itself, so nothing further down —
 * registration, the saved graph, the "did the options change?" check the node's Apply button
 * relies on — has to re-check it.
 */
class CommandOptionTest {

    @Test
    void blanksAndDuplicatesAreDroppedAndOrderIsKept() {
        CommandOption option = new CommandOption("env", DiscordOptionType.TEXT,
                Arrays.asList(" prod ", "", "staging", "prod", null), ChoiceMode.RESTRICTED);

        assertEquals(List.of("prod", "staging"), option.choices(),
                "Discord rejects a repeated choice, and a blank one is nothing the user meant to type");
    }

    @Test
    void anEmptyListAndFreeInputAlwaysTravelTogether() {
        assertEquals(ChoiceMode.FREE, new CommandOption("env", DiscordOptionType.TEXT, List.of(), ChoiceMode.RESTRICTED).choiceMode(),
                "a mode restricting the option to nothing is not a state Discord has");
        assertEquals(List.of(), new CommandOption("env", DiscordOptionType.TEXT, List.of("prod"), ChoiceMode.FREE).choices(),
                "free input means the list is not in play, so it is not carried around either");
    }

    @Test
    void aTypeDiscordTakesNoListForDropsOne() {
        CommandOption user = new CommandOption("who", DiscordOptionType.USER, List.of("me", "you"), ChoiceMode.SUGGESTED);

        assertEquals(List.of(), user.choices(),
                "Discord takes choices and autocomplete on string, integer and number options only");
        assertEquals(ChoiceMode.FREE, user.choiceMode());
    }

    @Test
    void twoOptionsThatMeanTheSameThingAreEqual() {
        assertEquals(new CommandOption("count", DiscordOptionType.INTEGER),
                new CommandOption("count", DiscordOptionType.INTEGER, List.of(" "), ChoiceMode.SUGGESTED),
                "the node's Apply button compares edited options against the current ones to decide "
                        + "whether to rebuild its ports; equal-meaning options must compare equal");
    }
}

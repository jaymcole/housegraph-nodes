package io.github.jaymcole.housegraph.plugins.discord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One declared slash-command option: its (lowercase) {@code name}, its {@link DiscordOptionType},
 * and optionally a list of {@code choices} that Discord either {@link ChoiceMode#RESTRICTED
 * restricts} the option to or {@link ChoiceMode#SUGGESTED suggests} through autocomplete.
 * <p>
 * The record normalizes itself so an option can't hold a combination Discord would reject:
 * blank choices are dropped and duplicates collapse (Discord rejects a repeated choice);
 * {@link ChoiceMode#FREE} and an empty list always travel together, so a mode is never left
 * pointing at nothing; and a type Discord takes no list for
 * ({@link DiscordOptionType#supportsChoices()}) drops the list rather than failing registration
 * of the whole command later. Two options that mean the same thing therefore compare equal,
 * which is what the node's "did the options actually change?" check relies on.
 *
 * @param name       the option's lowercase name (how its value is keyed in an invocation)
 * @param type       the option's value type
 * @param choices    the declared values, in the order they were declared; empty when {@code choiceMode} is {@link ChoiceMode#FREE}
 * @param choiceMode what {@code choices} means to Discord
 */
public record CommandOption(String name, DiscordOptionType type, List<String> choices, ChoiceMode choiceMode) {

    public CommandOption {
        choices = normalize(choices);
        if (choiceMode == null) {
            choiceMode = ChoiceMode.FREE;
        }
        if (choices.isEmpty() || choiceMode == ChoiceMode.FREE || (type != null && !type.supportsChoices())) {
            choices = List.of();
            choiceMode = ChoiceMode.FREE;
        }
    }

    /** An option with no choice list: any value of its type. */
    public CommandOption(String name, DiscordOptionType type) {
        this(name, type, List.of(), ChoiceMode.FREE);
    }

    /** Trimmed, blank-free, duplicate-free, in declaration order. */
    private static List<String> normalize(List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String choice : choices) {
            if (choice != null && !choice.isBlank()) {
                kept.add(choice.trim());
            }
        }
        return List.copyOf(new ArrayList<>(kept));
    }
}

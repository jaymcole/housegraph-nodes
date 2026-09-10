package io.github.jaymcole.housegraph.plugins.discord;

/**
 * What the list of values on a {@link CommandOption} means to Discord. Discord offers two
 * different things here and they are not interchangeable, so the list alone doesn't say which
 * one is wanted:
 * <ul>
 *   <li>{@link #RESTRICTED} registers the values as the option's <em>choices</em>. Discord shows
 *       them as a picker and refuses anything else, so the graph can rely on the value being one
 *       of them. Discord caps a choice list at 25 and it is fixed at registration — changing it
 *       means re-registering the command (in this library: a reconnect).</li>
 *   <li>{@link #SUGGESTED} turns on Discord's <em>autocomplete</em> for the option instead. As
 *       someone types, Discord asks the bot what to suggest and this library answers with the
 *       matching values — but the person can still submit something that isn't in the list, so
 *       the graph must handle values it has never heard of. The declared list isn't capped at 25
 *       (only the 25 <em>shown</em> at a time are), which is the practical reason to prefer it
 *       for long lists.</li>
 * </ul>
 * Discord allows exactly one of the two per option — an autocompleting option may not also carry
 * choices — which is why this is one mode rather than two independent flags.
 */
public enum ChoiceMode {

    /** No list: the option takes any value of its type. */
    FREE,

    /** The values are the option's choices; Discord won't accept anything else. */
    RESTRICTED,

    /** The values are autocomplete suggestions; anything else is still submittable. */
    SUGGESTED
}

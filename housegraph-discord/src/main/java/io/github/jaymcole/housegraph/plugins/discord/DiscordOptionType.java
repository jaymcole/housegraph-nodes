package io.github.jaymcole.housegraph.plugins.discord;

/**
 * The kinds of slash-command option HouseGraph exposes. The type controls the input UI
 * Discord shows (a number field, a user picker, …); the graph always receives the value
 * as text. Mapped to JDA's option types by {@link DiscordBot}.
 */
public enum DiscordOptionType {
    TEXT,
    INTEGER,
    BOOLEAN,
    USER;

    /**
     * Whether Discord lets this kind of option carry a list of values — as fixed choices or as
     * autocomplete suggestions. Discord allows both only on its string, integer and number
     * options; a boolean is already a two-value picker and a user is picked from the server's
     * members, so neither takes a list of ours.
     */
    public boolean supportsChoices() {
        return this == TEXT || this == INTEGER;
    }
}

package io.github.jaymcole.housegraph.plugins.discord;

/**
 * One button to attach to an outgoing message: its {@code id} (returned by Discord on click,
 * and what a {@link DiscordButtonClick}-listening node matches against) and its {@code label}.
 *
 * @param id    the button's custom id; also the string a Button Click node's flow-out branch is named after
 * @param label the text shown on the button
 */
public record DiscordButtonSpec(String id, String label) {
}

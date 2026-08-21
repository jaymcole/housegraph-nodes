package io.github.jaymcole.housegraph.plugins.discord;

/**
 * A received button click, reduced to what the graph needs: which button ({@code buttonId},
 * matching the id it was sent with), where and who it came from, and a {@link DiscordReply}
 * handle to answer the (deferred) interaction. Published by {@link DiscordBot} and consumed by
 * the Discord Button Click node.
 *
 * @param buttonId   the clicked button's custom id
 * @param channelId  the id of the channel the message was posted in
 * @param authorId   the clicking user's Discord id
 * @param authorName the clicking user's display name
 * @param reply      the handle used to answer the (deferred) interaction
 */
public record DiscordButtonClick(String buttonId, String channelId, String authorId, String authorName,
                                  DiscordReply reply) {
}

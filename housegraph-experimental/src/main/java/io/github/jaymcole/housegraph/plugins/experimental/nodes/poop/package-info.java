/**
 * Insights over the daily-poop log a house graph keeps in a {@code housegraph-database} table:
 * one person's stats and streaks, and a household leaderboard. Both are pure functions of the rows
 * handed to them, so they work on whatever Find Rows or SQL Query returns and own no storage of
 * their own. They live in the experimental library until they have a permanent home.
 */
package io.github.jaymcole.housegraph.plugins.experimental.nodes.poop;

/**
 * Talking to Robinhood: one logged-in
 * {@link io.github.jaymcole.housegraph.plugins.robinhood.RobinhoodSession} that holds the login and
 * makes the calls, and small records for what comes back — a
 * {@link io.github.jaymcole.housegraph.plugins.robinhood.Quote}, an
 * {@link io.github.jaymcole.housegraph.plugins.robinhood.AccountSummary}, a
 * {@link io.github.jaymcole.housegraph.plugins.robinhood.Position}, an
 * {@link io.github.jaymcole.housegraph.plugins.robinhood.Order}. The sibling {@code nodes} package
 * is the thin layer that wires those onto ports.
 * <p>
 * <b>Robinhood publishes no API for this.</b> Everything here speaks the private interface its own
 * apps use, which can change without notice; when it does, this package is where the change lands.
 * What that means for anyone installing the library — and why it was still worth building this way
 * — is written down in {@code docs/design/robinhood-unofficial-api.md}.
 */
package io.github.jaymcole.housegraph.plugins.robinhood;

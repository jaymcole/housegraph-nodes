/**
 * Talking to Alpaca: the session every node works through, the value types its answers become, and
 * the two classes ({@link io.github.jaymcole.housegraph.plugins.alpaca.AlpacaApi} and
 * {@code Orders}) where the wire format is written down.
 * <p>
 * Nothing in this package knows about graphs, ports or JavaFX. That is deliberate — it is what
 * makes the part worth testing (the order payload, the reading of replies full of nulls and
 * numbers-as-strings, the login check) testable without standing up a node, and it keeps the node
 * classes in {@code .nodes} down to wiring ports to method calls.
 */
package io.github.jaymcole.housegraph.plugins.alpaca;

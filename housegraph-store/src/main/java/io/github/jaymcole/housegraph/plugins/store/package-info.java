/**
 * The entry-handling this library's {@link io.github.jaymcole.housegraph.plugins.store.nodes nodes}
 * share, kept out of them so it can be tested as a plain string function.
 * <p>
 * Note the neighbours: this is {@code plugins.store}, the library. The host's
 * {@code io.github.jaymcole.housegraph.store} is where {@code JsonDocumentStore} itself lives, and
 * this package builds on it rather than replacing it.
 */
package io.github.jaymcole.housegraph.plugins.store;

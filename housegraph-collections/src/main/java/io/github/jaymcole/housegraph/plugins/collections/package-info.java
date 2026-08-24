/**
 * The JavaFX-free logic behind the {@code collections} nodes: one class per collection stating the
 * rules its nodes play by — {@link io.github.jaymcole.housegraph.plugins.collections.Lists},
 * {@link io.github.jaymcole.housegraph.plugins.collections.Maps} and
 * {@link io.github.jaymcole.housegraph.plugins.collections.Sets} — plus the two authored-as-text
 * modes ({@link io.github.jaymcole.housegraph.plugins.collections.TextMatch},
 * {@link io.github.jaymcole.housegraph.plugins.collections.Comparison}). They are kept beside the
 * nodes rather than inside them so each can be unit-tested on its own.
 * <p>
 * <b>{@code Lists} is the one to read first.</b> Its header explains the erasure that every rule in
 * the other two follows from: a data anchor's type is a bare {@code Class}, so a collection port's
 * element type is invisible and a node can never assume it was handed the type its author had in
 * mind. {@code Maps} and {@code Sets} each state what that costs them and what they do about it.
 * <p>
 * This package is flat on purpose while
 * {@link io.github.jaymcole.housegraph.plugins.collections.nodes} is not: a subpackage there is
 * user-visible surface — it becomes an Add-Node submenu — whereas one here would only be five
 * files wearing three folders.
 */
package io.github.jaymcole.housegraph.plugins.collections;

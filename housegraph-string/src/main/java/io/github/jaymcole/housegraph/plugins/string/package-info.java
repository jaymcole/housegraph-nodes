/**
 * The JavaFX-free rules and value types every node in the Text library plays by, kept out of the
 * node classes so they can be unit-tested headlessly:
 * {@link io.github.jaymcole.housegraph.plugins.string.Texts} (null handling, the list port type,
 * positional indices), {@link io.github.jaymcole.housegraph.plugins.string.Template} (the
 * {@code {placeholder}} grammar behind Format Text),
 * {@link io.github.jaymcole.housegraph.plugins.string.Patterns} (regular expression compilation
 * and its error message), and the three authored-mode enums
 * {@link io.github.jaymcole.housegraph.plugins.string.CaseMode},
 * {@link io.github.jaymcole.housegraph.plugins.string.TrimMode} and
 * {@link io.github.jaymcole.housegraph.plugins.string.CompareMode}.
 */
package io.github.jaymcole.housegraph.plugins.string;

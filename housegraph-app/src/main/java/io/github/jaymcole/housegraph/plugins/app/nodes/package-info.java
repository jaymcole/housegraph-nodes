/**
 * The nodes that ask the running HouseGraph for something, and hand it to the graph as ordinary
 * values. Back onto the sibling {@code app} package, which holds the one call they all make
 * ({@link io.github.jaymcole.housegraph.plugins.app.HostService}) and the services it can be made
 * to ({@link io.github.jaymcole.housegraph.plugins.app.GraphImages}).
 * <p>
 * <b>Every node here is an action node</b> — a flow input, a flow output, and work in between —
 * which is the split this repository's {@code CLAUDE.md} argues for. Nothing here decides
 * <em>when</em> to run: wire a Daily Trigger in to keep a dated picture of the house's automations,
 * a web hook to fetch one on demand, or a button. A node that drew the graph on a timer of its own
 * would serve exactly one of those.
 * <p>
 * <b>This is also the only package in the library that touches JavaFX.</b> Images come back as
 * {@code javafx.scene.image.Image}, the currency the rest of the node ecosystem already speaks —
 * Camera Snapshot emits one, Animal Classifier consumes one — so a graph picture can be fed
 * straight into anything that takes an image. Keeping that import here and nowhere else is what
 * lets the request, the reply and every failure message be unit-tested headlessly, since the
 * libraries in this repository compile against JavaFX but never get it on the test classpath.
 */
package io.github.jaymcole.housegraph.plugins.app.nodes;

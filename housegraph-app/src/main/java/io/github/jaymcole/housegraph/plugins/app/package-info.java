/**
 * Asking the HouseGraph application that is hosting this library for something only it can
 * produce, and turning what comes back into values a graph can use.
 * <p>
 * Every other library in this repository reaches <em>outwards</em> — a camera, a git remote, a
 * model server. This one reaches <em>inwards</em>, at the process it is already running inside,
 * and that turns out to be the harder direction, for one reason:
 * <p>
 * <b>A node library cannot see the application's classes, and the application cannot see the
 * library's.</b> {@code housegraph-api} is the entire shared vocabulary. The app's own types — its
 * canvas, its image export, its window — are not on this library's compile classpath and never
 * will be; and a class this library declares cannot be implemented on the app side, because plugin
 * class loading is parent-first, so the parent has no way to reach a child's classes. An interface
 * defined in either place is therefore useless as a contract between them. What <em>is</em> shared
 * is the JDK, plus {@code housegraph-api} itself.
 * <p>
 * <b>So the contract is a JDK type carrying a map.</b> The app publishes a
 * {@link java.util.function.Function Function&lt;Map&lt;String,Object&gt;,Map&lt;String,Object&gt;&gt;}
 * into {@link io.github.jaymcole.housegraph.resource.ResourceRegistry} under a well-known name;
 * this library finds it there and calls it. {@link java.util.function.Function} and
 * {@link java.util.Map} are loaded by the bootstrap loader, so both sides mean the same class by
 * them — which is exactly what an interface declared on either side would fail to achieve.
 * {@link io.github.jaymcole.housegraph.plugins.app.HostService} is that call, once, so a node
 * never writes the lookup or the error handling itself, and
 * {@link io.github.jaymcole.housegraph.plugins.app.GraphImages} is the one service spoken to
 * today.
 * <p>
 * <b>Every service here is optional by construction.</b> Nothing is registered until an
 * application version that has the feature registers it, and a build that cannot offer a service
 * — one running graphs without a canvas, say — never will. A missing service is therefore an
 * ordinary runtime outcome rather than a broken install, and every node here fails with a sentence
 * saying so rather than with an absent value. The service names and their request and reply shapes
 * are written down in {@code docs/design/graph-image-service.md}.
 * <p>
 * <b>The JavaFX split is the same one the rest of this repository makes.</b> Everything in this
 * package is free of JavaFX so it can be unit-tested headlessly against a stub service registered
 * in the registry; only the {@link io.github.jaymcole.housegraph.plugins.app.nodes nodes} package
 * touches {@code javafx.scene.image.Image}, and only to wrap bytes that already exist on disk.
 */
package io.github.jaymcole.housegraph.plugins.app;

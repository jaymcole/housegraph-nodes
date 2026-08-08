/**
 * JVM-native machine-learning inference clients.
 * <p>
 * These are the engine-side counterparts to the {@code plugins.ml.nodes} nodes, in the
 * same way {@code plugins.camera} holds the clients its {@code plugins.camera.nodes} nodes
 * drive. Each class wraps a locally-run model behind a small, JavaFX-free API so the nodes
 * (and tests) can call it headlessly. Models run through
 * <a href="https://djl.ai">Deep Java Library</a> (PyTorch engine); the native runtime
 * and model weights download to DJL's on-disk cache on first use — no Python required.
 * <p>
 * {@link io.github.jaymcole.housegraph.plugins.ml.ImageNetClassifier} is the first: a shared,
 * lazily-loaded ImageNet image classifier. As more models are added (detectors, other
 * classifiers, a local LLM) and shared concerns emerge, common lifecycle/loading code
 * should be factored out here rather than duplicated per node.
 */
package io.github.jaymcole.housegraph.plugins.ml;

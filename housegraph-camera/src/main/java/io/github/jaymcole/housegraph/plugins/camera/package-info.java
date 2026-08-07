/**
 * IP-camera discovery and clients.
 * <p>
 * {@link io.github.jaymcole.housegraph.plugins.camera.CameraDiscovery} finds cameras via
 * ONVIF WS-Discovery (with a TCP port-scan fallback);
 * {@link io.github.jaymcole.housegraph.plugins.camera.OnvifEnrichment} adds authenticated
 * ONVIF details; {@link io.github.jaymcole.housegraph.plugins.camera.ReolinkClient} reads
 * detection state from Reolink's HTTP CGI API; and
 * {@link io.github.jaymcole.housegraph.plugins.camera.CameraConfigStore} persists the
 * (credential-free) camera registry. Pure JDK — no camera SDK.
 */
package io.github.jaymcole.housegraph.plugins.camera;

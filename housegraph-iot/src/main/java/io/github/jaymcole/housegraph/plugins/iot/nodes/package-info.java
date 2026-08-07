/**
 * Nodes that drive small networked devices over plain HTTP.
 *
 * <p>Currently the squirrel-alarm sign — an Arduino UNO R4 WiFi with an LED matrix, whose firmware
 * lives in {@code firmware/squirrel_status} in this repository. Keeping the firmware beside the
 * node that drives it is the point of the split: neither is much use without the other.
 */
package io.github.jaymcole.housegraph.plugins.iot.nodes;

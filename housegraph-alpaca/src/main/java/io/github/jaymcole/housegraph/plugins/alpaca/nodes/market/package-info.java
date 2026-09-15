/**
 * Reading the market: prices, history, and whether it is open.
 * <p>
 * Nothing in this package can change anything. Every node here is a read against Alpaca's
 * market-data API or its calendar, which is what makes them the safe half of this library to
 * experiment with — and why a graph that only quotes and charts needs no live account at all, since
 * paper keys read the same data.
 */
package io.github.jaymcole.housegraph.plugins.alpaca.nodes.market;

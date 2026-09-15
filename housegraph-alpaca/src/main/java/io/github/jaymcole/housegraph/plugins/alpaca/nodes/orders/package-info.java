/**
 * Placing orders and chasing them: the half of this library that spends money.
 * <p>
 * <b>Place Order and Close Position are the only two nodes in the library that change anything</b>,
 * and both ship with Dry Run switched on. Order Status, Cancel Order and Get Recent Orders are what
 * a graph uses to find out what happened afterwards — deliberately separate nodes, because "did it
 * fill?" is a question with a schedule attached and schedules belong to triggers.
 */
package io.github.jaymcole.housegraph.plugins.alpaca.nodes.orders;

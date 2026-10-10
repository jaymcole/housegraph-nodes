package io.github.jaymcole.housegraph.plugins.experimental.nodes.poop;

import java.util.List;

/**
 * The list type these nodes' row ports declare, erased to the same raw {@code List} class
 * {@code housegraph-database}'s {@code Rows.ROWS_TYPE} uses — which is what lets Find Rows' Rows
 * output wire straight in, and Standings wire into the collections library. Declared here rather
 * than borrowed because this library depends on nothing but the API.
 */
final class PoopRows {

    @SuppressWarnings("unchecked")
    static final Class<List<?>> TYPE = (Class<List<?>>) (Class<?>) List.class;

    private PoopRows() {
    }
}

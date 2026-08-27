package io.github.jaymcole.housegraph.plugins.app;

import io.github.jaymcole.housegraph.resource.ResourceRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A stand-in for the application, registered under a real service name, for the tests in this
 * package.
 * <p>
 * It is deliberately a bare {@link Function} of maps rather than anything typed: that is exactly
 * what the application publishes and all that either side can agree on, so a stub of any other
 * shape would be testing a contract this library does not actually have.
 * <p>
 * {@link #remove()} matters. {@link ResourceRegistry#shared()} is one process-wide instance, so a
 * stub left registered is visible to every later test in the JVM.
 */
final class StubService implements Function<Map<String, Object>, Map<String, Object>> {

    private final String name;
    private final Function<Map<String, Object>, Map<String, Object>> answer;

    /** The request the last call arrived with, or null if it has not been called. */
    Map<String, Object> lastRequest;

    private StubService(String name, Function<Map<String, Object>, Map<String, Object>> answer) {
        this.name = name;
        this.answer = answer;
    }

    /** Registers a stub under {@code name} that answers every request the same way. */
    static StubService answering(String name, Map<String, Object> reply) {
        return register(name, request -> reply);
    }

    /** Registers a stub whose reply — or thrown failure — is computed from the request. */
    static StubService register(String name, Function<Map<String, Object>, Map<String, Object>> answer) {
        StubService stub = new StubService(name, answer);
        ResourceRegistry.shared().register(name, stub);
        return stub;
    }

    /** A reply map, written as pairs so a test reads as the JSON-ish thing it stands for. */
    static Map<String, Object> reply(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> request) {
        lastRequest = request;
        return answer.apply(request);
    }

    void remove() {
        ResourceRegistry.shared().unregister(name);
    }
}

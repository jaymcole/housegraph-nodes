package io.github.jaymcole.housegraph.plugins.web;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the declare/withdraw/find contract {@code LocalWebServer}'s hook dispatcher relies
 * on: path/method normalization must line up on both sides, or a declared route would never
 * match an incoming request.
 */
class RouteRegistryTest {

    @Test
    void declaredRouteIsFoundByExactMethodAndPath() {
        RouteRegistry registry = new RouteRegistry();
        registry.declare("srv", new WebHookRoute("/doorbell", "POST", false, 0));

        Optional<WebHookRoute> found = registry.find("srv", "POST", "/doorbell");

        assertTrue(found.isPresent());
        assertEquals("/doorbell", found.get().path());
    }

    @Test
    void methodMatchingIsCaseInsensitive() {
        RouteRegistry registry = new RouteRegistry();
        registry.declare("srv", new WebHookRoute("/doorbell", "post", false, 0));

        assertTrue(registry.find("srv", "POST", "/doorbell").isPresent());
    }

    @Test
    void pathMatchingToleratesAMissingLeadingSlashOnEitherSide() {
        RouteRegistry registry = new RouteRegistry();
        registry.declare("srv", new WebHookRoute("doorbell", "POST", false, 0));

        assertTrue(registry.find("srv", "POST", "/doorbell").isPresent());
    }

    @Test
    void unknownServerOrRouteIsAbsent() {
        RouteRegistry registry = new RouteRegistry();
        registry.declare("srv", new WebHookRoute("/doorbell", "POST", false, 0));

        assertTrue(registry.find("other-server", "POST", "/doorbell").isEmpty());
        assertTrue(registry.find("srv", "POST", "/unknown").isEmpty());
        assertTrue(registry.find("srv", "GET", "/doorbell").isEmpty());
    }

    @Test
    void withdrawRemovesTheDeclaration() {
        RouteRegistry registry = new RouteRegistry();
        registry.declare("srv", new WebHookRoute("/doorbell", "POST", false, 0));

        registry.withdraw("srv", "POST", "/doorbell");

        assertTrue(registry.find("srv", "POST", "/doorbell").isEmpty());
    }

    @Test
    void redeclaringUnderTheSameKeyReplacesTheRoute() {
        RouteRegistry registry = new RouteRegistry();
        registry.declare("srv", new WebHookRoute("/doorbell", "POST", false, 0));

        registry.declare("srv", new WebHookRoute("/doorbell", "POST", true, 15));

        Optional<WebHookRoute> found = registry.find("srv", "POST", "/doorbell");
        assertTrue(found.get().awaitReply());
        assertEquals(15, found.get().timeoutSeconds());
    }
}

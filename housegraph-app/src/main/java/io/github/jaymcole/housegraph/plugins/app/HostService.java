package io.github.jaymcole.housegraph.plugins.app;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One call to a service the hosting application published, and the answer as a map — the whole of
 * this library's contact with the process it runs inside.
 * <p>
 * <b>Why a map and not an interface</b> is the package documentation's subject: the app and a node
 * library share only the JDK and {@code housegraph-api}, so the request and the reply travel as
 * {@link Map}s through a {@link Function}, which are types both class loaders agree on. The cost
 * is that nothing about the shape is checked at compile time, so it is all checked here instead,
 * once, with a message per way of being wrong — a service that answered with the wrong thing is
 * otherwise a {@link ClassCastException} thrown from inside a node.
 * <p>
 * <b>A service that isn't there is not an error here.</b> {@link #find} returns empty and leaves
 * the sentence to the caller, because only the caller knows what it wanted the service for and
 * therefore what the reader should do about it — see {@link GraphImages#export}.
 * <p>
 * <b>Nothing here retries or times the call out.</b> The application is a local, in-process
 * callee: it either answers or it is wedged, and re-asking a wedged application achieves nothing.
 * A node that wants a bound sets its own execution timeout, which the engine enforces by
 * interrupting {@code process()}.
 */
public final class HostService {

    private static final Logger log = Log.get(HostService.class);

    /**
     * The request key naming the contract revision this library speaks, so a later application can
     * tell an older library's request apart from a newer one's without guessing from the keys
     * present. Every request carries it; a service free to assume revision 1 may ignore it.
     */
    public static final String CONTRACT_KEY = "contract";

    /**
     * The reply key a service sets to say the request failed. Its value is shown to the user as
     * the reason, so a service writes a sentence there rather than a code.
     */
    public static final String ERROR_KEY = "error";

    /**
     * The revision of the request/reply contract this library speaks. Bumped only when an existing
     * key changes meaning — adding an optional key does not need it, since a service that does not
     * know a key ignores it.
     */
    public static final int CONTRACT_REVISION = 1;

    private final String name;
    private final Function<?, ?> service;

    private HostService(String name, Function<?, ?> service) {
        this.name = name;
        this.service = service;
    }

    /**
     * The service published under {@code name}, if this application publishes one.
     * <p>
     * Empty covers both of the ordinary cases at once — an application that predates the service,
     * and one running in a mode that cannot offer it — and they are not distinguishable from here,
     * which is why the caller writes the message.
     *
     * @param name the well-known service name, e.g. {@link GraphImages#SERVICE_NAME}
     * @return the service, or empty when nothing of that name is registered
     */
    public static Optional<HostService> find(String name) {
        return ResourceRegistry.shared().find(name, Function.class)
                .map(function -> new HostService(name, function));
    }

    /** The name this service was found under. */
    public String name() {
        return name;
    }

    /**
     * Sends one request and returns the reply, having established that it is a reply: a map, and
     * not one carrying an {@link #ERROR_KEY}.
     *
     * @param request what to ask for; the {@link #CONTRACT_KEY} is added here so no caller can
     *                forget it
     * @return the reply map, never null and never carrying an error
     * @throws HostServiceException if the service threw, answered with something that is not a
     *                              map, or answered with an error
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(Map<String, Object> request) {
        Map<String, Object> outgoing = new LinkedHashMap<>(request);
        outgoing.put(CONTRACT_KEY, CONTRACT_REVISION);

        long startedAt = System.nanoTime();
        Object reply;
        try {
            reply = ((Function<Map<String, Object>, ?>) service).apply(outgoing);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // The engine interrupts process() to cancel a run or to enforce a node timeout, and
                // a service that was waiting on the UI thread surfaces that as a runtime failure of
                // its own. Naming the cancellation is more use than repeating the app's complaint.
                throw new HostServiceException("The run was cancelled while HouseGraph was answering \""
                        + name + "\".", e);
            }
            throw new HostServiceException("HouseGraph's \"" + name + "\" service failed: " + describe(e), e);
        }
        log.debug("{} answered in {} ms", name, (System.nanoTime() - startedAt) / 1_000_000L);

        if (!(reply instanceof Map<?, ?> map)) {
            throw new HostServiceException("HouseGraph's \"" + name + "\" service answered with "
                    + (reply == null ? "nothing" : "a " + reply.getClass().getSimpleName())
                    + " rather than a reply. This library and that service disagree about the contract;"
                    + " updating both to the same release is the fix.");
        }
        Object error = map.get(ERROR_KEY);
        if (error instanceof String text && !text.isBlank()) {
            throw new HostServiceException("HouseGraph refused the \"" + name + "\" request: " + text);
        }
        return (Map<String, Object>) map;
    }

    /**
     * The strings under {@code key}, for a reply key the contract declares to be a list of them.
     * <p>
     * Defensive in the same way the collections library is: a list that arrived holding something
     * other than strings is read through {@link String#valueOf} rather than rejected, because the
     * useful failure is "the key was missing", not "element 3 was a Path".
     *
     * @param reply the reply from {@link #call}
     * @param key   the key to read
     * @return the values, never null; an empty list when the key is absent or empty
     * @throws HostServiceException if the key is present but is not a list
     */
    public List<String> stringsOf(Map<String, Object> reply, String key) {
        Object value = reply.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new HostServiceException("HouseGraph's \"" + name + "\" service answered with a "
                    + value.getClass().getSimpleName() + " for \"" + key + "\", where the contract says a list.");
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null) {
                strings.add(String.valueOf(element));
            }
        }
        return List.copyOf(strings);
    }

    /** An exception as a phrase for a message: its own text where it has one, its type otherwise. */
    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

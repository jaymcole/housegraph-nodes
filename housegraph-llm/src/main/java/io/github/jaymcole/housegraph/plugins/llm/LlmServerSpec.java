package io.github.jaymcole.housegraph.plugins.llm;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * One model server to run: what to launch, where, and the address it should be answering on when
 * it is up.
 * <p>
 * <b>The validation lives here rather than in the node</b>, the same split {@link LlmRequest}
 * makes, so "what a startable server looks like" is one thing that can be tested without a graph.
 * Blank fields fall back to what "run an LLM locally" means on a machine where nothing has been
 * moved; only a working directory that was typed and does not exist is an outright failure, since
 * that is a typo rather than an omission.
 *
 * @param name                  the name this server is known by — its {@code ResourceRegistry} key,
 *                              and what keys its orphan record on disk; blank means
 *                              {@value #DEFAULT_NAME}
 * @param command               the shell command that starts it; blank means {@value #DEFAULT_COMMAND}
 * @param directory             the directory to run it in, or null to inherit HouseGraph's own
 * @param api                   the protocol it will speak once up; null means {@link LlmApi#OLLAMA}
 * @param server                the address it should answer on, e.g. {@code http://localhost:11434}
 * @param apiKey                an optional bearer token for the readiness check; blank sends none
 * @param startupTimeoutSeconds how long to wait for it to answer, clamped to at least one second
 */
public record LlmServerSpec(String name, String command, Path directory, LlmApi api, String server,
                            String apiKey, int startupTimeoutSeconds) {

    /** What an unnamed server is called. One word, so it is a sane filename for its orphan record. */
    public static final String DEFAULT_NAME = "local-llm";

    /**
     * What starts a model server on a machine where nothing has been moved. Ollama's own, matching
     * the Server and Model the Local LLM node ships pre-filled with.
     */
    public static final String DEFAULT_COMMAND = "ollama serve";

    /**
     * Two minutes to come up. Ollama binds its port in about a second; a llama.cpp or vLLM command
     * that loads weights before it starts listening takes considerably longer, and this is sized
     * for the second case so the first is never the one that has to be tuned.
     */
    public static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 120;

    public LlmServerSpec {
        name = blankTo(name, DEFAULT_NAME);
        command = blankTo(command, DEFAULT_COMMAND);
        api = api == null ? LlmApi.OLLAMA : api;
        server = server == null ? "" : server.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (server.isEmpty()) {
            server = LocalLlmClient.DEFAULT_SERVER;
        }
        // Builds the URL the readiness check will actually GET, and throws if it will not parse -
        // here, rather than three minutes into a start that was never going to have anywhere to look.
        api.modelsEndpoint(server);
        if (directory != null && !Files.isDirectory(directory)) {
            throw new LlmException("The Directory to run \"" + command + "\" in does not exist: " + directory);
        }
        startupTimeoutSeconds = Math.max(1, startupTimeoutSeconds);
    }

    /**
     * A spec from the text a node's inputs carry, with a blank directory meaning "HouseGraph's own"
     * rather than a path of nothing.
     *
     * @param name                  the server's name, possibly blank
     * @param command               the start command, possibly blank
     * @param directory             the working directory as text, possibly blank
     * @param api                   the authored API, possibly blank — see {@link LlmApi#parse}
     * @param server                the authored address, possibly blank
     * @param apiKey                an optional bearer token
     * @param startupTimeoutSeconds how long to wait for it to answer
     * @return the validated spec
     * @throws LlmException if the API is not one this library knows, the address is unusable, or
     *                      the directory was named and does not exist
     */
    public static LlmServerSpec of(String name, String command, String directory, String api, String server,
                                   String apiKey, int startupTimeoutSeconds) {
        return new LlmServerSpec(name, command, path(directory), LlmApi.parse(api), server, apiKey,
                startupTimeoutSeconds);
    }

    /** The server's root URL — where the readiness check looks and what the node reports. */
    public String address() {
        return api.root(server);
    }

    private static Path path(String directory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        try {
            return Path.of(directory.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new LlmException("\"" + directory + "\" is not a usable directory: " + e.getMessage(), e);
        }
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}

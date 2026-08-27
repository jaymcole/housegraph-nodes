package io.github.jaymcole.housegraph.plugins.llm;

import java.util.List;
import java.util.Locale;

/**
 * What one look at a model server's address found: whether anything is answering there, which
 * models it has if so, and a sentence saying why not if it isn't.
 * <p>
 * <b>"Not running" is an answer here, not a failure.</b> Everything else in this library throws
 * when it cannot reach a server, because a prompt that cannot be sent has nothing to hand
 * downstream. A status check is the opposite: "nothing is listening" is precisely the thing it was
 * asked to find out, and a graph that starts the server only when it is down needs that answer as
 * data it can branch on rather than as an exception that stops the run. {@link LlmModels#status}
 * is therefore the one call in this library that swallows a connection failure — and the
 * {@link #detail} is where what it swallowed is kept.
 *
 * @param running whether the server answered its model-list endpoint
 * @param models  the models it reports having, in the order it listed them; empty when it is down,
 *                and also when it is up with nothing pulled yet
 * @param detail  one sentence about this result, for a node to show and a person to act on
 */
public record LlmServerStatus(boolean running, List<String> models, String detail) {

    public LlmServerStatus {
        models = models == null ? List.of() : List.copyOf(models);
        detail = detail == null ? "" : detail;
    }

    /**
     * Whether the server has a model, by the name someone would type rather than the name the
     * server prints.
     * <p>
     * <b>Ollama tags every model</b>, so a machine with {@code llama3.2} pulled reports
     * {@code llama3.2:latest} — and a Model field that says {@code llama3.2} is both what the
     * Local LLM node ships with and what {@code ollama run} accepts. Comparing the two literally
     * would report a model that is installed, works, and is named on the very next node as
     * missing. An untagged name therefore also matches its {@code :latest} tag; a name that names
     * a tag ({@code llama3.2:1b}) is matched exactly, since asking for a specific tag means it.
     *
     * @param model the model to look for, as someone would type it
     * @return true if the server reports having it
     */
    public boolean has(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String wanted = model.trim().toLowerCase(Locale.ROOT);
        String tagged = wanted.contains(":") ? wanted : wanted + ":latest";
        for (String installed : models) {
            String have = installed.toLowerCase(Locale.ROOT);
            if (have.equals(wanted) || have.equals(tagged)) {
                return true;
            }
        }
        return false;
    }

    /** A status for a server that could not be reached, carrying why. */
    static LlmServerStatus down(String detail) {
        return new LlmServerStatus(false, List.of(), detail);
    }

    /** A status for a server that answered, carrying what it said it has. */
    static LlmServerStatus up(List<String> models, String detail) {
        return new LlmServerStatus(true, models, detail);
    }
}

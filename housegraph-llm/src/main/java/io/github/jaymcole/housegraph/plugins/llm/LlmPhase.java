package io.github.jaymcole.housegraph.plugins.llm;

/**
 * Which half of its work a model is in, as a streamed answer arrives — published on the prompt
 * node's <b>Phase</b> output so a graph can format a status message differently while the model is
 * still reasoning.
 * <p>
 * <b>Why a phase rather than a flow port for the transition.</b> A port that fired once when
 * thinking ended would say nothing at all for the models that never think, leaving a graph to
 * special-case them; a phase is published on every update, so "this model went straight to
 * answering" is {@link #ANSWERING} from the first tick and needs no branch of its own.
 */
public enum LlmPhase {

    /** The model is reasoning: nothing has been added to the answer yet. */
    THINKING("thinking"),

    /** The model is writing its answer. The only phase a model that cannot think is ever in. */
    ANSWERING("answering"),

    /** The answer is complete — the phase published on the final, non-streamed firing. */
    DONE("done");

    private final String label;

    LlmPhase(String label) {
        this.label = label;
    }

    /** @return the text published on the node's Phase output, for a graph to compare against. */
    public String label() {
        return label;
    }
}

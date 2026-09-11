package io.github.jaymcole.housegraph.plugins.llm;

/**
 * A finished answer: what the model said, and what it thought on the way there.
 * <p>
 * <b>The two are kept apart because a graph wants them apart.</b> A model's reasoning is worth
 * showing while it works and almost never worth sending as the answer - the whole point of a
 * thinking model is that it argues with itself first - so folding the reasoning into the reply
 * would make the node's Response output unusable for exactly the models this is for.
 *
 * @param response what the model answered; {@code ""} if it said nothing, which is not a failure
 * @param thinking what it reasoned first; {@code ""} for a model that wasn't asked to think, or can't
 */
public record LlmAnswer(String response, String thinking) {

    public LlmAnswer {
        response = response == null ? "" : response;
        thinking = thinking == null ? "" : thinking;
    }
}

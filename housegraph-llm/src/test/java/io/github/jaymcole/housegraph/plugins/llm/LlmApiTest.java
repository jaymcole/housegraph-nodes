package io.github.jaymcole.housegraph.plugins.llm;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmApiTest {

    @Test
    void blankApiIsOllama() {
        assertEquals(LlmApi.OLLAMA, LlmApi.parse(null));
        assertEquals(LlmApi.OLLAMA, LlmApi.parse(""));
        assertEquals(LlmApi.OLLAMA, LlmApi.parse("   "));
    }

    @Test
    void anApiIsNamedHoweverItIsSpelled() {
        assertEquals(LlmApi.OPENAI, LlmApi.parse("openai"));
        assertEquals(LlmApi.OPENAI, LlmApi.parse("OpenAI"));
        assertEquals(LlmApi.OPENAI, LlmApi.parse(" open-ai "));
        assertEquals(LlmApi.OPENAI, LlmApi.parse("OpenAI_Compatible"));
    }

    @Test
    void theServerCanBeNamedInsteadOfTheProtocol() {
        assertEquals(LlmApi.OPENAI, LlmApi.parse("lm studio"));
        assertEquals(LlmApi.OPENAI, LlmApi.parse("llama.cpp"));
        assertEquals(LlmApi.OPENAI, LlmApi.parse("vLLM"));
        assertEquals(LlmApi.OLLAMA, LlmApi.parse("Ollama"));
    }

    @Test
    void anUnknownApiFailsRatherThanGuessing() {
        LlmException failure = assertThrows(LlmException.class, () -> LlmApi.parse("anthropic"));
        assertTrue(failure.getMessage().contains("anthropic"), failure.getMessage());
        assertTrue(failure.getMessage().contains("ollama"), failure.getMessage());
    }

    @Test
    void anAddressBecomesTheApisEndpoint() {
        assertEquals("http://localhost:11434/api/generate",
                LlmApi.OLLAMA.endpoint("http://localhost:11434").toString());
        assertEquals("http://localhost:1234/v1/chat/completions",
                LlmApi.OPENAI.endpoint("http://localhost:1234").toString());
    }

    @Test
    void aBareHostMeansPlainHttp() {
        assertEquals("http://localhost:11434/api/generate", LlmApi.OLLAMA.endpoint("localhost:11434").toString());
        assertEquals("https://box.lan/api/generate", LlmApi.OLLAMA.endpoint("https://box.lan").toString());
    }

    @Test
    void aTrailingSlashIsNotASecondPathSegment() {
        assertEquals("http://localhost:11434/api/generate", LlmApi.OLLAMA.endpoint("http://localhost:11434//").toString());
    }

    @Test
    void anAddressThatAlreadyNamesTheEndpointIsLeftAlone() {
        assertEquals("http://localhost:1234/v1/chat/completions",
                LlmApi.OPENAI.endpoint("http://localhost:1234/v1").toString());
        assertEquals("http://localhost:1234/v1/chat/completions",
                LlmApi.OPENAI.endpoint("http://localhost:1234/v1/chat/completions").toString());
        assertEquals("http://localhost:11434/api/generate",
                LlmApi.OLLAMA.endpoint("http://localhost:11434/api/generate").toString());
    }

    @Test
    void noAddressFails() {
        assertThrows(LlmException.class, () -> LlmApi.OLLAMA.endpoint(" "));
    }

    @Test
    void eachApiListsItsModelsSomewhereElse() {
        assertEquals("http://localhost:11434/api/tags",
                LlmApi.OLLAMA.modelsEndpoint("http://localhost:11434").toString());
        assertEquals("http://localhost:1234/v1/models",
                LlmApi.OPENAI.modelsEndpoint("http://localhost:1234").toString());
        assertEquals("http://localhost:1234/v1/models",
                LlmApi.OPENAI.modelsEndpoint("http://localhost:1234/v1").toString());
    }

    @Test
    void aServerThatNamesThePromptEndpointStillHasAModelList() {
        // Someone pastes the address they already had working into Server. Appending to it would
        // give /api/generate/api/tags, which is a 404 and reads as "the server is down".
        assertEquals("http://localhost:11434/api/tags",
                LlmApi.OLLAMA.modelsEndpoint("http://localhost:11434/api/generate").toString());
        assertEquals("http://localhost:1234/v1/models",
                LlmApi.OPENAI.modelsEndpoint("http://localhost:1234/v1/chat/completions").toString());
    }

    @Test
    void anAddressThatAlreadyNamesTheModelListIsLeftAlone() {
        assertEquals("http://localhost:11434/api/tags",
                LlmApi.OLLAMA.modelsEndpoint("http://localhost:11434/api/tags").toString());
        assertEquals("http://localhost:1234/v1/models",
                LlmApi.OPENAI.modelsEndpoint("http://localhost:1234/v1/models").toString());
    }

    @Test
    void theModelsAreReadFromWhereEachApiPutsThem() {
        assertEquals(List.of("llama3.2:latest", "qwen2.5:7b"), LlmApi.OLLAMA.modelsFrom(
                "{\"models\":[{\"name\":\"llama3.2:latest\",\"size\":1},{\"name\":\"qwen2.5:7b\"}]}"));
        assertEquals(List.of("local-model"), LlmApi.OPENAI.modelsFrom(
                "{\"object\":\"list\",\"data\":[{\"id\":\"local-model\",\"object\":\"model\"}]}"));
    }

    @Test
    void aServerWithNothingPulledIsRunningWithNoModels() {
        assertEquals(List.of(), LlmApi.OLLAMA.modelsFrom("{\"models\":[]}"));
        assertEquals(List.of(), LlmApi.OPENAI.modelsFrom("{\"data\":[]}"));
    }

    @Test
    void aModelListInTheOtherApisShapeIsAFailureNotAnEmptyList() {
        LlmException failure = assertThrows(LlmException.class,
                () -> LlmApi.OLLAMA.modelsFrom("{\"data\":[{\"id\":\"local-model\"}]}"));
        assertTrue(failure.getMessage().contains("API setting"), failure.getMessage());
        assertThrows(LlmException.class, () -> LlmApi.OPENAI.modelsFrom("{\"models\":[]}"));
        assertThrows(LlmException.class, () -> LlmApi.OLLAMA.modelsFrom("<html>nope</html>"));
    }

    @Test
    void theOllamaBodyIsAPromptAndAModel() {
        JSONObject body = new JSONObject(LlmApi.OLLAMA.requestBody(request(LlmApi.OLLAMA, null, null)));
        assertEquals("llama3.2", body.getString("model"));
        assertEquals("Why is the sky blue?", body.getString("prompt"));
        assertFalse(body.getBoolean("stream"));
        assertFalse(body.has("system"), "a blank system prompt should be left out entirely");
        assertFalse(body.has("options"), "an unset temperature should leave the server's default alone");
    }

    @Test
    void theOpenAiBodyIsAOneTurnConversation() {
        JSONObject body = new JSONObject(LlmApi.OPENAI.requestBody(request(LlmApi.OPENAI, "Be brief.", 0.2f)));
        assertEquals("llama3.2", body.getString("model"));
        assertFalse(body.getBoolean("stream"));
        assertEquals(0.2, body.getDouble("temperature"), 1e-6);
        assertEquals(2, body.getJSONArray("messages").length());
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("Be brief.", body.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("role"));
        assertEquals("Why is the sky blue?", body.getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    @Test
    void aSystemPromptAndTemperatureReachOllamaWhereItExpectsThem() {
        JSONObject body = new JSONObject(LlmApi.OLLAMA.requestBody(request(LlmApi.OLLAMA, "Be brief.", 0.7f)));
        assertEquals("Be brief.", body.getString("system"));
        assertEquals(0.7, body.getJSONObject("options").getDouble("temperature"), 1e-6);
    }

    @Test
    void aSystemPromptOfSpacesCountsAsNone() {
        JSONObject ollama = new JSONObject(LlmApi.OLLAMA.requestBody(request(LlmApi.OLLAMA, "   ", null)));
        assertFalse(ollama.has("system"));
        JSONObject openai = new JSONObject(LlmApi.OPENAI.requestBody(request(LlmApi.OPENAI, "   ", null)));
        assertEquals(1, openai.getJSONArray("messages").length());
    }

    @Test
    void theReplyIsReadFromWhereEachApiPutsIt() {
        assertEquals("Rayleigh scattering.",
                LlmApi.OLLAMA.replyFrom("{\"model\":\"llama3.2\",\"response\":\"Rayleigh scattering.\",\"done\":true}"));
        assertEquals("Rayleigh scattering.", LlmApi.OPENAI.replyFrom(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Rayleigh scattering.\"}}]}"));
    }

    @Test
    void aModelThatSaidNothingIsEmptyTextRatherThanAFailure() {
        assertEquals("", LlmApi.OLLAMA.replyFrom("{\"response\":\"\",\"done\":true}"));
        assertEquals("", LlmApi.OPENAI.replyFrom("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
    }

    @Test
    void theOtherApisReplyIsAFailureNotAnEmptyAnswer() {
        // The symptom of API set to the wrong one: a perfectly good answer this API cannot read.
        LlmException failure = assertThrows(LlmException.class,
                () -> LlmApi.OLLAMA.replyFrom("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
        assertTrue(failure.getMessage().contains("API setting"), failure.getMessage());
        assertThrows(LlmException.class, () -> LlmApi.OPENAI.replyFrom("{\"response\":\"hi\"}"));
        assertThrows(LlmException.class, () -> LlmApi.OPENAI.replyFrom("{\"choices\":[]}"));
    }

    @Test
    void somethingThatIsNotJsonFails() {
        LlmException failure = assertThrows(LlmException.class, () -> LlmApi.OLLAMA.replyFrom("<html>nope</html>"));
        assertTrue(failure.getMessage().contains("<html>nope</html>"), failure.getMessage());
    }

    @Test
    void aLongBodyIsQuotedButNotDumped() {
        String excerpt = LlmApi.excerpt("x".repeat(500));
        assertEquals(203, excerpt.length());
        assertTrue(excerpt.endsWith("..."));
        assertEquals("(nothing)", LlmApi.excerpt("  "));
    }

    @Test
    void aConversationSendsOllamaToItsChatEndpointAndAPlainPromptDoesNot() {
        assertEquals("http://localhost:11434/api/generate",
                LlmApi.OLLAMA.endpoint(request(LlmApi.OLLAMA, null, null)).toString());
        assertEquals("http://localhost:11434/api/chat",
                LlmApi.OLLAMA.endpoint(conversation(LlmApi.OLLAMA)).toString());
        // OpenAI has one endpoint for both: a conversation is just a longer messages array.
        assertEquals("http://localhost:11434/v1/chat/completions",
                LlmApi.OPENAI.endpoint(conversation(LlmApi.OPENAI)).toString());
    }

    @Test
    void aServerFieldNamingAnOllamaEndpointStillLandsOnTheRightOne() {
        // People paste whatever address they already had working. Appending to it would give
        // /api/generate/api/chat, which is a 404 built out of a correct address.
        LlmRequest typed = new LlmRequest(LlmApi.OLLAMA, "http://localhost:11434/api/generate", "llama3.2",
                null, List.of(LlmMessage.user("hi"), LlmMessage.assistant("hello")), true, "and again",
                null, null, null, 30);
        assertEquals("http://localhost:11434/api/chat", LlmApi.OLLAMA.endpoint(typed).toString());
        assertEquals("http://localhost:11434/api/tags",
                LlmApi.OLLAMA.modelsEndpoint("http://localhost:11434/api/chat").toString());
    }

    @Test
    void theFirstTurnOfAConversationGoesWhereTheRestOfItWill() {
        // Otherwise turn one is chat-templated differently from turn two, and the first answer of
        // every conversation is shaped by a different call than its successors.
        assertEquals("http://localhost:11434/api/chat",
                LlmApi.OLLAMA.endpoint(firstTurn(LlmApi.OLLAMA)).toString());
        JSONObject body = new JSONObject(LlmApi.OLLAMA.requestBody(firstTurn(LlmApi.OLLAMA)));
        assertEquals(1, body.getJSONArray("messages").length());
        assertFalse(body.has("prompt"));
    }

    @Test
    void aNonEmptyHistoryIsAConversationWhateverItWasToldToBe() {
        LlmRequest saidOtherwise = new LlmRequest(LlmApi.OLLAMA, "http://localhost:11434", "llama3.2", null,
                List.of(LlmMessage.user("hi"), LlmMessage.assistant("hello")), false, "again", null, null, null, 30);

        assertTrue(saidOtherwise.conversational());
        assertEquals("http://localhost:11434/api/chat", LlmApi.OLLAMA.endpoint(saidOtherwise).toString());
    }

    @Test
    void aConversationBecomesAMessagesArrayInEitherApi() {
        for (LlmApi api : List.of(LlmApi.OLLAMA, LlmApi.OPENAI)) {
            JSONObject body = new JSONObject(api.requestBody(conversation(api)));
            JSONArray messages = body.getJSONArray("messages");
            assertEquals(4, messages.length(), api.label());
            assertEquals("system", messages.getJSONObject(0).getString("role"));
            assertEquals("user", messages.getJSONObject(1).getString("role"));
            assertEquals("Who wrote Dune?", messages.getJSONObject(1).getString("content"));
            assertEquals("assistant", messages.getJSONObject(2).getString("role"));
            assertEquals("Frank Herbert.", messages.getJSONObject(2).getString("content"));
            assertEquals("user", messages.getJSONObject(3).getString("role"));
            assertEquals("Why is the sky blue?", messages.getJSONObject(3).getString("content"));
            assertFalse(body.has("prompt"), api.label() + " should not also send a one-shot prompt");
        }
    }

    @Test
    void theSystemPromptLeadsEveryTurnRatherThanBeingPartOfTheHistory() {
        // Editing System Prompt has to change the next answer; a conversation bound to the
        // instruction it started under would be the alternative.
        JSONObject body = new JSONObject(LlmApi.OLLAMA.requestBody(conversation(LlmApi.OLLAMA)));
        assertEquals("Be brief.", body.getJSONArray("messages").getJSONObject(0).getString("content"));
    }

    @Test
    void ollamaIsReadInEitherOfItsTwoReplyShapes() {
        assertEquals("Rayleigh scattering.", LlmApi.OLLAMA.replyFrom(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Rayleigh scattering.\"},\"done\":true}"));
        LlmException failure = assertThrows(LlmException.class, () -> LlmApi.OLLAMA.replyFrom("{\"done\":true}"));
        assertTrue(failure.getMessage().contains("response or message.content"), failure.getMessage());
    }

    @Test
    void streamingIsAskedForOnlyWhenTheRequestWantsIt() {
        for (LlmApi api : List.of(LlmApi.OLLAMA, LlmApi.OPENAI)) {
            assertFalse(new JSONObject(api.requestBody(request(api, null, null))).getBoolean("stream"),
                    api.label() + " must not stream a request that did not ask to");
            assertTrue(new JSONObject(api.requestBody(streaming(api, null))).getBoolean("stream"), api.label());
        }
    }

    @Test
    void aBlankThinkSettingSendsNoFieldAtAll() {
        // The important one. Ollama answers HTTP 400 "does not support thinking" for a model that
        // cannot, so a field sent by default would break every graph pointed at an ordinary model.
        for (String blank : new String[]{null, "", "   "}) {
            JSONObject body = new JSONObject(LlmApi.OLLAMA.requestBody(streaming(LlmApi.OLLAMA, blank)));
            assertFalse(body.has("think"), "blank must not send think, not even as false");
        }
    }

    @Test
    void trueAndFalseAreSentAsBooleansAndALevelAsItsName() {
        assertEquals(true, new JSONObject(LlmApi.OLLAMA.requestBody(streaming(LlmApi.OLLAMA, "true"))).get("think"));
        assertEquals(false, new JSONObject(LlmApi.OLLAMA.requestBody(streaming(LlmApi.OLLAMA, "False"))).get("think"));
        assertEquals("low", new JSONObject(LlmApi.OLLAMA.requestBody(streaming(LlmApi.OLLAMA, "low"))).get("think"));
    }

    @Test
    void thinkIsDroppedForAnOpenAiServerRatherThanSentWhereItIsNotUnderstood() {
        // num_ctx's reason: there is no agreed request field, so sending one invites a rejection
        // from a server that validates its input.
        assertFalse(new JSONObject(LlmApi.OPENAI.requestBody(streaming(LlmApi.OPENAI, "true"))).has("think"));
    }

    @Test
    void reasoningIsReadBackOutOfAnUnstreamedOllamaReplyInEitherShape() {
        assertEquals("Let me check.", LlmApi.OLLAMA.thinkingFrom(
                "{\"thinking\":\"Let me check.\",\"response\":\"Frank Herbert.\"}"));
        assertEquals("Let me check.", LlmApi.OLLAMA.thinkingFrom(
                "{\"message\":{\"content\":\"Frank Herbert.\",\"thinking\":\"Let me check.\"}}"));
        // A model that was not asked to think answers without the field, which is ordinary rather
        // than a server speaking the wrong protocol - unlike a missing content field.
        assertEquals("", LlmApi.OLLAMA.thinkingFrom("{\"response\":\"Frank Herbert.\"}"));
        assertEquals("", LlmApi.OPENAI.thinkingFrom(
                "{\"choices\":[{\"message\":{\"content\":\"Frank Herbert.\"}}]}"));
    }

    /** A streaming request, with an authored thinking setting. */
    private static LlmRequest streaming(LlmApi api, String think) {
        return new LlmRequest(api, "http://localhost:11434", "llama3.2", null, List.of(), false,
                "Why is the sky blue?", null, null, null, 30, true, think);
    }

    private static LlmRequest request(LlmApi api, String system, Float temperature) {
        return new LlmRequest(api, "http://localhost:11434", "llama3.2", system, "Why is the sky blue?",
                temperature, null, 30);
    }

    /** The same request, with one exchange already behind it. */
    private static LlmRequest conversation(LlmApi api) {
        return new LlmRequest(api, "http://localhost:11434", "llama3.2", "Be brief.",
                List.of(LlmMessage.user("Who wrote Dune?"), LlmMessage.assistant("Frank Herbert.")),
                true, "Why is the sky blue?", null, null, null, 30);
    }

    /** Turn one of a conversation: nothing said yet, but it is a conversation all the same. */
    private static LlmRequest firstTurn(LlmApi api) {
        return new LlmRequest(api, "http://localhost:11434", "llama3.2", null, List.of(), true,
                "Who wrote Dune?", null, null, null, 30);
    }
}

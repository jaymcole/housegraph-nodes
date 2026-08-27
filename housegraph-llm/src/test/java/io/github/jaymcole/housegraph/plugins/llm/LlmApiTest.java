package io.github.jaymcole.housegraph.plugins.llm;

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

    private static LlmRequest request(LlmApi api, String system, Float temperature) {
        return new LlmRequest(api, "http://localhost:11434", "llama3.2", system, "Why is the sky blue?",
                temperature, null, 30);
    }
}

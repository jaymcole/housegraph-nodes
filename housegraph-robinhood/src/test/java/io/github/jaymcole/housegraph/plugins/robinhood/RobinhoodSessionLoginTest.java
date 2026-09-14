package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login state machine: the four ways Robinhood's token endpoint can answer, and what this
 * library does about each.
 * <p>
 * This is the part of the library most likely to be wrong and least likely to be noticed being
 * wrong, because every failure in it looks identical from the canvas ("it won't connect"). The
 * stub lets each branch be driven deliberately.
 */
class RobinhoodSessionLoginTest {

    private StubRobinhood robinhood;

    @BeforeEach
    void setUp() throws IOException {
        robinhood = StubRobinhood.open();
        robinhood.on("GET", "/accounts/", 200, StubRobinhood.accounts("123456789"));
    }

    @AfterEach
    void tearDown() {
        robinhood.close();
    }

    @Test
    void logsInWithAPasswordAndReadsTheAccount() {
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 86400));
        RobinhoodSession session = robinhood.session();

        session.connect(StubRobinhood.credentials(), 5);

        assertTrue(session.isConnected());
        assertEquals("123456789", session.accountNumber());

        JSONObject sent = robinhood.lastCallTo("POST", "/oauth2/token/").json();
        assertEquals("password", sent.getString("grant_type"));
        assertEquals("trader@example.com", sent.getString("username"));
        assertEquals(RobinhoodApi.CLIENT_ID, sent.getString("client_id"));
        // No code is offered until Robinhood asks: a TOTP code is single-use, and spending one on
        // an account that didn't need it is how the retry then has nothing fresh to send.
        assertFalse(sent.has("mfa_code"));
    }

    @Test
    void theDeviceTokenIsTheSameOnEveryLogin() {
        // A random device token per login makes every connection look like a new phone, which asks
        // for device approval every single time - the bug this pins down.
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("a", "r", 86400));
        robinhood.session().connect(StubRobinhood.credentials(), 5);
        String first = robinhood.lastCallTo("POST", "/oauth2/token/").json().getString("device_token");

        robinhood.session().connect(StubRobinhood.credentials(), 5);
        String second = robinhood.lastCallTo("POST", "/oauth2/token/").json().getString("device_token");

        assertEquals(first, second);
        assertFalse(first.isBlank());
    }

    @Test
    void answersAnMfaRequestWithACodeFromTheSeed() {
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.mfaRequired());
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 86400));

        robinhood.session().connect(StubRobinhood.credentials(), 5);

        assertEquals(2, robinhood.callsTo("POST", "/oauth2/token/").size());
        JSONObject retry = robinhood.lastCallTo("POST", "/oauth2/token/").json();
        assertTrue(retry.has("mfa_code"), "the retry should carry a code");
        assertEquals(6, retry.getString("mfa_code").length());
    }

    @Test
    void saysWhatToDoWhenMfaIsWantedAndNoSeedWasGiven() {
        robinhood.only("POST", "/oauth2/token/", 200, StubRobinhood.mfaRequired());
        RobinhoodCredentials noSeed = new RobinhoodCredentials("trader@example.com", "hunter2", null);

        String message = assertThrows(RobinhoodException.class,
                () -> robinhood.session().connect(noSeed, 5)).getMessage();

        assertTrue(message.contains("MFA Secret"), message);
        assertTrue(message.contains("two-factor"), message);
    }

    @Test
    void doesNotKeepResendingARejectedCode() {
        // A wrong code sent again is a wrong code. Stopping after one retry keeps a bad seed from
        // becoming a password-guessing loop against the user's own account.
        robinhood.only("POST", "/oauth2/token/", 200, StubRobinhood.mfaRequired());

        String message = assertThrows(RobinhoodException.class,
                () -> robinhood.session().connect(StubRobinhood.credentials(), 5)).getMessage();

        assertTrue(message.contains("rejected the two-factor code"), message);
        assertEquals(2, robinhood.callsTo("POST", "/oauth2/token/").size());
    }

    @Test
    void walksTheDeviceApprovalWorkflowAndRetriesTheLogin() {
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.verificationWorkflow("wf-1"));
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 86400));
        robinhood.on("POST", "/pathfinder/user_machine/", 200, "{\"id\": \"machine-1\"}");
        robinhood.on("GET", "/pathfinder/inquiries/machine-1/user_view/", 200,
                sheriffChallenge("challenge-1", "prompt"));
        robinhood.on("GET", "/push/challenge-1/get_prompts_status/", 200,
                "{\"challenge_status\": \"validated\"}");
        robinhood.on("POST", "/pathfinder/inquiries/machine-1/user_view/", 200, "{}");

        RobinhoodSession session = robinhood.session();
        session.connect(StubRobinhood.credentials(), 5);

        assertTrue(session.isConnected());
        JSONObject started = robinhood.lastCallTo("POST", "/pathfinder/user_machine/").json();
        assertEquals("suv", started.getString("flow"));
        assertEquals("wf-1", started.getJSONObject("input").getString("workflow_id"));
        // The workflow is told to continue only after the prompt came back validated, and the token
        // is only asked for again after that.
        assertEquals("continue",
                robinhood.lastCallTo("POST", "/pathfinder/inquiries/machine-1/user_view/")
                        .json().getJSONObject("user_input").getString("status"));
        assertEquals(2, robinhood.callsTo("POST", "/oauth2/token/").size());
    }

    @Test
    void givesUpOnAnApprovalNobodyTapped() {
        robinhood.only("POST", "/oauth2/token/", 200, StubRobinhood.verificationWorkflow("wf-1"));
        robinhood.on("POST", "/pathfinder/user_machine/", 200, "{\"id\": \"machine-1\"}");
        robinhood.on("GET", "/pathfinder/inquiries/machine-1/user_view/", 200,
                sheriffChallenge("challenge-1", "prompt"));
        robinhood.on("GET", "/push/challenge-1/get_prompts_status/", 200,
                "{\"challenge_status\": \"issued\"}");

        // Zero seconds of patience: the wait itself isn't what's being tested, the giving up is.
        String message = assertThrows(RobinhoodException.class,
                () -> robinhood.session().connect(StubRobinhood.credentials(), 0)).getMessage();

        assertTrue(message.contains("not approved"), message);
        assertTrue(message.contains("Approval Timeout"), message);
    }

    @Test
    void saysWhatToDoAboutAChallengeItCannotAnswer() {
        // A graph cannot read a text message. The only useful thing to do is say so and name the fix.
        robinhood.only("POST", "/oauth2/token/", 200,
                "{\"challenge\": {\"id\": \"c-1\", \"type\": \"sms\"}}");

        String message = assertThrows(RobinhoodException.class,
                () -> robinhood.session().connect(StubRobinhood.credentials(), 5)).getMessage();

        assertTrue(message.contains("sms"), message);
        assertTrue(message.contains("authenticator app"), message);
    }

    @Test
    void reportsAWrongPasswordInRobinhoodsOwnWords() {
        robinhood.only("POST", "/oauth2/token/", 400,
                "{\"detail\": \"Unable to log in with provided credentials.\"}");

        String message = assertThrows(RobinhoodException.class,
                () -> robinhood.session().connect(StubRobinhood.credentials(), 5)).getMessage();

        assertTrue(message.contains("Unable to log in with provided credentials"), message);
    }

    @Test
    void refusesToConnectWithHalfACredential() {
        // Caught before any call goes out: an empty password posted to the token endpoint is a
        // failed login attempt on the user's real account.
        assertThrows(RobinhoodException.class, () -> robinhood.session()
                .connect(new RobinhoodCredentials("", "hunter2", null), 5));
        assertThrows(RobinhoodException.class, () -> robinhood.session()
                .connect(new RobinhoodCredentials("trader@example.com", " ", null), 5));
        assertTrue(robinhood.callsTo("POST", "/oauth2/token/").isEmpty());
    }

    @Test
    void refreshesTheTokenRatherThanLoggingInAgain() {
        // The first token is already inside the refresh margin when it arrives, so the very next
        // call has to renew it - and must do so with the refresh grant, not the password.
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 60));
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-2", "refresh-2", 86400));

        RobinhoodSession session = robinhood.session();
        session.connect(StubRobinhood.credentials(), 5);

        JSONObject refresh = robinhood.callsTo("POST", "/oauth2/token/").get(1).json();
        assertEquals("refresh_token", refresh.getString("grant_type"));
        assertEquals("refresh-1", refresh.getString("refresh_token"));
        // And the call that prompted the refresh went out with the new token, not the expiring one.
        assertEquals("Bearer access-2", robinhood.lastCallTo("GET", "/accounts/").authorization());
    }

    @Test
    void everyCallAfterLoginCarriesTheToken() {
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 86400));

        robinhood.session().connect(StubRobinhood.credentials(), 5);

        assertEquals("Bearer access-1", robinhood.lastCallTo("GET", "/accounts/").authorization());
    }

    @Test
    void disconnectingHandsTheTokenBackAndForgetsIt() {
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 86400));
        robinhood.on("POST", "/oauth2/revoke_token/", 200, "{}");
        RobinhoodSession session = robinhood.session();
        session.connect(StubRobinhood.credentials(), 5);

        session.disconnect();

        assertFalse(session.isConnected());
        assertEquals("refresh-1", robinhood.lastCallTo("POST", "/oauth2/revoke_token/")
                .json().getString("token"));
    }

    @Test
    void disconnectingWorksEvenWhenRobinhoodWillNotTakeTheTokenBack() {
        // Forgetting the token locally is the half that must happen; a node stuck showing
        // "connected" because a revoke 500'd would have no way out.
        robinhood.on("POST", "/oauth2/token/", 200, StubRobinhood.tokens("access-1", "refresh-1", 86400));
        robinhood.on("POST", "/oauth2/revoke_token/", 500, "{\"detail\": \"nope\"}");
        RobinhoodSession session = robinhood.session();
        session.connect(StubRobinhood.credentials(), 5);

        session.disconnect();

        assertFalse(session.isConnected());
    }

    /** The shape the approval workflow reports a pending challenge in. */
    private static String sheriffChallenge(String challengeId, String type) {
        return new JSONObject().put("type_context", new JSONObject()
                .put("context", new JSONObject()
                        .put("sheriff_challenge", new JSONObject()
                                .put("id", challengeId)
                                .put("type", type)
                                .put("status", "issued"))))
                .toString();
    }
}

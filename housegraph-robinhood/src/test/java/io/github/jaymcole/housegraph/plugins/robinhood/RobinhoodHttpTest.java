package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence a failure puts on a node's status line.
 * <p>
 * Robinhood reports a refusal in four different shapes depending on which endpoint refused, and the
 * difference between reading them and not is the difference between "You can only purchase 1.00
 * shares of this security" and "HTTP 400" - one of which tells its reader what to do.
 */
class RobinhoodHttpTest {

    private static String describe(int status, String body) {
        return RobinhoodHttp.describe(status, new JSONObject(body), body, "POST",
                "https://api.robinhood.com/orders/");
    }

    @Test
    void readsARejectedOrdersDetail() {
        String message = describe(400, "{\"detail\": \"You can only purchase 1.00 shares\"}");

        assertTrue(message.contains("You can only purchase 1.00 shares"), message);
    }

    @Test
    void readsAnOAuthErrorDescription() {
        String message = describe(400,
                "{\"error\": \"invalid_grant\", \"error_description\": \"That password is incorrect\"}");

        assertTrue(message.contains("That password is incorrect"), message);
    }

    @Test
    void readsAPerFieldValidationError() {
        String message = describe(400, "{\"quantity\": [\"Order quantity must be positive\"]}");

        assertTrue(message.contains("Order quantity must be positive"), message);
        // Naming the field matters when the complaint alone doesn't say which one it is about.
        assertTrue(message.contains("quantity"), message);
    }

    @Test
    void readsNonFieldErrorsWithoutPrefixingThem() {
        String message = describe(400, "{\"non_field_errors\": [\"This order is not permitted\"]}");

        assertTrue(message.contains("This order is not permitted"), message);
        assertTrue(!message.contains("non_field_errors"), message);
    }

    @Test
    void explainsAnExpiredSessionRatherThanSayingFourOhOne() {
        String message = describe(401, "{}");

        assertTrue(message.contains("no longer valid"), message);
        assertTrue(message.contains("Connect"), message);
    }

    @Test
    void namesRateLimitingForWhatItIs() {
        String message = describe(429,
                "{\"detail\": \"Request was throttled. Expected available in 3 seconds.\"}");

        assertTrue(message.contains("rate-limiting"), message);
        assertTrue(message.contains("3 seconds"), message);
    }

    @Test
    void fallsBackToTheStatusAndPathForAShapeItHasNotSeen() {
        String message = RobinhoodHttp.describe(503, new JSONObject(), "<html>down for maintenance</html>",
                "GET", "https://api.robinhood.com/accounts/?nonzero=true");

        assertTrue(message.contains("503"), message);
        assertTrue(message.contains("/accounts/"), message);
        // The query string is dropped from messages, on principle: Robinhood's carry no secrets
        // today, and a message-building path that quotes URLs wholesale is one endpoint away from
        // putting one on screen.
        assertTrue(!message.contains("nonzero"), message);
    }
}

package io.github.jaymcole.housegraph.plugins.alpaca;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence a failed call puts on a node's status line.
 * <p>
 * <b>This is tested because the message is the whole of what a person gets.</b> A node that reported
 * "HTTP 403" would be telling its reader nothing they could act on, and the two most common 403s in
 * this library mean completely different things — one is "your keys are for the other account", the
 * other is "you are asking for a data feed you don't subscribe to". Each assertion below is on the
 * fix being in the message, not just on the failure being reported.
 */
class AlpacaHttpTest {

    private static final String TRADING = "https://paper-api.alpaca.markets/v2/orders";
    private static final String DATA = "https://data.alpaca.markets/v2/stocks/snapshots?symbols=AAPL";

    @Test
    void rejectedKeysNameTheMostLikelyCause() {
        // Paper keys on the live host, or the reverse. It is the first mistake everybody makes, and
        // it is also the thing that makes the Paper Trading switch safe.
        String message = AlpacaHttp.describe(401, "{\"message\":\"access key verification failed\"}",
                "GET", TRADING);

        assertTrue(message.contains("paper keys only work"), message);
        assertTrue(message.contains("Paper Trading"), message);
    }

    @Test
    void aTradingForbiddenReadsAsAKeyProblemAndADataOneAsASubscription() {
        String trading = AlpacaHttp.describe(403, "{\"message\":\"forbidden\"}", "POST", TRADING);
        assertTrue(trading.contains("API keys"), trading);

        String data = AlpacaHttp.describe(403,
                "{\"message\":\"subscription does not permit querying recent SIP data\"}",
                "GET", DATA);
        assertTrue(data.contains("market-data subscription"), data);
        assertTrue(data.contains("iex"), data);
    }

    @Test
    void alpacasOwnMessageIsKeptForARefusedOrder() {
        // Alpaca's 422 message is the only place the reason appears - an order refused here never
        // becomes an order at all, so there is nothing to look up afterwards.
        String message = AlpacaHttp.describe(422,
                "{\"code\":40310000,\"message\":\"insufficient buying power\"}", "POST", TRADING);

        assertTrue(message.contains("insufficient buying power"), message);
    }

    @Test
    void rateLimitingSaysWhatToDoAboutIt() {
        String message = AlpacaHttp.describe(429, "{\"message\":\"too many requests\"}",
                "GET", TRADING);

        assertTrue(message.contains("200 requests a minute"), message);
        assertTrue(message.contains("Slow the trigger"), message);
    }

    @Test
    void aBodyThatIsNotJsonStillProducesSomethingReadable() {
        String message = AlpacaHttp.describe(502, "<html>Bad Gateway</html>", "GET", TRADING);

        assertTrue(message.contains("502"), message);
        assertTrue(message.contains("/v2/orders"), message);
    }

    @Test
    void theQueryStringIsStrippedOutOfAMessage() {
        // Not because it carries a secret - Alpaca's don't - but because a status line is one line.
        String message = AlpacaHttp.describe(500, "", "GET", DATA);

        assertTrue(message.contains("/v2/stocks/snapshots"), message);
        assertTrue(!message.contains("symbols=AAPL"), message);
    }

    @Test
    void anEmptyBodyIsSaidRatherThanShownAsNothing() {
        String message = AlpacaHttp.describe(500, "", "GET", TRADING);
        assertTrue(message.contains("empty body"), message);
    }
}

package io.github.jaymcole.housegraph.plugins.robinhood;

/**
 * Where Robinhood's endpoints are and what this library sends them.
 *
 * <h2>None of this is a published API</h2>
 * Robinhood has no documented, supported API for retail customers. What is here is the private
 * interface its own apps use, written down from the open-source clients that have tracked it for
 * years (robin_stocks and its relatives). <b>It can change without notice, and when it does, nodes
 * in this library stop working.</b> The whole of what could break is collected in this one class
 * and in {@link Orders} on purpose: a URL that moved or a payload field that was renamed is then an
 * edit here rather than an archaeology exercise across nine node classes. The reasoning, and what
 * it means for anybody installing this, is in {@code docs/design/robinhood-unofficial-api.md}.
 *
 * <h2>The client id is Robinhood's own, and public</h2>
 * {@link #CLIENT_ID} is the OAuth client id Robinhood's web app ships to every browser that loads
 * it. It is not a credential, not a registration, and not anything issued to this library — it is a
 * constant every unofficial client uses because the token endpoint will not answer without it.
 */
public final class RobinhoodApi {

    /** The API host, absent a test pointing somewhere else. */
    public static final String BASE_URL = "https://api.robinhood.com";

    /** The OAuth client id Robinhood's own web app uses. Public, not secret - see the class note. */
    public static final String CLIENT_ID = "c82SH0WZOsabOXGP2sxqcj34FxkvfnWRZBKlBjFS";

    /**
     * Sent as {@code X-Robinhood-API-Version}. Robinhood serves older clients for a long time and
     * this is what the unofficial clients pin; it is not read by anything here, only echoed.
     */
    public static final String API_VERSION = "1.431.4";

    /**
     * How long an access token is asked to live. Robinhood may answer with less, which is why
     * {@link RobinhoodSession} trusts the {@code expires_in} it gets back rather than this.
     */
    public static final int TOKEN_LIFETIME_SECONDS = 86_400;

    private RobinhoodApi() {
    }

    /** The OAuth token endpoint - password grant, refresh grant, and the MFA retry. */
    public static String token(String baseUrl) {
        return baseUrl + "/oauth2/token/";
    }

    /** Where a refresh token is handed back so it stops working. */
    public static String revoke(String baseUrl) {
        return baseUrl + "/oauth2/revoke_token/";
    }

    /** The brokerage accounts this login can trade - this library uses the first one. */
    public static String accounts(String baseUrl) {
        return baseUrl + "/accounts/";
    }

    /** Equity, market value and the day's change, for one account number. */
    public static String portfolio(String baseUrl, String accountNumber) {
        return baseUrl + "/portfolios/" + accountNumber + "/";
    }

    /** Holdings. {@code nonzero=true} leaves out everything sold down to nothing. */
    public static String positions(String baseUrl) {
        return baseUrl + "/positions/?nonzero=true";
    }

    /** One symbol's tradable instrument - the URL an order has to name. */
    public static String instrumentBySymbol(String baseUrl, String symbol) {
        return baseUrl + "/instruments/?symbol=" + symbol;
    }

    /** Last trade, bid and ask for one symbol. */
    public static String quote(String baseUrl, String symbol) {
        return baseUrl + "/marketdata/quotes/" + symbol + "/";
    }

    /**
     * Several symbols' quotes in one call. Reading a portfolio's worth one symbol at a time is what
     * gets an account rate-limited, so anything that prices a list uses this.
     */
    public static String quotes(String baseUrl, String commaSeparatedSymbols) {
        return baseUrl + "/marketdata/quotes/?symbols=" + commaSeparatedSymbols;
    }

    /** POST to place; GET for the most recent orders. */
    public static String orders(String baseUrl) {
        return baseUrl + "/orders/";
    }

    /** One order, by the id a placement answered with. */
    public static String order(String baseUrl, String orderId) {
        return baseUrl + "/orders/" + orderId + "/";
    }

    /** POST to ask Robinhood to cancel an order that has not finished. */
    public static String cancelOrder(String baseUrl, String orderId) {
        return baseUrl + "/orders/" + orderId + "/cancel/";
    }

    // --- Device approval (the "is this you?" prompt in the phone app) ----------------------------

    /** Starts the approval workflow the token endpoint asked for. */
    public static String userMachine(String baseUrl) {
        return baseUrl + "/pathfinder/user_machine/";
    }

    /** Reads, and then advances, the state of one approval workflow. */
    public static String inquiry(String baseUrl, String machineId) {
        return baseUrl + "/pathfinder/inquiries/" + machineId + "/user_view/";
    }

    /** Whether the person has tapped Approve in the app yet. */
    public static String promptStatus(String baseUrl, String challengeId) {
        return baseUrl + "/push/" + challengeId + "/get_prompts_status/";
    }

    /** Where a code from an SMS or email challenge would be sent back. */
    public static String challengeResponse(String baseUrl, String challengeId) {
        return baseUrl + "/challenge/" + challengeId + "/respond/";
    }
}

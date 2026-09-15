package io.github.jaymcole.housegraph.plugins.alpaca;

import org.json.JSONObject;

/**
 * The numbers a person opens a broker's app to see: what the account is worth, what is left to
 * trade with, and whether it is allowed to trade at all.
 * <p>
 * All of it comes from one call — {@code GET /v2/account} — which is also the call a connect makes
 * to check that the API keys work. So the Alpaca Account node already knows most of this the moment
 * it connects; the Account Summary node exists to read it <em>again</em>, on a schedule something
 * upstream decides, because buying power moves during the day.
 *
 * <h2>The two blocked flags are worth branching on</h2>
 * An account can be live, funded, and still refuse every order — a transfer under review, a
 * restriction after a pattern-day-trading breach, an account the holder suspended themselves.
 * {@link #tradingBlocked()} says so <em>before</em> an order is written, which is a better place to
 * find out than a 422 on the order itself.
 *
 * @param accountNumber  the brokerage account number this describes
 * @param status         Alpaca's account status, normally {@code ACTIVE}
 * @param currency       the account currency, normally {@code USD}
 * @param equity         cash plus the market value of every holding
 * @param lastEquity     what equity was at the previous close, for the day's change
 * @param cash           the cash balance
 * @param buyingPower    what an order can spend right now, margin included if the account has it
 * @param longMarketValue  the market value of the long holdings
 * @param shortMarketValue the market value of the short holdings, which Alpaca reports negative
 * @param tradingBlocked whether the account is barred from placing orders
 * @param accountBlocked whether the account is barred from activity altogether
 * @param shortingEnabled whether the account may sell short, or null if Alpaca didn't say
 */
public record AccountSummary(String accountNumber,
                             String status,
                             String currency,
                             Double equity,
                             Double lastEquity,
                             Double cash,
                             Double buyingPower,
                             Double longMarketValue,
                             Double shortMarketValue,
                             boolean tradingBlocked,
                             boolean accountBlocked,
                             Boolean shortingEnabled) {

    /**
     * Today's change in equity, or null when either end of the subtraction is missing. Positive is
     * up.
     *
     * @return the day's change in dollars, or null
     */
    public Double dayChange() {
        return (equity == null || lastEquity == null) ? null : equity - lastEquity;
    }

    /**
     * Today's change as a fraction of the previous close — 0.012 is up 1.2%. Null when equity at
     * the previous close was missing or zero.
     *
     * @return the day's change as a fraction, or null
     */
    public Double dayChangePercent() {
        Double change = dayChange();
        if (change == null || lastEquity == null || lastEquity == 0) {
            return null;
        }
        return change / lastEquity;
    }

    /** Whether the account can place an order at all right now. */
    public boolean canTrade() {
        return !tradingBlocked && !accountBlocked;
    }

    /**
     * Builds the summary from {@code GET /v2/account}.
     * <p>
     * Deliberately absent: {@code pattern_day_trader}, {@code daytrade_count} and
     * {@code daytrading_buying_power}, which Alpaca removed from this response in July 2026 when US
     * intraday margin rules changed. Reading them would have given every graph a permanently null
     * port.
     *
     * @param body the account
     * @return the summary
     */
    static AccountSummary from(JSONObject body) {
        return new AccountSummary(
                Json.string(body, "account_number"),
                Json.string(body, "status"),
                Json.stringOr(body, "currency", "USD"),
                Json.number(body, "equity"),
                Json.number(body, "last_equity"),
                Json.number(body, "cash"),
                Json.number(body, "buying_power"),
                Json.number(body, "long_market_value"),
                Json.number(body, "short_market_value"),
                Json.bool(body, "trading_blocked", false),
                Json.bool(body, "account_blocked", false),
                Json.boolOrNull(body, "shorting_enabled"));
    }
}

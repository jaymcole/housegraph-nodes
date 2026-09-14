package io.github.jaymcole.housegraph.plugins.robinhood;

import org.json.JSONObject;

/**
 * The numbers a person opens the app to see: what the account is worth, and what is left to trade
 * with.
 * <p>
 * It is assembled from <b>two</b> Robinhood endpoints, which is worth knowing because they mean
 * different things. {@code /accounts/} holds the cash side — {@code buying_power} is what an order
 * can actually spend, including margin if the account has it. {@code /portfolios/} holds the
 * valuation side — {@code equity} is cash plus the market value of everything held. Neither alone
 * answers "how am I doing", so {@link RobinhoodSession#accountSummary()} reads both.
 *
 * @param accountNumber       the brokerage account number this describes
 * @param buyingPower         what is available to spend on a buy right now, or null if unreported
 * @param cash                settled and unsettled cash, or null
 * @param equity              cash plus the market value of every holding, or null
 * @param marketValue         the market value of the holdings alone, or null
 * @param previousCloseEquity what equity was at yesterday's close, or null
 */
public record AccountSummary(String accountNumber,
                             Double buyingPower,
                             Double cash,
                             Double equity,
                             Double marketValue,
                             Double previousCloseEquity) {

    /**
     * Today's change in equity, or null when either end of the subtraction is missing. Positive is
     * up.
     *
     * @return the day's change, or null
     */
    public Double dayChange() {
        return (equity == null || previousCloseEquity == null) ? null : equity - previousCloseEquity;
    }

    /**
     * Builds the summary from the two bodies.
     *
     * @param accountNumber the account number, which {@code account} may not repeat
     * @param account       one result of {@code /accounts/}
     * @param portfolio     the body of {@code /portfolios/<number>/}, or null if it could not be read
     * @return the summary
     */
    static AccountSummary from(String accountNumber, JSONObject account, JSONObject portfolio) {
        // extended_hours_equity is what the app shows outside regular hours; equity stops moving at
        // the close, so preferring it keeps an evening reading honest. Same rule as Quote#price.
        Double equity = Json.number(portfolio, "extended_hours_equity");
        if (equity == null) {
            equity = Json.number(portfolio, "equity");
        }
        Double marketValue = Json.number(portfolio, "extended_hours_market_value");
        if (marketValue == null) {
            marketValue = Json.number(portfolio, "market_value");
        }
        return new AccountSummary(
                Json.stringOr(account, "account_number", accountNumber),
                Json.number(account, "buying_power"),
                Json.number(account, "cash"),
                equity,
                marketValue,
                Json.number(portfolio, "adjusted_equity_previous_close"));
    }
}

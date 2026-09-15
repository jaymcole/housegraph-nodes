package io.github.jaymcole.housegraph.plugins.alpaca;

/**
 * One pair of Alpaca API keys, and which account they are for.
 *
 * <h2>Paper and live keys are different keys, and that is a safety feature</h2>
 * Alpaca issues a separate key/secret pair for the paper account and the live one, and each pair
 * works only against its own host. <b>So {@link #paper()} is not a "please be careful" flag — it
 * chooses the host, and the keys themselves decide whether the call is accepted.</b> A graph
 * carrying paper keys with Paper Trading switched off does not place a real order; it fails to
 * connect, with Alpaca saying the key is invalid. The mistake this library most wants to make
 * impossible is the one where a test graph turns out to have been trading real money, and Alpaca's
 * own key separation is what makes it impossible rather than merely unlikely.
 *
 * <h2>Nothing here is written down anywhere</h2>
 * These values arrive from the node's ports on every run and are held only for as long as the
 * session is connected. Both are secret inputs, so HouseGraph never writes them to a save file, and
 * nothing in this library writes them to disk or repeats them in a log line or an exception
 * message. The only place a key persists is HouseGraph's own encrypted secret store, which is what
 * the Secret Loader node reads from.
 *
 * @param apiKeyId  the key id, which Alpaca shows in full (it is an identifier, not the secret)
 * @param secretKey the secret key, which Alpaca shows exactly once when the pair is created
 * @param paper     true for the paper-trading account, false for the live brokerage account
 */
public record AlpacaCredentials(String apiKeyId, String secretKey, boolean paper) {

    /**
     * Checks that both halves are there before anything is sent.
     *
     * @return this, so it can be validated inline
     * @throws AlpacaException naming the field that is missing
     */
    public AlpacaCredentials validate() {
        if (apiKeyId == null || apiKeyId.isBlank()) {
            throw new AlpacaException("API Key ID is empty. Generate a key pair in Alpaca's dashboard "
                    + "- under Paper Trading for a paper account, which is what this node uses by "
                    + "default - and wire it in from a Secret Loader node.");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new AlpacaException("API Secret Key is empty. Alpaca shows the secret once, when the "
                    + "key pair is created; if it wasn't kept, generate a new pair.");
        }
        return this;
    }

    /** The trading host these keys belong to. */
    public String tradingHost() {
        return AlpacaApi.tradingHost(paper);
    }

    /** "paper" or "live", for a status line or a log message. */
    public String accountKind() {
        return paper ? "paper" : "live";
    }

    /**
     * Never prints the keys. A record's generated {@code toString} would put the secret into any log
     * line or exception message that interpolated a credentials object, which is the kind of leak
     * that is only noticed once it is in somebody's log file.
     */
    @Override
    public String toString() {
        return "AlpacaCredentials[" + accountKind() + "]";
    }
}

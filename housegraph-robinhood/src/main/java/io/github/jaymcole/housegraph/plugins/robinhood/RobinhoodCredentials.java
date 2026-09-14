package io.github.jaymcole.housegraph.plugins.robinhood;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * What it takes to log in, and nothing else.
 * <p>
 * <b>This is never stored, logged or put in a message.</b> It is built from the node's inputs at
 * the moment a Connect runs and held by the live {@link RobinhoodSession} only so that an expired
 * session can log in again without a person present. HouseGraph does not write a
 * {@code markSecret()} input's value to a save file, so nothing here reaches disk through this
 * library either — which is also why a reloaded graph has to be given its password again before it
 * can connect. That trade is argued in {@code docs/design/robinhood-unofficial-api.md}.
 *
 * @param username the Robinhood login, usually an email address
 * @param password the Robinhood password
 * @param mfaSeed  the base32 two-factor seed, or null/blank to be asked for approval in the app
 *                 instead
 */
public record RobinhoodCredentials(String username, String password, String mfaSeed) {

    /**
     * Checks that the two mandatory halves are there.
     *
     * @return this, so it can be validated inline
     * @throws RobinhoodException naming the empty field
     */
    public RobinhoodCredentials validate() {
        if (username == null || username.isBlank()) {
            throw new RobinhoodException("Username is empty - it is the email address the Robinhood "
                    + "account is under.");
        }
        if (password == null || password.isBlank()) {
            throw new RobinhoodException("Password is empty.");
        }
        return this;
    }

    /** Whether a two-factor code can be generated without a person. */
    public boolean hasMfaSeed() {
        return mfaSeed != null && !mfaSeed.isBlank();
    }

    /**
     * The six-digit code for right now, or null when no seed was given.
     *
     * @return the code, or null
     * @throws RobinhoodException if a seed was given but is not base32
     */
    public String mfaCode() {
        return hasMfaSeed() ? Totp.now(mfaSeed) : null;
    }

    /**
     * A stable id for this machine-and-login pair, which Robinhood uses to recognise a device it
     * has already been told to trust.
     * <p>
     * <b>It has to be the same every time or device approval is asked for on every single login.</b>
     * A random UUID per session — the obvious implementation — makes each connection look like a
     * new phone, which is exactly the thing the approval prompt exists to catch. Deriving it from
     * the username instead keeps it stable across restarts without this library writing anything to
     * disk, and keeps two different logins on one machine apart.
     *
     * @return a UUID, the same one for the same username, every time
     */
    public String deviceToken() {
        return UUID.nameUUIDFromBytes(("housegraph-robinhood:" + username.trim().toLowerCase())
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}

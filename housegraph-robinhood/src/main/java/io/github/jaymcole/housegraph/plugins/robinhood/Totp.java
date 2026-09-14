package io.github.jaymcole.housegraph.plugins.robinhood;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

/**
 * The six digits an authenticator app would be showing right now, from the same seed it was set up
 * with — RFC 6238 TOTP over HMAC-SHA1, 30-second steps, which is what Robinhood issues.
 *
 * <h2>Why a node generates these rather than asking for one</h2>
 * Robinhood's password grant answers {@code {"mfa_required": true}} and expects the code on the
 * retry. A node that could only <em>ask</em> for it would work exactly once — while somebody is
 * sitting at the machine — and a graph that reconnects at 4am would simply stop there. Holding the
 * seed is what makes an unattended graph possible at all.
 * <p>
 * <b>That is a real trade and this library does not pretend otherwise.</b> The seed is the second
 * factor: whatever holds it can mint codes forever, so storing it next to the password collapses
 * two factors into one. It is worth it for a machine that trades on its own and not worth it for
 * one a person drives by hand — which is why {@code MFA Secret} is optional, and a node with the
 * field left empty asks Robinhood for app approval instead
 * (see {@link RobinhoodSession#connect}).
 *
 * <h2>The seed</h2>
 * Base32, as Robinhood shows it when setting up two-factor authentication ("Enter this code
 * manually"), with spaces and padding tolerated because that is how it is copied. Anything that is
 * not base32 throws here rather than at the token endpoint, so the message names the field the user
 * has to fix instead of Robinhood's generic "invalid code".
 */
public final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;

    private Totp() {
    }

    /**
     * The code for {@code seed} at this moment.
     *
     * @param seed the base32 TOTP seed, as shown when two-factor authentication was set up
     * @return six digits, left-padded with zeroes
     * @throws RobinhoodException if {@code seed} is not base32
     */
    public static String now(String seed) {
        return at(seed, Instant.now());
    }

    /**
     * The code for {@code seed} at {@code instant} — {@link #now} with the clock handed in, which is
     * what makes this testable against RFC 6238's published vectors.
     *
     * @param seed    the base32 TOTP seed
     * @param instant the moment to generate for
     * @return six digits, left-padded with zeroes
     * @throws RobinhoodException if {@code seed} is not base32
     */
    public static String at(String seed, Instant instant) {
        byte[] key = decodeBase32(seed);
        long counter = instant.getEpochSecond() / STEP_SECONDS;
        byte[] digest = hmacSha1(key, ByteBuffer.allocate(Long.BYTES).putLong(counter).array());

        // RFC 4226 dynamic truncation: the low nibble of the last byte picks where to read from.
        int offset = digest[digest.length - 1] & 0x0F;
        int binary = ((digest[offset] & 0x7F) << 24)
                | ((digest[offset + 1] & 0xFF) << 16)
                | ((digest[offset + 2] & 0xFF) << 8)
                | (digest[offset + 3] & 0xFF);
        return String.format("%0" + DIGITS + "d", binary % 1_000_000);
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is required of every Java platform, so this is unreachable rather than
            // handled - but an empty seed reaches init() as a zero-length key, which does throw.
            throw new RobinhoodException("Could not generate a two-factor code: " + e.getMessage(), e);
        }
    }

    /**
     * Base32 (RFC 4648) without the alphabet's padding, spaces or case mattering — all three of
     * which appear in a seed copied off a phone screen.
     */
    static byte[] decodeBase32(String seed) {
        if (seed == null || seed.isBlank()) {
            throw new RobinhoodException("The MFA Secret is empty - paste the base32 code Robinhood "
                    + "showed when you set up two-factor authentication.");
        }
        String cleaned = seed.replace(" ", "").replace("-", "").replace("=", "").toUpperCase();
        int bits = 0;
        int accumulator = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int written = 0;
        for (int index = 0; index < cleaned.length(); index++) {
            int value = BASE32.indexOf(cleaned.charAt(index));
            if (value < 0) {
                throw new RobinhoodException("The MFA Secret is not a valid base32 code: '"
                        + cleaned.charAt(index) + "' is not one of A-Z or 2-7. Paste the code "
                        + "Robinhood showed when you set up two-factor authentication, not a "
                        + "six-digit code from the app.");
            }
            accumulator = (accumulator << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[written++] = (byte) (accumulator >> bits);
            }
        }
        if (written == 0) {
            throw new RobinhoodException("The MFA Secret is too short to be a base32 code.");
        }
        byte[] key = new byte[written];
        System.arraycopy(out, 0, key, 0, written);
        return key;
    }
}

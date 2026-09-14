package io.github.jaymcole.housegraph.plugins.robinhood;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-factor codes, against RFC 6238's own published vectors.
 * <p>
 * Worth testing exactly because it is unfalsifiable in use: a wrong implementation looks like
 * Robinhood rejecting the code, which looks like a mistyped seed, which is the first thing anybody
 * would blame. The RFC's vectors settle it here instead.
 * <p>
 * The seed is the RFC's ASCII {@code "12345678901234567890"} written as base32.
 */
class TotpTest {

    private static final String RFC_SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesTheRfcVectorAtFiftyNineSeconds() {
        assertEquals("287082", Totp.at(RFC_SEED, Instant.ofEpochSecond(59)));
    }

    @Test
    void matchesTheRfcVectorAtOneOneOneOneOneOneOneOneZeroNine() {
        assertEquals("081804", Totp.at(RFC_SEED, Instant.ofEpochSecond(1111111109L)));
    }

    @Test
    void matchesTheRfcVectorAtTwoBillion() {
        assertEquals("005924", Totp.at(RFC_SEED, Instant.ofEpochSecond(1234567890L)));
        assertEquals("279037", Totp.at(RFC_SEED, Instant.ofEpochSecond(2000000000L)));
    }

    @Test
    void holdsTheSameCodeForAWholeThirtySecondStep() {
        // The window matters: a code generated at :29 has to still be the one Robinhood expects at
        // :30 minus a millisecond, or a login intermittently fails for no visible reason.
        assertEquals(Totp.at(RFC_SEED, Instant.ofEpochSecond(1234567890L)),
                Totp.at(RFC_SEED, Instant.ofEpochSecond(1234567890L + 29)));
        assertEquals("590587", Totp.at(RFC_SEED, Instant.ofEpochSecond(1234567890L + 30)));
    }

    @Test
    void acceptsASeedCopiedOffAScreen() {
        // Spaces, lower case and padding are how a seed actually arrives; all three mean the same
        // code, and none of them should be a login failure.
        String expected = Totp.at(RFC_SEED, Instant.ofEpochSecond(59));
        assertEquals(expected, Totp.at("gezd gnbv gy3t qojq gezd gnbv gy3t qojq", Instant.ofEpochSecond(59)));
        assertEquals(expected, Totp.at("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ====", Instant.ofEpochSecond(59)));
    }

    @Test
    void refusesSomethingThatIsNotASeedAndSaysWhy() {
        // The mistake this catches is pasting the six-digit code instead of the seed - "1" and "0"
        // are not in the base32 alphabet, so it is catchable, and the message says so.
        RobinhoodException thrown =
                assertThrows(RobinhoodException.class, () -> Totp.now("104857"));
        assertTrue(thrown.getMessage().contains("not a valid base32"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("six-digit"), thrown.getMessage());
    }

    @Test
    void refusesAnEmptySeed() {
        assertThrows(RobinhoodException.class, () -> Totp.now(""));
        assertThrows(RobinhoodException.class, () -> Totp.now(null));
    }

    @Test
    void alwaysProducesSixDigits() {
        // Left-padding is the easy thing to get wrong: one login in ten would send five digits.
        for (int step = 0; step < 500; step++) {
            String code = Totp.at(RFC_SEED, Instant.ofEpochSecond(step * 30L));
            assertEquals(6, code.length(), "step " + step + " gave " + code);
            assertTrue(code.chars().allMatch(Character::isDigit), code);
        }
    }
}

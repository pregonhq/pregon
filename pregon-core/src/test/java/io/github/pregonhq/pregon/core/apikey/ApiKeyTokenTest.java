package io.github.pregonhq.pregon.core.apikey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyTokenTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void generatedKeysHaveTheDocumentedFormatAndParseBack() {
        ApiKeyToken token = ApiKeyToken.generate(random);

        assertTrue(token.value().matches("pgn_[0-9A-Za-z]{8}_[0-9A-Za-z]{43}"), token.value());
        ApiKeyToken parsed = ApiKeyToken.parse(token.value()).orElseThrow();
        assertEquals(token.id(), parsed.id());
        assertTrue(java.util.Arrays.equals(token.secretHash(), parsed.secretHash()));
    }

    @Test
    void generatedKeysDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertTrue(seen.add(ApiKeyToken.generate(random).value()));
        }
    }

    @Test
    void toStringNeverRevealsTheSecret() {
        ApiKeyToken token = ApiKeyToken.generate(random);

        assertEquals("pgn_" + token.id() + "_***", token.toString());
        assertFalse(token.toString().contains(token.value().substring(13)));
    }

    @Test
    void theStoredHashIsTheSha256OfTheSecret() throws Exception {
        ApiKeyToken token = ApiKeyToken.generate(random);
        byte[] expected = java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.value().substring(13).getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertTrue(java.util.Arrays.equals(expected, token.secretHash()));
        assertFalse(java.util.Arrays.equals(expected, ApiKeyToken.generate(random).secretHash()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "pgn_",
            "pgn_ABCDEFGH",
            "pgn_ABCDEFGH_tooShort",
            "PGN_ABCDEFGH_0123456789012345678901234567890123456789012",
            "pgn_ABCDEFG_0123456789012345678901234567890123456789012",
            "pgn_ABCDEFGH_0123456789012345678901234567890123456789012x",
            "pgn_ABCDEFG-_0123456789012345678901234567890123456789012",
            " pgn_ABCDEFGH_0123456789012345678901234567890123456789012",
            "Bearer pgn_ABCDEFGH_0123456789012345678901234567890123456789012"
    })
    void rejectsAnythingThatIsNotExactlyAKey(String text) {
        assertTrue(ApiKeyToken.parse(text).isEmpty(), String.valueOf(text));
    }
}

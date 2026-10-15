package io.github.pregonhq.pregon.core.apikey;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An API key as the client sends it: {@code pgn_<id>_<secret>} (ADR-0018).
 *
 * <p>The id is public and finds the stored key; the secret is never stored, only its SHA-256.
 * A plain hash is enough because the secret has 256 random bits: slow hashes protect
 * low-entropy passwords, not random keys.
 */
public final class ApiKeyToken {

    static final String PREFIX = "pgn_";
    static final int ID_LENGTH = 8;
    static final int SECRET_BYTES = 32;
    /** 62^43 > 2^256, so 43 base62 characters hold any 32-byte secret. */
    static final int SECRET_LENGTH = 43;

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern FORMAT = Pattern.compile(
            "pgn_([0-9A-Za-z]{" + ID_LENGTH + "})_([0-9A-Za-z]{" + SECRET_LENGTH + "})");

    private final String id;
    private final String secret;

    private ApiKeyToken(String id, String secret) {
        this.id = id;
        this.secret = secret;
    }

    /** A new key with a random id and secret. */
    public static ApiKeyToken generate(SecureRandom random) {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return new ApiKeyToken(id.toString(), toBase62(secret));
    }

    /** Parses a key; empty when the text is not exactly in the key format. */
    public static Optional<ApiKeyToken> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        var matcher = FORMAT.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ApiKeyToken(matcher.group(1), matcher.group(2)));
    }

    public String id() {
        return id;
    }

    /** SHA-256 of the secret, the only part of the secret that is stored. */
    public byte[] secretHash() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform must provide SHA-256", e);
        }
    }

    /** The full key. Shown once at creation; never logged. */
    public String value() {
        return PREFIX + id + "_" + secret;
    }

    /** Never reveals the secret, so a token that reaches a log does not leak. */
    @Override
    public String toString() {
        return PREFIX + id + "_***";
    }

    private static String toBase62(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        BigInteger base = BigInteger.valueOf(BASE62.length());
        char[] out = new char[SECRET_LENGTH];
        for (int i = SECRET_LENGTH - 1; i >= 0; i--) {
            BigInteger[] division = value.divideAndRemainder(base);
            out[i] = BASE62.charAt(division[1].intValue());
            value = division[0];
        }
        return new String(out);
    }
}

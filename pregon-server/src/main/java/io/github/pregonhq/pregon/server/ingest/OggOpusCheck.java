package io.github.pregonhq.pregon.server.ingest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Is this an Ogg/Opus stream? Checked by header only (RFC 3533, RFC 7845), as decided for ingestion:
 * the first page must start with the {@code OggS} capture pattern and its first packet with
 * {@code OpusHead}. Decoding is the sidecar's job; a stream that lies here is caught there and the
 * segment fails on its own, not the upload.
 */
final class OggOpusCheck {

    private static final byte[] OGGS = "OggS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OPUS_HEAD = "OpusHead".getBytes(StandardCharsets.US_ASCII);
    private static final int PAGE_HEADER = 27;

    private OggOpusCheck() {
    }

    static boolean isOggOpus(byte[] audio) {
        if (audio.length < PAGE_HEADER + 1 + OPUS_HEAD.length || !startsWith(audio, 0, OGGS)) {
            return false;
        }
        int segments = audio[26] & 0xFF;
        int firstPacket = PAGE_HEADER + segments;
        return segments > 0 && audio.length >= firstPacket + OPUS_HEAD.length && startsWith(audio, firstPacket, OPUS_HEAD);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}

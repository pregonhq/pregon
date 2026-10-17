package io.github.pregonhq.pregon.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** The fixture generator must be right, or a failing ingestion test would blame the server. */
class OggOpusTest {

    @Test
    void theCrcIsTheOneOggDefines() {
        // Check value of CRC-32 with polynomial 0x04C11DB7, init 0, no reflection, no final XOR.
        assertThat(OggOpus.crc("123456789".getBytes(StandardCharsets.US_ASCII))).isEqualTo(0x89A1897F);
    }

    @Test
    void everyPageCarriesItsOwnChecksum() {
        byte[] stream = OggOpus.silence(120, 0);
        int offset = 0;
        int pages = 0;
        while (offset < stream.length) {
            assertThat(new String(stream, offset, 4, StandardCharsets.US_ASCII)).isEqualTo("OggS");
            int segments = stream[offset + 26] & 0xFF;
            int length = 27 + segments;
            for (int i = 0; i < segments; i++) {
                length += stream[offset + 27 + i] & 0xFF;
            }
            byte[] page = Arrays.copyOfRange(stream, offset, offset + length);
            int stored = ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Arrays.fill(page, 22, 26, (byte) 0);
            assertThat(OggOpus.crc(page)).isEqualTo(stored);
            offset += length;
            pages++;
        }
        assertThat(pages).isEqualTo(2 + 3);
        assertThat(new String(stream, 28, 8, StandardCharsets.US_ASCII)).isEqualTo("OpusHead");
    }

    @Test
    void sameLengthDifferentBytesWithAnotherSeed() {
        byte[] a = OggOpus.silence(10, 0);
        byte[] b = OggOpus.silence(10, 1);
        assertThat(a).hasSameSizeAs(b).isNotEqualTo(b);
    }

    @Test
    void buildsStreamsOfAnExactSize() {
        for (int size : new int[] {4_000, 65_536, 1_048_576, 1_048_577}) {
            assertThat(OggOpus.ofSize(size)).hasSize(size);
        }
    }
}

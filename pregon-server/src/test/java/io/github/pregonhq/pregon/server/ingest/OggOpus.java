package io.github.pregonhq.pregon.server.ingest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds synthetic Ogg/Opus streams byte by byte (RFC 3533, RFC 7845), so the tests need neither
 * an encoder nor audio files in the repository. Every packet is a single 20 ms CELT frame, mono,
 * at 48 kHz; the payload carries no recorded sound.
 */
public final class OggOpus {

    private static final int SAMPLES_PER_PACKET = 960;
    private static final int PRE_SKIP = 312;
    private static final int PACKETS_PER_PAGE = 50;
    /** TOC byte: configuration 31 (CELT, fullband, 20 ms), mono, one frame per packet. */
    private static final byte TOC = (byte) 0xF8;
    private static final int PAGE_HEADER = 27;

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }

    private OggOpus() {
    }

    /** A valid stream of {@code packets} silent frames; {@code seed} changes the bytes, not the length. */
    public static byte[] silence(int packets, int seed) {
        List<byte[]> audio = new ArrayList<>();
        for (int i = 0; i < packets; i++) {
            audio.add(new byte[] {TOC, (byte) 0xFF, (byte) (0xFE ^ (seed & 0x01))});
        }
        return stream(audio, seed);
    }

    /** A valid stream of exactly {@code bytes} bytes, for size-limit tests. */
    static byte[] ofSize(int bytes) {
        int fixed = headersSize();
        int remaining = bytes - fixed;
        List<byte[]> audio = new ArrayList<>();
        // Full pages of 254-byte packets (one lacing value each) until a final page fits the rest.
        int fullPage = PAGE_HEADER + PACKETS_PER_PAGE + PACKETS_PER_PAGE * 254;
        while (remaining > fullPage + 2 * PAGE_HEADER + 600) {
            for (int i = 0; i < PACKETS_PER_PAGE; i++) {
                audio.add(packet(254));
            }
            remaining -= fullPage;
        }
        List<List<byte[]>> tail = tailPages(remaining);
        byte[] stream = stream(audio, tail);
        if (stream.length != bytes) {
            throw new IllegalStateException("built " + stream.length + " bytes instead of " + bytes);
        }
        return stream;
    }

    /** The bytes of a WAV header followed by silence: audio, but not Ogg/Opus. */
    static byte[] wav() {
        ByteBuffer b = ByteBuffer.allocate(44 + 3200).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 3200)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(3200);
        return b.array();
    }

    /** An Ogg stream whose first packet is not {@code OpusHead} (e.g. Ogg/Vorbis). */
    static byte[] oggWithoutOpusHead() {
        byte[] vorbis = "\u0001vorbis\u0000\u0000\u0000\u0000\u0001".getBytes(StandardCharsets.ISO_8859_1);
        return page(0x02, 0, 0, List.of(vorbis));
    }

    // CRC-32 as Ogg defines it: polynomial 0x04C11DB7, initial value 0, no reflection, no final XOR.
    static int crc(byte[] data) {
        int crc = 0;
        for (byte d : data) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ d) & 0xFF];
        }
        return crc;
    }

    private static byte[] packet(int length) {
        byte[] p = new byte[length];
        p[0] = TOC;
        for (int i = 1; i < length; i++) {
            p[i] = (byte) (i * 31);
        }
        return p;
    }

    private static int headersSize() {
        return page(0x02, 0, 0, List.of(opusHead())).length + page(0, 0, 1, List.of(opusTags())).length;
    }

    /** One or two last pages that add up to exactly {@code bytes}, each with a single packet. */
    private static List<List<byte[]>> tailPages(int bytes) {
        Integer single = packetLengthForPage(bytes);
        if (single != null) {
            return List.of(List.of(packet(single)));
        }
        for (int first = 300; first < bytes - 300; first++) {
            Integer a = packetLengthForPage(first);
            Integer b = packetLengthForPage(bytes - first);
            if (a != null && b != null) {
                return List.of(List.of(packet(a)), List.of(packet(b)));
            }
        }
        throw new IllegalArgumentException("cannot build a tail of " + bytes + " bytes");
    }

    private static Integer packetLengthForPage(int pageBytes) {
        for (int p = 1; p < pageBytes; p++) {
            int size = PAGE_HEADER + lacing(p).length + p;
            if (size == pageBytes) {
                return p;
            }
            if (size > pageBytes) {
                return null;
            }
        }
        return null;
    }

    private static byte[] stream(List<byte[]> audio, int seed) {
        return stream(audio, List.of(), seed);
    }

    private static byte[] stream(List<byte[]> audio, List<List<byte[]>> tail) {
        return stream(audio, tail, 0);
    }

    private static byte[] stream(List<byte[]> audio, List<List<byte[]>> tail, int seed) {
        int serial = 0x5052_4547 ^ seed;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int sequence = 0;
        out.writeBytes(page(0x02, 0, sequence++, List.of(opusHead()), serial));
        out.writeBytes(page(0, 0, sequence++, List.of(opusTags()), serial));
        List<List<byte[]>> pages = new ArrayList<>();
        for (int i = 0; i < audio.size(); i += PACKETS_PER_PAGE) {
            pages.add(audio.subList(i, Math.min(i + PACKETS_PER_PAGE, audio.size())));
        }
        pages.addAll(tail);
        long granule = 0;
        for (int i = 0; i < pages.size(); i++) {
            granule += (long) pages.get(i).size() * SAMPLES_PER_PACKET;
            int type = i == pages.size() - 1 ? 0x04 : 0;
            out.writeBytes(page(type, granule, sequence++, pages.get(i), serial));
        }
        return out.toByteArray();
    }

    private static byte[] opusHead() {
        return ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1).put((byte) 1).putShort((short) PRE_SKIP).putInt(48000).putShort((short) 0).put((byte) 0)
                .array();
    }

    private static byte[] opusTags() {
        byte[] vendor = "pregon-test".getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(vendor.length).put(vendor).putInt(0)
                .array();
    }

    private static byte[] page(int type, long granule, int sequence, List<byte[]> packets) {
        return page(type, granule, sequence, packets, 0x5052_4547);
    }

    private static byte[] page(int type, long granule, int sequence, List<byte[]> packets, int serial) {
        ByteArrayOutputStream segments = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : packets) {
            segments.writeBytes(lacing(p.length));
            body.writeBytes(p);
        }
        byte[] table = segments.toByteArray();
        if (table.length > 255) {
            throw new IllegalArgumentException("too many segments in one page");
        }
        ByteBuffer page = ByteBuffer.allocate(PAGE_HEADER + table.length + body.size()).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) type)
                .putLong(granule).putInt(serial).putInt(sequence).putInt(0)
                .put((byte) table.length).put(table).put(body.toByteArray());
        byte[] bytes = page.array();
        ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc(bytes));
        return bytes;
    }

    /** Lacing values of one packet: as many 255 as fit, then the rest (a 0 when it divides evenly). */
    private static byte[] lacing(int length) {
        byte[] values = new byte[length / 255 + 1];
        for (int i = 0; i < values.length - 1; i++) {
            values[i] = (byte) 255;
        }
        values[values.length - 1] = (byte) (length % 255);
        return values;
    }
}

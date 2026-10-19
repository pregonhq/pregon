// Synthetic Ogg/Opus streams built byte by byte (RFC 3533, RFC 7845), so the load test needs
// neither an encoder nor audio files in the repository. Port of the server's test helper
// OggOpus.java and of stt-sidecar/tests/oggopus.py: every packet is a single 20 ms CELT frame,
// mono, 48 kHz. The payload bytes are pseudo-random, never recorded sound, sized to the bitrate
// the capture agent uses so the server hashes and stores what it will see in production.

const SAMPLES_PER_PACKET = 960;
const PACKETS_PER_SECOND = 50;
const PRE_SKIP = 312;
const PACKETS_PER_PAGE = 50;
// TOC byte: configuration 31 (CELT, fullband, 20 ms), mono, one frame per packet.
const TOC = 0xf8;
const SERIAL = 0x50524547;

const CRC_TABLE = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let r = i << 24;
  for (let bit = 0; bit < 8; bit++) {
    r = r & 0x80000000 ? (r << 1) ^ 0x04c11db7 : r << 1;
  }
  CRC_TABLE[i] = r >>> 0;
}

/** CRC-32 as Ogg defines it: polynomial 0x04C11DB7, initial 0, no reflection, no final XOR. */
export function crc(bytes) {
  let value = 0;
  for (let i = 0; i < bytes.length; i++) {
    value = ((value << 8) ^ CRC_TABLE[((value >>> 24) ^ bytes[i]) & 0xff]) >>> 0;
  }
  return value;
}

/**
 * A valid Ogg/Opus stream of `seconds` of audio at `kbps`. `seed` changes the bytes, not the
 * length, so segments with different seeds have different hashes.
 */
export function segment({ seconds = 10, kbps = 24, seed = 0 } = {}) {
  const packets = Math.round(seconds * PACKETS_PER_SECOND);
  const bytesPerPacket = Math.max(1, Math.round((kbps * 1000) / 8 / PACKETS_PER_SECOND));
  const random = xorshift(seed);
  const audio = [];
  for (let i = 0; i < packets; i++) {
    const packet = new Uint8Array(bytesPerPacket);
    packet[0] = TOC;
    for (let b = 1; b < bytesPerPacket; b++) {
      packet[b] = random() & 0xff;
    }
    audio.push(packet);
  }
  return stream(audio, (SERIAL ^ seed) >>> 0);
}

function xorshift(seed) {
  let state = (Math.imul(seed + 1, 0x9e3779b1) ^ 0x85ebca6b) >>> 0 || 1;
  return () => {
    state ^= state << 13;
    state >>>= 0;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    return state;
  };
}

const ASCII = new TextEncoder();

function opusHead() {
  const head = new Uint8Array(19);
  const view = new DataView(head.buffer);
  head.set(ASCII.encode('OpusHead'), 0);
  head[8] = 1; // version
  head[9] = 1; // channels
  view.setUint16(10, PRE_SKIP, true);
  view.setUint32(12, 48000, true); // input sample rate
  view.setInt16(16, 0, true); // output gain
  head[18] = 0; // channel mapping family
  return head;
}

function opusTags() {
  const vendor = ASCII.encode('pregon-load');
  const tags = new Uint8Array(8 + 4 + vendor.length + 4);
  const view = new DataView(tags.buffer);
  tags.set(ASCII.encode('OpusTags'), 0);
  view.setUint32(8, vendor.length, true);
  tags.set(vendor, 12);
  view.setUint32(12 + vendor.length, 0, true); // no user comments
  return tags;
}

function page(headerType, granule, sequence, packets, serial) {
  const lacing = [];
  let dataLength = 0;
  for (const packet of packets) {
    let remaining = packet.length;
    while (remaining >= 255) {
      lacing.push(255);
      remaining -= 255;
    }
    lacing.push(remaining);
    dataLength += packet.length;
  }
  if (lacing.length > 255) {
    throw new Error('too many segments in one page');
  }
  const out = new Uint8Array(27 + lacing.length + dataLength);
  const view = new DataView(out.buffer);
  out.set(ASCII.encode('OggS'), 0);
  out[4] = 0; // stream structure version
  out[5] = headerType;
  view.setUint32(6, granule >>> 0, true);
  view.setUint32(10, Math.floor(granule / 4294967296), true);
  view.setUint32(14, serial, true);
  view.setUint32(18, sequence, true);
  view.setUint32(22, 0, true); // checksum, computed below over the whole page
  out[26] = lacing.length;
  out.set(lacing, 27);
  let offset = 27 + lacing.length;
  for (const packet of packets) {
    out.set(packet, offset);
    offset += packet.length;
  }
  view.setUint32(22, crc(out), true);
  return out;
}

function stream(audio, serial) {
  const pages = [page(0x02, 0, 0, [opusHead()], serial), page(0, 0, 1, [opusTags()], serial)];
  let granule = 0;
  for (let start = 0, sequence = 2; start < audio.length; start += PACKETS_PER_PAGE, sequence++) {
    const packets = audio.slice(start, start + PACKETS_PER_PAGE);
    granule += packets.length * SAMPLES_PER_PACKET;
    const last = start + PACKETS_PER_PAGE >= audio.length;
    pages.push(page(last ? 0x04 : 0, granule, sequence, packets, serial));
  }
  const out = new Uint8Array(pages.reduce((total, p) => total + p.length, 0));
  let offset = 0;
  for (const p of pages) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

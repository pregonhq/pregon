"""Synthetic Ogg/Opus streams built byte by byte (RFC 3533, RFC 7845), so the tests need neither
an encoder nor audio files in the repository. Port of the server's test helper of the same name:
every packet is a single 20 ms CELT frame, mono, 48 kHz, carrying no recorded sound.
"""

from __future__ import annotations

import struct

SAMPLES_PER_PACKET = 960
PRE_SKIP = 312
PACKETS_PER_PAGE = 50
# TOC byte: configuration 31 (CELT, fullband, 20 ms), mono, one frame per packet.
TOC = 0xF8
SERIAL = 0x50524547

_CRC_TABLE = []
for _i in range(256):
    _r = _i << 24
    for _ in range(8):
        _r = ((_r << 1) ^ 0x04C11DB7) if _r & 0x80000000 else (_r << 1)
    _CRC_TABLE.append(_r & 0xFFFFFFFF)


def crc(data: bytes) -> int:
    """CRC-32 as Ogg defines it: polynomial 0x04C11DB7, initial 0, no reflection, no final XOR."""
    value = 0
    for byte in data:
        value = ((value << 8) & 0xFFFFFFFF) ^ _CRC_TABLE[((value >> 24) ^ byte) & 0xFF]
    return value


def silence(packets: int, seed: int = 0) -> bytes:
    """A valid stream of `packets` silent 20 ms frames; `seed` changes the bytes, not the length."""
    audio = [bytes([TOC, 0xFF, 0xFE ^ (seed & 0x01)]) for _ in range(packets)]
    return _stream(audio, SERIAL ^ seed)


def wav() -> bytes:
    """A WAV header followed by silence: audio, but not Ogg/Opus."""
    data = 3200
    return (
        b"RIFF" + struct.pack("<I", 36 + data) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
        + b"data" + struct.pack("<I", data) + bytes(data)
    )


def _lacing(length: int) -> bytes:
    return bytes([255] * (length // 255) + [length % 255])


def _opus_head() -> bytes:
    return b"OpusHead" + struct.pack("<BBHIhB", 1, 1, PRE_SKIP, 48000, 0, 0)


def _opus_tags() -> bytes:
    vendor = b"pregon-test"
    return b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)


def _page(header_type: int, granule: int, sequence: int, packets: list[bytes], serial: int) -> bytes:
    table = b"".join(_lacing(len(p)) for p in packets)
    if len(table) > 255:
        raise ValueError("too many segments in one page")
    header = b"OggS" + struct.pack("<BBqIIIB", 0, header_type, granule, serial, sequence, 0, len(table))
    page = bytearray(header + table + b"".join(packets))
    struct.pack_into("<I", page, 22, crc(bytes(page)))
    return bytes(page)


def _stream(audio: list[bytes], serial: int) -> bytes:
    out = bytearray()
    sequence = 0
    out += _page(0x02, 0, sequence, [_opus_head()], serial)
    sequence += 1
    out += _page(0, 0, sequence, [_opus_tags()], serial)
    sequence += 1
    pages = [audio[i : i + PACKETS_PER_PAGE] for i in range(0, len(audio), PACKETS_PER_PAGE)]
    granule = 0
    for index, page in enumerate(pages):
        granule += len(page) * SAMPLES_PER_PACKET
        header_type = 0x04 if index == len(pages) - 1 else 0
        out += _page(header_type, granule, sequence, page, serial)
        sequence += 1
    return bytes(out)

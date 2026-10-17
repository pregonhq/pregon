"""The in-memory multipart reader: names, order, limits and malformed bodies."""

import httpx
import pytest

from app.multipart import MultipartError, MultipartReader, PartTooLarge, TooManyParts


def body(**parts: bytes) -> tuple[str, bytes]:
    """A real multipart/form-data body as a client would send it, with its Content-Type."""
    request = httpx.Request("POST", "http://x/", files={name: (name, data, "application/octet-stream") for name, data in parts.items()})
    return request.headers["content-type"], request.read()


def read(content_type: str, data: bytes, max_part_bytes: int = 1_000_000, max_parts: int = 10, chunk: int = 7) -> MultipartReader:
    reader = MultipartReader(content_type, max_part_bytes, max_parts)
    for i in range(0, len(data), chunk):
        reader.feed(data[i : i + chunk])
    reader.finish()
    return reader


def test_parts_keep_their_names_order_and_bytes():
    content_type, data = body(request=b'{"a":1}', first=b"\x00\xff" * 100, second=b"OggS")

    reader = read(content_type, data)

    assert [p.name for p in reader.parts] == ["request", "first", "second"]
    assert bytes(reader.parts[1].data) == b"\x00\xff" * 100
    assert bytes(reader.parts[2].data) == b"OggS"
    assert reader.parts[0].content_type == "application/octet-stream"


def test_chunk_boundaries_do_not_matter():
    content_type, data = body(request=b"x" * 1000, audio=bytes(range(256)) * 10)

    for chunk in (1, 3, 64, 4096, len(data)):
        reader = read(content_type, data, chunk=chunk)
        assert bytes(reader.parts[1].data) == bytes(range(256)) * 10


def test_a_part_over_the_limit_is_refused_while_it_arrives():
    content_type, data = body(request=b"{}", audio=b"a" * 5000)

    with pytest.raises(PartTooLarge) as error:
        read(content_type, data, max_part_bytes=4000)
    assert error.value.name == "audio"


def test_a_part_exactly_at_the_limit_is_accepted():
    content_type, data = body(audio=b"a" * 4000)

    assert len(read(content_type, data, max_part_bytes=4000).parts[0].data) == 4000


def test_too_many_parts_is_refused_at_the_extra_part():
    content_type, data = body(a=b"1", b=b"2", c=b"3")

    with pytest.raises(TooManyParts):
        read(content_type, data, max_parts=2)


def test_missing_boundary_or_wrong_media_type_is_rejected():
    with pytest.raises(MultipartError):
        MultipartReader("application/json", 10, 10)
    with pytest.raises(MultipartError):
        MultipartReader("multipart/form-data", 10, 10)
    with pytest.raises(MultipartError):
        MultipartReader(None, 10, 10)


def test_truncated_body_is_rejected():
    content_type, data = body(request=b"{}", audio=b"a" * 100)

    with pytest.raises(MultipartError):
        read(content_type, data[:-30])

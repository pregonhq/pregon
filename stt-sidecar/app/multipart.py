"""Streaming multipart/form-data reader that keeps every part in memory and bounds its size.

Starlette's form parser spools large parts to a temporary file; audio must never touch disk
(ADR-0006), and a part over the limit must be refused while it arrives, not after it was stored.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from python_multipart.multipart import MultipartParser, parse_options_header


class MultipartError(ValueError):
    """The body is not the multipart/form-data the contract describes."""


class PartTooLarge(MultipartError):
    def __init__(self, name: str, limit: int) -> None:
        super().__init__(f"part {name!r} is larger than {limit} bytes")
        self.name = name
        self.limit = limit


class TooManyParts(MultipartError):
    def __init__(self, limit: int) -> None:
        super().__init__(f"more than {limit} parts")


@dataclass
class Part:
    name: str
    content_type: str | None
    data: bytearray = field(default_factory=bytearray)


class MultipartReader:
    """Feed it the request body chunk by chunk; `parts` holds what arrived, in order."""

    def __init__(self, content_type: str | None, max_part_bytes: int, max_parts: int) -> None:
        media_type, params = parse_options_header(content_type or "")
        boundary = params.get(b"boundary")
        if media_type != b"multipart/form-data" or not boundary:
            raise MultipartError("Content-Type must be multipart/form-data with a boundary")
        self._max_part_bytes = max_part_bytes
        self._max_parts = max_parts
        self.parts: list[Part] = []
        self._current: Part | None = None
        self._header_name = bytearray()
        self._header_value = bytearray()
        self._headers: dict[bytes, bytes] = {}
        self._parser = MultipartParser(
            boundary,
            {
                "on_part_begin": self._on_part_begin,
                "on_header_field": lambda data, start, end: self._header_name.extend(data[start:end]),
                "on_header_value": lambda data, start, end: self._header_value.extend(data[start:end]),
                "on_header_end": self._on_header_end,
                "on_headers_finished": self._on_headers_finished,
                "on_part_data": self._on_part_data,
                "on_part_end": self._on_part_end,
            },
        )

    def feed(self, chunk: bytes) -> None:
        self._parser.write(chunk)

    def finish(self) -> None:
        self._parser.finalize()
        if self._current is not None:
            raise MultipartError("body ended inside a part")

    def _on_part_begin(self) -> None:
        if len(self.parts) >= self._max_parts:
            raise TooManyParts(self._max_parts)
        self._headers = {}

    def _on_header_end(self) -> None:
        self._headers[bytes(self._header_name).lower()] = bytes(self._header_value)
        self._header_name.clear()
        self._header_value.clear()

    def _on_headers_finished(self) -> None:
        disposition = self._headers.get(b"content-disposition")
        if disposition is None:
            raise MultipartError("a part has no Content-Disposition header")
        _kind, params = parse_options_header(disposition)
        name = params.get(b"name")
        if not name:
            raise MultipartError("a part has no name")
        content_type = self._headers.get(b"content-type")
        self._current = Part(name.decode("utf-8", "replace"), content_type.decode("latin-1") if content_type else None)

    def _on_part_data(self, data: bytes, start: int, end: int) -> None:
        part = self._current
        assert part is not None
        if len(part.data) + (end - start) > self._max_part_bytes:
            raise PartTooLarge(part.name, self._max_part_bytes)
        part.data.extend(data[start:end])

    def _on_part_end(self) -> None:
        assert self._current is not None
        self.parts.append(self._current)
        self._current = None

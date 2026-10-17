"""Problem Details (RFC 9457) responses, the error format of every Pregón API."""

from __future__ import annotations

from collections.abc import Mapping

from fastapi.responses import JSONResponse

TITLES = {
    400: "Bad Request",
    413: "Content Too Large",
    415: "Unsupported Media Type",
    503: "Service Unavailable",
}


def problem(status: int, detail: str, headers: Mapping[str, str] | None = None) -> JSONResponse:
    body = {"type": "about:blank", "title": TITLES.get(status, "Error"), "status": status, "detail": detail}
    return JSONResponse(body, status_code=status, media_type="application/problem+json", headers=dict(headers or {}))

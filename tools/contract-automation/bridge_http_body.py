"""Bounded HTTP body reader for the isolated SSH acceptance bridge only."""
from __future__ import annotations


def read_http_body(stream, headers, limit: int) -> bytes:
    def exact(size):
        data = stream.read(size)
        if len(data) != size:
            raise ValueError("truncated HTTP test body")
        return data

    transfer = headers.get("Transfer-Encoding", "").lower()
    length = headers.get("Content-Length")
    if transfer:
        if transfer != "chunked" or length is not None:
            raise ValueError("ambiguous HTTP test framing")
        parts = []
        total = 0
        while True:
            line = stream.readline(128)
            if not line.endswith(b"\r\n"):
                raise ValueError("invalid HTTP test chunk")
            token = line[:-2].split(b";", 1)[0]
            if not token or any(c not in b"0123456789abcdefABCDEF" for c in token):
                raise ValueError("invalid HTTP test chunk size")
            size = int(token, 16)
            if total + size > limit:
                raise ValueError("HTTP test input limit")
            if size == 0:
                # Trailers are not required by either real client and cannot
                # influence forwarded credentials or message framing.
                if stream.readline(128) != b"\r\n":
                    raise ValueError("HTTP test trailers unsupported")
                return b"".join(parts)
            parts.append(exact(size))
            total += size
            if exact(2) != b"\r\n":
                raise ValueError("invalid HTTP test chunk delimiter")
    if length is None or not length.isdecimal():
        raise ValueError("missing HTTP test body length")
    size = int(length)
    if size > limit:
        raise ValueError("HTTP test input limit")
    return exact(size)

import io
import unittest
from bridge_http_body import read_http_body


class BridgeBodyTest(unittest.TestCase):
    def test_binary_multipart_stream_matches_content_length(self):
        body = b"--boundary\r\n\x00\xff\xe8\xaa\x00\r\n--boundary--\r\n"
        chunks = b"".join(hex(len(p))[2:].encode() + b"\r\n" + p + b"\r\n"
                          for p in (body[:8], body[8:19], body[19:])) + b"0\r\n\r\n"
        self.assertEqual(read_http_body(io.BytesIO(chunks), {"Transfer-Encoding": "chunked"}, 100), body)
        self.assertEqual(read_http_body(io.BytesIO(body), {"Content-Length": str(len(body))}, 100), body)

    def test_oversized_chunk_is_rejected_before_payload_read(self):
        stream = io.BytesIO(b"10000000\r\n")
        with self.assertRaises(ValueError):
            read_http_body(stream, {"Transfer-Encoding": "chunked"}, 1024)
        self.assertEqual(stream.tell(), 10)

    def test_ambiguous_invalid_and_truncated_frames_are_rejected(self):
        cases = [(b"", {"Transfer-Encoding": "chunked", "Content-Length": "0"}),
                 (b"4\r\nab", {"Transfer-Encoding": "chunked"}),
                 (b"+4\r\n", {"Transfer-Encoding": "chunked"}),
                 (b"0\r\nAuthorization: forbidden\r\n\r\n", {"Transfer-Encoding": "chunked"}),
                 (b"ab", {"Content-Length": "3"}), (b"", {"Content-Length": "-1"})]
        for body, headers in cases:
            with self.subTest(headers=headers, body=body), self.assertRaises(ValueError):
                read_http_body(io.BytesIO(body), headers, 100)


if __name__ == "__main__":
    unittest.main()

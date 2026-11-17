#!/usr/bin/env python3
"""Tiny webhook catcher for the local e2e. Writes the last body and signature to disk."""
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

body_path, sig_path, port = sys.argv[1], sys.argv[2], int(sys.argv[3])


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        with open(body_path, "wb") as handle:
            handle.write(raw)
        with open(sig_path, "w", encoding="utf-8") as handle:
            handle.write(self.headers.get("X-AevumPay-Signature", ""))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        sys.stderr.write("webhook " + (fmt % args) + "\n")


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()

"""Disposable INTERNAL-network SMTP sink: counts deliveries, never stores mail/credentials."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from socketserver import StreamRequestHandler, ThreadingTCPServer
import threading

COUNTS = {'messages': 0, 'firing': 0, 'resolved': 0}
LOCK = threading.Lock()


class SMTP(StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(5)
        self.wfile.write(b'220 fixture SMTP\r\n')
        while True:
            command = self.rfile.readline(8192).upper()
            if not command:
                return
            if command.startswith(b'EHLO'):
                self.wfile.write(b'250-fixture\r\n250 AUTH PLAIN\r\n')
            elif command.startswith(b'AUTH'):
                self.wfile.write(b'235 authenticated\r\n')
            elif command.startswith(b'DATA'):
                self.wfile.write(b'354 send data\r\n')
                data = bytearray()
                while True:
                    line = self.rfile.readline(8192)
                    if line in {b'.\r\n', b''}:
                        break
                    if len(data) < 65536:
                        data.extend(line)
                # Retain no body/address; only the known status in the subject.
                subject = next((line.upper() for line in bytes(data).splitlines() if line.upper().startswith(b'SUBJECT:')), b'')
                with LOCK:
                    COUNTS['messages'] += 1
                    COUNTS['firing'] += int(b'FIRING' in subject)
                    COUNTS['resolved'] += int(b'RESOLVED' in subject)
                self.wfile.write(b'250 accepted\r\n')
            elif command.startswith(b'QUIT'):
                self.wfile.write(b'221 bye\r\n')
                return
            else:
                self.wfile.write(b'250 ok\r\n')


class HTTP(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        with LOCK:
            body = json.dumps(COUNTS).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        self.rfile.read(min(int(self.headers.get('Content-Length', '0')), 65536))
        self.send_response(200)
        self.end_headers()


threading.Thread(target=ThreadingTCPServer(('0.0.0.0', 2525), SMTP).serve_forever, daemon=True).start()
ThreadingHTTPServer(('0.0.0.0', 8090), HTTP).serve_forever()

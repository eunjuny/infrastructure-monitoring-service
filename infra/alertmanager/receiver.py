"""Bounded inbox and authenticated email-config control; SMTP is sent by Alertmanager."""
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import hmac
import os
from threading import Lock

NAMES = {'BackendScrapeUnavailable', 'ResourceApiErrorBurst', 'MockBatchFailures'}
EVENTS = deque(maxlen=200)
LOCK = Lock()
EMAIL = None
CONTROL_TOKEN = os.environ.get('ALERT_EMAIL_CONTROL_TOKEN', '')


def sanitize(body):
    if not isinstance(body, dict) or not isinstance(body.get('alerts'), list) or len(body['alerts']) > 20:
        raise ValueError('Invalid alerts')
    result = []
    for alert in body['alerts']:
        labels = alert.get('labels', {})
        name = labels.get('alertname')
        status = alert.get('status')
        if name not in NAMES or labels.get('job') != 'infrastructure-api' or status not in {'firing', 'resolved'}:
            raise ValueError('Unsupported alert')
        result.append({'name': name, 'status': status, 'receivedAt': datetime.now(timezone.utc).isoformat()})
    return result


class Receiver(BaseHTTPRequestHandler):
    def email_authorized(self):
        return (EMAIL is not None and len(CONTROL_TOKEN) >= 32
                and hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + CONTROL_TOKEN))

    def setup(self):
        super().setup()
        self.connection.settimeout(3)

    def log_message(self, *_):
        pass  # No bodies, URLs or attacker-controlled access logs.

    def respond(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == '/health':
            self.respond(200, {'ready': True})
        elif self.path == '/events':
            with LOCK:
                self.respond(200, {'events': list(EVENTS)})
        elif self.path == '/email-settings' and self.email_authorized():
            self.respond(200, EMAIL.snapshot())
        else:
            self.respond(404, {})

    def do_PUT(self):
        if self.path != '/email-settings' or not self.email_authorized():
            self.respond(403, {})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 1024 or self.headers.get('Transfer-Encoding') is not None:
                self.respond(413, {})
                return
            self.respond(200, EMAIL.update(json.loads(self.rfile.read(size))))
        except (ValueError, TypeError, AttributeError):
            self.respond(400, {'message': 'Invalid recipient or SMTP is not configured'})
        except (OSError, TimeoutError):
            self.respond(503, {'message': 'Notification configuration unavailable'})

    def do_POST(self):
        if self.path != '/webhook':
            self.respond(404, {})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 65536 or self.headers.get('Transfer-Encoding') is not None:
                self.respond(413, {})
                return
            rows = sanitize(json.loads(self.rfile.read(size)))
            with LOCK:
                EVENTS.extend(rows)
            self.respond(200, {'accepted': len(rows)})
        except (ValueError, TypeError, AttributeError, TimeoutError, OSError):
            self.respond(400, {})


if __name__ == '__main__':
    from email_settings import EmailSettings
    if os.environ.get('ALERT_EMAIL_SMTP_FILE'):
        if len(CONTROL_TOKEN) < 32:
            raise SystemExit('Missing private notification control token')
        try:
            EMAIL = EmailSettings('/var/lib/alert-email', os.environ['ALERT_EMAIL_SMTP_FILE'])
        except Exception:
            raise SystemExit('Private notification setup failed; values suppressed') from None
    HTTPServer(('0.0.0.0', 8090), Receiver).serve_forever()

#!/usr/bin/env python3
"""
极简 WebDAV 测试服务器 —— 只为了验证 AvPlay 的同步链路（PROPFIND / GET / PUT）。

不是通用 WebDAV 实现：只支持单层目录 + 单文件，够验"探测 / 首次上传(404) / 下载 / 合并后再上传"四条路径。
正式环境请用 Nextcloud / 群晖 / 坚果云。

用法：
    python webdav-test-server.py [--port 8099] [--user demo] [--password demo123]

Android 模拟器访问本机请用 http://10.0.2.2:8099/dav/avplay
"""
import argparse
import base64
import os
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

ROOT = os.path.join(tempfile.gettempdir(), "webdav-test-root")
AUTH_USER = "demo"
AUTH_PASSWORD = "demo123"

PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>{href}</D:href>
    <D:propstat>
      <D:prop>
        <D:displayname>{name}</D:displayname>
        <D:resourcetype>{rtype}</D:resourcetype>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 覆盖默认日志，输出单行便于抓取
        sys.stdout.write("[dav] %s %s\n" % (self.command, fmt % args))
        sys.stdout.flush()

    # ---------- 基础设施 ----------
    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return False
        return raw == f"{AUTH_USER}:{AUTH_PASSWORD}"

    def _require_auth(self) -> bool:
        if self._authorized():
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="avplay-test"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def _disk_path(self) -> str:
        path = unquote(urlparse(self.path).path)
        # 只取最后一段，防止 ../ 逃逸
        name = os.path.basename(path.rstrip("/"))
        if not name:
            name = "root"
        return os.path.join(ROOT, name)

    # ---------- 动词 ----------
    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("DAV", "1,2")
        self.send_header("Allow", "OPTIONS, PROPFIND, GET, PUT, HEAD, DELETE")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
        if not self._require_auth():
            return
        href = self.path
        name = os.path.basename(urlparse(href).path.rstrip("/")) or "root"
        body = PROPFIND_BODY.format(href=href, name=name, rtype="<D:collection/>")
        payload = body.encode("utf-8")
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if not self._require_auth():
            return
        path = self._disk_path()
        if not os.path.isfile(path):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            print(f"[dav] GET 404 (no file yet): {path}", flush=True)
            return
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        print(f"[dav] GET 200 {len(data)}B {path}", flush=True)

    def do_PUT(self):
        if not self._require_auth():
            return
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length) if length else b""
        os.makedirs(ROOT, exist_ok=True)
        path = self._disk_path()
        with open(path, "wb") as f:
            f.write(data)
        self.send_response(201)
        self.send_header("Content-Length", "0")
        self.end_headers()
        print(f"[dav] PUT 201 {len(data)}B {path}", flush=True)

    def do_DELETE(self):
        if not self._require_auth():
            return
        path = self._disk_path()
        if os.path.isfile(path):
            os.remove(path)
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main():
    global AUTH_USER, AUTH_PASSWORD
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--user", default=AUTH_USER)
    ap.add_argument("--password", default=AUTH_PASSWORD)
    args = ap.parse_args()
    AUTH_USER, AUTH_PASSWORD = args.user, args.password
    os.makedirs(ROOT, exist_ok=True)
    print(f"[dav] root={ROOT} auth={AUTH_USER}/{AUTH_PASSWORD}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()

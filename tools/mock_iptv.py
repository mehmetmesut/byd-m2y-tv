"""Local fake IPTV provider for emulator tests: Xtream player_api, M3U, XMLTV, paced TS, HLS, MP4 with Range.
Emulator reaches it at http://10.0.2.2:8765. Users: u/p (small catalogue), big/p (20k live, 60k VOD, 5k series).
M3U: http://10.0.2.2:8765/get.php?username=u&password=p&type=m3u_plus

Test media goes in ./media (not committed), made with ffmpeg, for example:
  ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 180
         -c:v libx264 -b:v 1000k -g 25 -pix_fmt yuv420p -c:a aac -f mpegts live1.ts   (live2/live3: other sources)
  same inputs, -t 120 -movflags +faststart movie.mp4
  same inputs, -t 60 -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename hls/s%03d.ts hls/index.m3u8
  ffmpeg -f lavfi -i color=c=0x0A84FF:size=96x96 -frames:v 1 logo.png
"""
import base64
import json
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MEDIA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")
HOST = "http://10.0.2.2:8765"
USERS = {"u": "p", "big": "p"}
BIG = {"live": 20000, "vod": 60000, "series": 5000, "cats": 50}
LIVE_SECONDS = 180
# --no-short-epg: drop get_short_epg without an answer, like the user's panel (the app must fall back to xmltv.php)
NO_SHORT_EPG = "--no-short-epg" in sys.argv


def b64(s):
    return base64.b64encode(s.encode()).decode()


def live_streams(big):
    if big:
        return [{"num": i, "name": f"Kanal {i}", "stream_id": i, "stream_icon": f"{HOST}/logo/{i % 3}.png",
                 "epg_channel_id": f"ch{i}", "category_id": str(i % BIG["cats"] + 1), "added": "0"} for i in range(1, BIG["live"] + 1)]
    return [
        {"num": 1, "name": "TRT 1 Deneme", "stream_id": 1, "stream_icon": f"{HOST}/logo/1.png", "epg_channel_id": "trt1.tr", "category_id": "1"},
        {"num": 2, "name": "Spor Kanalı", "stream_id": 2, "stream_icon": f"{HOST}/logo/2.png", "epg_channel_id": "spor.tr", "category_id": "2"},
        {"num": 3, "name": "Haber Kanalı", "stream_id": 3, "stream_icon": None, "epg_channel_id": None, "category_id": "3"},
        {"num": 4, "name": "HLS Kanalı (uzantısız)", "stream_id": 4, "stream_icon": "", "epg_channel_id": "", "category_id": "1"},
    ]


def cats(big, prefix):
    if big:
        return [{"category_id": str(i), "category_name": f"{prefix} {i}", "parent_id": 0} for i in range(1, BIG["cats"] + 1)]
    return [{"category_id": "1", "category_name": "Ulusal"}, {"category_id": "2", "category_name": "Spor"}, {"category_id": "3", "category_name": "Haber"}]


def programmes(now, n=8):
    start = now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
    return [(start + timedelta(minutes=30 * k), start + timedelta(minutes=30 * (k + 1)), f"Program {k + 1}") for k in range(n)]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("%s %s\n" % (datetime.now().strftime("%H:%M:%S"), fmt % args))
        sys.stdout.flush()

    def send_json(self, obj):
        self.send_bytes(json.dumps(obj, ensure_ascii=False).encode(), "application/json")

    def send_bytes(self, data, ctype, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            self.route()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def route(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p == "/player_api.php":
            return self.api(q)
        if p == "/get.php":
            return self.m3u(q)
        if p in ("/epg.xml", "/xmltv.php"):
            return self.xmltv(q)
        if p.startswith("/logo/"):
            return self.file(os.path.join(MEDIA, "logo.png"), "image/png")
        if p.startswith("/hls/"):
            return self.file(os.path.join(MEDIA, "hls", os.path.basename(p)), "application/vnd.apple.mpegurl" if p.endswith(".m3u8") else "video/mp2t")
        parts = p.strip("/").split("/")
        if len(parts) == 4 and parts[0] in ("live", "movie", "series"):
            kind, user, pw, name = parts
            if USERS.get(user) != pw:
                return self.send_bytes(b"forbidden", "text/plain", 403)
            sid = name.split(".")[0]
            if kind == "live":
                if sid.startswith("s"):  # HLS segment relative to the extensionless channel URL
                    return self.file(os.path.join(MEDIA, "hls", name), "video/mp2t")
                n = int(sid)
                if n == 4:  # HLS playlist behind a .ts address: the app must fall back to HLS
                    return self.file(os.path.join(MEDIA, "hls", "index.m3u8"), "text/plain")
                return self.paced_ts(os.path.join(MEDIA, f"live{(n - 1) % 3 + 1}.ts"))
            return self.file(os.path.join(MEDIA, "movie.mp4"), "video/mp4")
        self.send_bytes(b"not found", "text/plain", 404)

    def api(self, q):
        user, pw = q.get("username"), q.get("password")
        if USERS.get(user) != pw:
            return self.send_json({"user_info": {"auth": 0}})
        big = user == "big"
        a = q.get("action")
        now = datetime.now(timezone.utc)
        if a is None:
            return self.send_json({"user_info": {"auth": 1, "status": "Active", "exp_date": "1893456000", "max_connections": "1",
                                                 "allowed_output_formats": ["m3u8", "ts"]}, "server_info": {"url": "10.0.2.2", "port": "8765"}})
        if a == "get_live_categories":
            return self.send_json(cats(big, "Paket"))
        if a == "get_vod_categories":
            return self.send_json(cats(big, "Film türü") if big else [{"category_id": "10", "category_name": "Aksiyon"}])
        if a == "get_series_categories":
            return self.send_json(cats(big, "Dizi türü") if big else [{"category_id": "20", "category_name": "Yerli"}])
        if a == "get_live_streams":
            return self.send_json(live_streams(big))
        if a == "get_vod_streams":
            n = BIG["vod"] if big else 1
            return self.send_json([{"num": i, "name": f"Deneme Filmi {i}", "stream_id": 100 + i, "stream_icon": f"{HOST}/logo/m{i % 5}.png",
                                    "rating": "7.5", "category_id": str(i % BIG["cats"] + 1) if big else "10", "container_extension": "mp4"}
                                   for i in range(1, n + 1)])
        if a == "get_series":
            n = BIG["series"] if big else 1
            return self.send_json([{"num": i, "name": f"Deneme Dizisi {i}", "series_id": 200 + i, "cover": f"{HOST}/logo/s.png",
                                    "rating": "8", "category_id": str(i % BIG["cats"] + 1) if big else "20"} for i in range(1, n + 1)])
        if a == "get_series_info":
            eps = {str(s): [{"id": str(3000 + s * 10 + e), "episode_num": e, "title": f"Bölüm {e}", "container_extension": "mp4"}
                            for e in range(1, 4)] for s in (1, 2)}
            return self.send_json({"info": {"plot": "Deneme dizisinin konusu."}, "episodes": eps})
        if a == "get_vod_info":
            return self.send_json({"info": {"plot": "Deneme filminin konusu.", "genre": "Aksiyon", "releasedate": "2024-05-01",
                                            "duration": "00:02:00"}, "movie_data": {}})
        if a == "get_short_epg" and NO_SHORT_EPG:
            self.close_connection = True
            return None
        if a == "get_short_epg":
            lim = int(q.get("limit", "4"))
            return self.send_json({"epg_listings": [{"title": b64(t), "description": b64(f"{t} açıklaması"),
                                                     "start_timestamp": str(int(s.timestamp())), "stop_timestamp": str(int(e.timestamp()))}
                                                    for s, e, t in programmes(now, lim)]})
        self.send_json([])

    def m3u(self, q):
        user, pw = q.get("username"), q.get("password")
        if USERS.get(user) != pw:
            return self.send_bytes(b"<html>Yetkisiz</html>", "text/html", 200)
        lines = [f'#EXTM3U url-tvg="{HOST}/epg.xml"']
        for s in live_streams(user == "big"):
            ext = "m3u8" if s["stream_id"] == 4 else "ts"
            path = f"{HOST}/hls/index.m3u8" if s["stream_id"] == 4 else f"{HOST}/live/{user}/{pw}/{s['stream_id']}.{ext}"
            grp = {"1": "Ulusal", "2": "Spor", "3": "Haber"}.get(s["category_id"], "Paket " + s["category_id"])
            lines.append(f'#EXTINF:-1 tvg-id="{s["epg_channel_id"] or ""}" tvg-logo="{s["stream_icon"] or ""}" group-title="{grp}",{s["name"]}')
            lines.append(path)
        lines += ['#EXTINF:-1 group-title="Filmler",Deneme Filmi', f"{HOST}/movie/{user}/{pw}/101.mp4",
                  '#EXTINF:-1 group-title="Diziler",Deneme Dizisi S01E01', f"{HOST}/series/{user}/{pw}/3011.mp4"]
        self.send_bytes(("\n".join(lines) + "\n").encode(), "audio/x-mpegurl")

    def xmltv(self, q):
        now = datetime.now(timezone.utc)
        out = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
        for ch in ("trt1.tr", "spor.tr", "unused.tr"):
            for s, e, t in programmes(now, 30):
                out.append(f'<programme start="{s:%Y%m%d%H%M%S} +0000" stop="{e:%Y%m%d%H%M%S} +0000" channel="{ch}">'
                           f"<title>{ch.split('.')[0].upper()} {t}</title><desc>{t} açıklaması</desc></programme>")
        out.append("</tv>")
        self.send_bytes("\n".join(out).encode(), "application/xml")

    def file(self, path, ctype):
        if not os.path.exists(path):
            return self.send_bytes(b"not found", "text/plain", 404)
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        code = 200
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a or 0)
            end = int(b) if b else size - 1
            code = 206
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def paced_ts(self, path):
        """Endless live feed: the file looped, sent at its own bitrate after a 3 s burst (like a real IPTV server)."""
        size = os.path.getsize(path)
        rate = size / LIVE_SECONDS
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        sent = 0
        t0 = time.time()
        burst = rate * 3
        while True:
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(65536 - 65536 % 188)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    ahead = (sent - burst) / rate - (time.time() - t0)
                    if ahead > 0:
                        time.sleep(ahead)


if __name__ == "__main__":
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer(("127.0.0.1", 8765), H)
    print("mock IPTV on 127.0.0.1:8765", flush=True)
    srv.serve_forever()

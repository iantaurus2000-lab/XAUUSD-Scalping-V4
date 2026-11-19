#!/usr/bin/env python3
"""Authenticated Rayyan MT5 Gateway relay for the APK and Rayyan4GatewayEA."""
from __future__ import annotations
import hmac
import json
import os
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

HOST = os.environ.get("RAYYAN_GATEWAY_BIND", "127.0.0.1")
PORT = int(os.environ.get("RAYYAN_GATEWAY_PORT", "8787"))
TOKEN = os.environ.get("RAYYAN_GATEWAY_TOKEN", "").strip()
DATA_DIR = Path(os.environ.get("RAYYAN_GATEWAY_DATA", "/var/lib/rayyan-gateway"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
DB = sqlite3.connect(str(DATA_DIR / "gateway.sqlite3"), check_same_thread=False)
DB.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
DB.commit()
LOCK = threading.RLock()
SYMBOLS = {"XAUUSDm", "BTCUSDm", "ETHUSDm"}
TIMEFRAMES = {"M1", "M5", "M15", "H1", "H4", "D1"}
SNAPSHOT_PREFIXES = (
    "broker_quote_", "mt5_bars_", "d1_", "powerfull_status_", "powerfull_range_pips_",
    "powerfull_low_", "powerfull_high_", "powerfull_bar_", "powerfull_state_at_", "powerfull_samples_",
    "powerfull_days_", "powerfull_pending_", "powerfull_positions_", "powerfull_order_",
    "powerfull_detail_", "powerfull_wins_", "powerfull_losses_", "powerfull_closed_deals_",
)
SNAPSHOT_EXACT = {
    "ea_last_poll_ms", "ea_order_state_id", "ea_order_state", "ea_order_state_ticket",
    "ea_order_state_symbol", "ea_order_state_side", "ea_order_state_price",
    "ea_order_state_snapshot", "ea_order_state_at", "order_status_id", "order_status",
    "order_status_detail", "order_status_at", "indicator_ack_id", "indicator_ack_at",
    "indicator_ea_last_poll_ms", "mt5_server_time_ms",
}

def now_ms() -> int:
    return int(time.time() * 1000)

def getv(key: str, default=None):
    with LOCK:
        row = DB.execute("SELECT v FROM kv WHERE k=?", (key,)).fetchone()
    if not row:
        return default
    try:
        return json.loads(row[0])
    except Exception:
        return default

def setv(values: dict) -> None:
    if not values:
        return
    rows = [(str(k), json.dumps(v, separators=(",", ":"), ensure_ascii=False)) for k, v in values.items()]
    with LOCK:
        DB.executemany("INSERT INTO kv(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", rows)
        DB.commit()

def snapshot() -> dict:
    with LOCK:
        rows = DB.execute("SELECT k,v FROM kv").fetchall()
    result = {}
    for key, raw in rows:
        if key in SNAPSHOT_EXACT or key.startswith(SNAPSHOT_PREFIXES):
            try:
                result[key] = json.loads(raw)
            except Exception:
                pass
    return result

def qvalue(query: dict, key: str, default: str = "") -> str:
    values = query.get(key, [])
    return values[0] if values else default

def safe_int(value: str, default=0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default

def safe_float(value: str, default=0.0) -> float:
    try:
        result = float(value)
        return result if result == result and abs(result) != float("inf") else default
    except (TypeError, ValueError):
        return default

def field(text: str, name: str) -> str:
    prefix = name + "="
    for part in (text or "").split(";"):
        if part.startswith(prefix):
            return part[len(prefix):]
    return ""

class Handler(BaseHTTPRequestHandler):
    server_version = "RayyanGateway/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.log_date_time_string(), fmt % args), flush=True)

    def respond(self, code: int, body: bytes, content_type="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def text(self, value: str, code=200):
        self.respond(code, value.encode("utf-8"))

    def json(self, value: dict, code=200):
        self.respond(code, json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8"),
                     "application/json; charset=utf-8")

    def authorized(self) -> bool:
        if not TOKEN:
            self.text("GATEWAY TOKEN NOT CONFIGURED", 503)
            return False
        supplied = self.headers.get("X-Rayyan-Token", "")
        if not hmac.compare_digest(supplied, TOKEN):
            self.text("UNAUTHORIZED", 401)
            return False
        return True

    def parsed(self):
        parts = urlsplit(self.path)
        return parts.path, parse_qs(parts.query, keep_blank_values=True)

    def read_body(self, maximum: int) -> bytes | None:
        length = safe_int(self.headers.get("Content-Length", ""), -1)
        if length < 0 or length > maximum:
            return None
        return self.rfile.read(length)

    def do_GET(self):
        if not self.authorized():
            return
        path, query = self.parsed()
        if path in ("/", "/health"):
            self.text("Rayyan gateway OK;server=LISTENING;port=%d;timeMs=%d" % (PORT, now_ms()))
        elif path == "/bridge/pull":
            self.json({"ok": True, "serverTimeMs": now_ms(), "values": snapshot()})
        elif path == "/next":
            now = now_ms()
            setv({"ea_last_poll_ms": now})
            command = str(getv("next", "") or "")
            command_id = field(command, "id")
            queued_at = safe_int(command_id, 0)
            if queued_at and now - queued_at > 15000:
                setv({"next": "", "order_status_id": command_id, "order_status": "EXPIRED",
                      "order_status_detail": "Perintah kedaluwarsa setelah 15 detik; periksa MT5 sebelum mengirim ulang",
                      "order_status_at": now})
                command = ""
            self.text(command)
        elif path == "/ack":
            command_id = qvalue(query, "id")
            current = str(getv("next", "") or "")
            if command_id and command_id == field(current, "id"):
                result = qvalue(query, "result") or "UNKNOWN"
                detail = qvalue(query, "detail") or "Hasil diterima dari EA MT5"
                setv({"next": "", "order_status_id": command_id, "order_status": result,
                      "order_status_detail": detail, "order_status_at": now_ms()})
                self.text("ACK OK")
            else:
                self.text("ACK STALE")
        elif path == "/indicator_next":
            setv({"indicator_ea_last_poll_ms": now_ms()})
            self.text(str(getv("indicator_next", "") or ""))
        elif path == "/indicator_ack":
            command_id = qvalue(query, "id")
            current = str(getv("indicator_next", "") or "")
            if command_id and command_id == field(current, "id"):
                setv({"indicator_next": "", "indicator_ack_id": command_id, "indicator_ack_at": now_ms()})
                self.text("INDICATOR ACK OK")
            else:
                self.text("INDICATOR ACK STALE")
        elif path == "/power_config":
            self.power_config(qvalue(query, "symbol"))
        elif path == "/power_state":
            self.power_state(query)
        elif path == "/d1_snapshot":
            self.d1_snapshot(query)
        elif path == "/state":
            self.state(query)
        elif path == "/quote":
            self.quote(query)
        elif path == "/chart/latest":
            target = DATA_DIR / "mt5_chart.png"
            if not target.is_file():
                self.text("CHART NOT READY", 404)
            else:
                data = target.read_bytes()
                self.respond(200, data, "image/png")
        else:
            self.text("NOT FOUND", 404)

    def do_POST(self):
        if not self.authorized():
            return
        path, _ = self.parsed()
        if path == "/bridge/push":
            raw = self.read_body(128 * 1024)
            try:
                payload = json.loads(raw or b"{}")
                values = payload.get("values", {})
                if not isinstance(values, dict):
                    raise ValueError("values must be an object")
                allowed = {}
                for key, value in values.items():
                    if key in ("next", "indicator_next") and isinstance(value, str):
                        if value:
                            queue_key = key
                            ack_key = "order_status_id" if key == "next" else "indicator_ack_id"
                            cmd_id = field(value, "id")
                            if cmd_id and str(getv(ack_key, "")) != cmd_id:
                                current = str(getv(queue_key, "") or "")
                                if not current or field(current, "id") == cmd_id:
                                    allowed[key] = value
                        continue
                    prefixes = (
                        "powerfull_enabled_", "powerfull_max_layers_", "powerfull_reforecast_",
                        "powerfull_quick_profit_", "powerfull_cross_candle_", "powerfull_lot_",
                        "powerfull_stop_loss_usd_", "powerfull_pip_size_",
                        "powerfull_min_profit_lock_points_", "broker_quote_point_",
                    )
                    if key.startswith(prefixes) and key.rsplit("_", 1)[-1] in SYMBOLS:
                        allowed[key] = value
                allowed["apk_last_sync_ms"] = now_ms()
                setv(allowed)
                self.text("BRIDGE PUSH OK")
            except Exception as exc:
                self.text("BRIDGE PUSH ERROR " + type(exc).__name__, 400)
        elif path == "/quote/upload":
            self.upload_quotes()
        elif path == "/bars/upload":
            self.upload_bars()
        elif path == "/chart/upload":
            raw = self.read_body(2_000_000)
            if not raw or len(raw) < 8 or raw[:8] != b"\x89PNG\r\n\x1a\n":
                self.text("CHART ERROR PNG", 400)
                return
            temp = DATA_DIR / "mt5_chart.tmp"
            target = DATA_DIR / "mt5_chart.png"
            temp.write_bytes(raw)
            temp.replace(target)
            setv({"mt5_chart_received_ms": now_ms(), "mt5_chart_bytes": len(raw)})
            self.text("CHART OK")
        else:
            self.text("NOT FOUND", 404)

    def power_config(self, symbol: str):
        if symbol not in SYMBOLS:
            self.text("symbol=INVALID;enabled=0;reason=UNSUPPORTED_SYMBOL")
            return
        requested = bool(getv("powerfull_enabled_" + symbol, False))
        max_layers = safe_int(str(getv("powerfull_max_layers_" + symbol, 5)), 5)
        reforecast = bool(getv("powerfull_reforecast_" + symbol, True))
        quick_profit = bool(getv("powerfull_quick_profit_" + symbol, True))
        cross_candle = bool(getv("powerfull_cross_candle_" + symbol, True))
        lot = safe_float(str(getv("powerfull_lot_" + symbol, 0.0)), 0.0)
        stop_usd = safe_float(str(getv("powerfull_stop_loss_usd_" + symbol, 0.0)), 0.0)
        point = safe_float(str(getv("broker_quote_point_" + symbol, "0")), 0.0)
        pip = safe_float(str(getv("powerfull_pip_size_" + symbol, point if point > 0 else 0.01)), 0.01)
        min_lock = safe_int(str(getv("powerfull_min_profit_lock_points_" + symbol, 5)), 5)
        minimum_lot = 0.10 if symbol in ("BTCUSDm", "ETHUSDm") else 0.01
        valid = (lot >= minimum_lot and stop_usd > 0 and pip > 0 and
                 1 <= max_layers <= 5 and 0 <= min_lock <= 100000)
        enabled = requested and valid
        reason = "READY" if enabled else ("DISABLED" if not requested else
                 ("STOP_LOSS_USD_REQUIRED" if stop_usd <= 0 else "CONFIG_INVALID"))
        answer = (
            "symbol=%s;enabled=%d;buyLimit=1;sellLimit=1;pendingLimit=0;maxLayers=%d;"
            "stopLossUSD=%.2f;reforecast=%d;quickProfit=%d;crossCandleHold=%d;"
            "lot=%.4f;lotStep=0.0100;pip=%.8f;stopPips=3.00;minDistancePoints=30;"
            "deviationPoints=20;maxPendingATR=1.50;minHoldCandles=0;"
            "reversalATRFactor=0.25;minProfitLockPoints=%d;reason=%s"
        ) % (symbol, int(enabled), max_layers, stop_usd, int(reforecast), int(quick_profit),
             int(cross_candle), lot, pip, min_lock, reason)
        self.text(answer)

    def power_state(self, query):
        symbol = qvalue(query, "symbol")
        if symbol not in SYMBOLS:
            self.text("POWER STATE ERROR SYMBOL", 400)
            return
        values = {"powerfull_status_" + symbol: qvalue(query, "status") or "UNKNOWN",
                  "powerfull_range_pips_" + symbol: qvalue(query, "range"),
                  "powerfull_low_" + symbol: qvalue(query, "low"),
                  "powerfull_high_" + symbol: qvalue(query, "high"),
                  "powerfull_bar_" + symbol: qvalue(query, "bar"),
                  "powerfull_samples_" + symbol: qvalue(query, "samples"),
                  "powerfull_days_" + symbol: qvalue(query, "days"),
                  "powerfull_pending_" + symbol: qvalue(query, "pending"),
                  "powerfull_positions_" + symbol: qvalue(query, "positions"),
                  "powerfull_order_kind_" + symbol: qvalue(query, "orderKind") or "NONE",
                  "powerfull_order_side_" + symbol: qvalue(query, "orderSide"),
                  "powerfull_order_ticket_" + symbol: qvalue(query, "orderTicket"),
                  "powerfull_order_entry_" + symbol: qvalue(query, "orderEntry") or "0",
                  "powerfull_detail_" + symbol: qvalue(query, "detail"),
                  "powerfull_wins_" + symbol: safe_int(qvalue(query, "wins")),
                  "powerfull_losses_" + symbol: safe_int(qvalue(query, "losses")),
                  "powerfull_closed_deals_" + symbol: safe_int(qvalue(query, "closedDeals")),
                  "powerfull_state_at_" + symbol: now_ms()}
        setv(values)
        self.text("POWER STATE OK")

    def d1_snapshot(self, query):
        symbol = qvalue(query, "symbol")
        algo = qvalue(query, "algo")
        if symbol not in SYMBOLS:
            self.text("D1 SNAPSHOT ERROR SYMBOL", 400)
            return
        if algo not in ("0", "1"):
            self.text("D1 SNAPSHOT ERROR ALGO", 400)
            return
        fields = ("status", "m15", "h1", "h4", "winrate", "wins", "losses", "entries",
                  "closed", "profit", "loss", "layers", "floating", "peak", "timer",
                  "open0", "open1", "open2", "open3")
        values = {"d1_symbol_" + symbol: symbol, "d1_algo_" + symbol: algo,
                  "d1_snapshot_at_" + symbol: now_ms()}
        for name in fields:
            values["d1_" + name + "_" + symbol] = qvalue(query, name)
        layers = qvalue(query, "layers")
        open_count = 0
        try:
            marker = layers.index("OPEN LAYERS")
            tail = layers[marker + len("OPEN LAYERS"):].strip()
            open_count = max(0, int(tail.split("/", 1)[0].split()[0]))
        except Exception:
            pass
        first_open = qvalue(query, "open0")
        side = "BUY" if "BUY" in first_open else ("SELL" if "SELL" in first_open else "")
        values.update({"powerfull_status_" + symbol: qvalue(query, "status"),
                       "powerfull_state_at_" + symbol: now_ms(),
                       "powerfull_wins_" + symbol: safe_int(qvalue(query, "wins")),
                       "powerfull_losses_" + symbol: safe_int(qvalue(query, "losses")),
                       "powerfull_closed_deals_" + symbol: safe_int(qvalue(query, "closed")),
                       "powerfull_positions_" + symbol: str(open_count),
                       "powerfull_order_side_" + symbol: side})
        setv(values)
        self.text("D1 SNAPSHOT OK")

    def state(self, query):
        command_id = qvalue(query, "id")
        state = qvalue(query, "state")
        if not command_id or state not in ("NONE", "PENDING", "POSITION"):
            self.text("STATE ERROR", 400)
            return
        symbol = qvalue(query, "symbol")
        values = {"ea_order_state_id": command_id, "ea_order_state": state,
                  "ea_order_state_ticket": qvalue(query, "ticket"),
                  "ea_order_state_symbol": symbol, "ea_order_state_side": qvalue(query, "side"),
                  "ea_order_state_price": qvalue(query, "price"),
                  "ea_order_state_snapshot": "|".join([command_id, state, qvalue(query, "ticket"),
                      symbol, qvalue(query, "side"), qvalue(query, "price")]),
                  "ea_order_state_at": now_ms()}
        setv(values)
        self.text("STATE OK")

    def quote(self, query):
        symbol = qvalue(query, "symbol")
        if symbol not in SYMBOLS:
            self.text("QUOTE ERROR SYMBOL", 400)
            return
        bid, ask = safe_float(qvalue(query, "bid")), safe_float(qvalue(query, "ask"))
        point, digits, tick_ms = safe_float(qvalue(query, "point")), safe_int(qvalue(query, "digits"), -1), safe_int(qvalue(query, "tickms"))
        if bid <= 0 or ask < bid or point <= 0 or not 0 <= digits <= 8 or tick_ms <= 0:
            self.text("QUOTE ERROR VALUES", 400)
            return
        setv({"broker_quote_bid_" + symbol: str(bid), "broker_quote_ask_" + symbol: str(ask),
              "broker_quote_point_" + symbol: str(point), "broker_quote_digits_" + symbol: digits,
              "broker_quote_tick_ms_" + symbol: tick_ms, "broker_quote_received_ms_" + symbol: now_ms()})
        self.text("QUOTE OK")

    def upload_quotes(self):
        raw = self.read_body(4096)
        try:
            records = (raw or b"").decode("utf-8").splitlines()
            if not records or len(records) > 3:
                raise ValueError("batch")
            values, seen = {}, set()
            for record in records:
                parts = dict(p.split("=", 1) for p in record.split(";") if "=" in p)
                symbol = parts.get("symbol", "")
                bid, ask = safe_float(parts.get("bid")), safe_float(parts.get("ask"))
                point, digits, tick_ms = safe_float(parts.get("point")), safe_int(parts.get("digits"), -1), safe_int(parts.get("tickms"))
                if symbol not in SYMBOLS or symbol in seen or bid <= 0 or ask < bid or point <= 0 or not 0 <= digits <= 8 or tick_ms <= 0:
                    raise ValueError("quote values")
                seen.add(symbol)
                values.update({"broker_quote_bid_" + symbol: str(bid), "broker_quote_ask_" + symbol: str(ask),
                               "broker_quote_point_" + symbol: str(point), "broker_quote_digits_" + symbol: digits,
                               "broker_quote_tick_ms_" + symbol: tick_ms, "broker_quote_received_ms_" + symbol: now_ms()})
            if not seen:
                raise ValueError("empty")
            setv(values)
            self.text("QUOTE OK COUNT=" + str(len(seen)))
        except Exception as exc:
            self.text("QUOTE ERROR " + str(exc), 400)

    def upload_bars(self):
        raw = self.read_body(250000)
        try:
            records = (raw or b"").decode("utf-8").splitlines()
            if len(records) < 1 or len(records) > 18:
                raise ValueError("batch")
            values, seen, total = {}, set(), 0
            for record in records:
                parts = dict(p.split("=", 1) for p in record.split(";") if "=" in p)
                symbol, tf, bars = parts.get("symbol", ""), parts.get("tf", ""), parts.get("bars", "")
                key = symbol + "_" + tf
                if symbol not in SYMBOLS or tf not in TIMEFRAMES or key in seen:
                    raise ValueError("symbol/timeframe")
                rows = bars.split("|")
                maximum = 250 if tf in ("M1", "M5") else 60
                if not 2 <= len(rows) <= maximum:
                    raise ValueError("count " + key)
                previous = 0
                for row in rows:
                    v = row.split(",")
                    if len(v) != 5:
                        raise ValueError("format " + key)
                    t = safe_int(v[0], -1)
                    o, h, lo, c = (safe_float(x, -1) for x in v[1:])
                    if t <= previous or min(o, h, lo, c) <= 0 or h < max(o, c) or lo > min(o, c) or h < lo:
                        raise ValueError("values " + key)
                    previous = t
                seen.add(key)
                received = now_ms()
                values.update({"mt5_bars_" + key: bars, "mt5_bars_at_" + key: received,
                               "mt5_bars_count_" + key: len(rows)})
                server_time = safe_int(parts.get("serverTime", "0"))
                if server_time > 0:
                    values["mt5_server_time_ms"] = server_time
                total += len(rows)
            setv(values)
            self.text("BARS OK SERIES=%d CANDLES=%d" % (len(seen), total))
        except Exception as exc:
            self.text("BARS ERROR " + str(exc), 400)

if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("RAYYAN_GATEWAY_TOKEN must be set; refusing to start an unauthenticated gateway")
    if not HOST.startswith("127.") and HOST != "::1":
        raise SystemExit("Gateway must bind to loopback; use cloudflared tunnel to expose it securely")
    print("Rayyan Gateway listening on %s:%d" % (HOST, PORT), flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()

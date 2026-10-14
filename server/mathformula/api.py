"""Read-only HTTPS-behind-proxy HTTP API. Do NOT expose the backend port publicly."""
import json,os,threading,logging,time,hmac
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs
from .storage import db
from .alpha30 import VERSION
LOG=logging.getLogger("mathformula")
def _rows(conn,sql,params=()):
    return [dict(x) for x in conn.execute(sql,params)]
class Api(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,addr,path,scanner,reader_token):
        super().__init__(addr,Handler)
        self.store_path=path;self.scanner=scanner;self.reader_token=reader_token
class Handler(BaseHTTPRequestHandler):
    def log_message(self,format,*args):pass # do not log authorization headers
    def _send(self,code,object_):
        payload=json.dumps(object_,separators=(',',':'),allow_nan=False).encode()
        self.send_response(code)
        self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Cache-Control","no-store")
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("Content-Length",str(len(payload)))
        self.end_headers();self.wfile.write(payload)
    def do_POST(self):self._send(405,{"error":"read-only API"})
    def do_PUT(self):self.do_POST()
    def do_DELETE(self):self.do_POST()
    def do_GET(self):
        path=urlparse(self.path).path
        if path=="/healthz":
            self._send(200,{"service":"MathFormula scanner","alive":True,"notTradeService":True});return
        auth=self.headers.get("Authorization","")
        received=auth[7:] if auth.startswith("Bearer ") else ""
        if not received or not hmac.compare_digest(received,self.server.reader_token):
            self._send(401,{"error":"Valid read-only bearer token required"});return
        try:
            with db(self.server.store_path) as conn:
                if path=="/v1/status":
                    scan=conn.execute("SELECT * FROM candidate_scans ORDER BY id DESC LIMIT 1").fetchone()
                    universe=conn.execute("SELECT COUNT(*) n FROM universe").fetchone()["n"]
                    history=conn.execute("SELECT COUNT(DISTINCT day) n FROM eod").fetchone()["n"]
                    active=conn.execute("SELECT COUNT(*) n FROM ltp WHERE observed_ms>=?",(int(time.time()*1000)-20*60*1000,)).fetchone()["n"]
                    obs=conn.execute("SELECT MAX(observed_ms) n FROM ltp").fetchone()["n"]
                    self._send(200,{"modelVersion":VERSION,"scannerVersion":"server-v1.0",
                       "readOnly":True,"universeSymbols":universe,"historicalTradingDays":history,
                       "freshLtpSymbols":active,"lastLtpObservedMs":obs,
                       "latestScan":dict(scan) if scan else None,
                       "lastError":self.server.scanner.latest_error,
                       "fullModelLive":False,"researchOnly":True,
                       "fullUniverseClaimsValid":universe>=3000 and active>=int(universe*.9) and history>=60})
                    return
                if path=="/v1/watchlist":
                    rows=_rows(conn,"""SELECT c.*,q.trade_ms,q.observed_ms quote_observed_ms,q.vwap,q.volume,q.bid,q.ask,
                      q.price confirmed_quote_price
                      FROM candidates c
                      LEFT JOIN quotes q ON q.symbol=c.symbol
                      WHERE c.day=(SELECT MAX(day) FROM candidates)
                      AND c.window=(SELECT window FROM candidates WHERE day=(SELECT MAX(day) FROM candidates)
                        ORDER BY created_ms DESC LIMIT 1)
                      ORDER BY c.rank_score DESC LIMIT 30""")
                    for r in rows:
                        r["details"]=json.loads(r["details"])
                        r["breakoutLevel"]=r.pop("reference")
                        r["preliminaryRank"]=r.pop("rank_score")
                        r["alpha30Score"]=r.pop("alpha_score")
                        r["quoteStale"]=not (r.get("trade_ms") and 0<=int(time.time()*1000)-r["trade_ms"]<=10_000)
                        r["signal"]="NO SIGNAL — full Alpha-30 execution factors not verified"
                    self._send(200,{"modelVersion":VERSION,"dataSource":"Groww LTP + imported dated NSE EOD",
                       "count":len(rows),"stocks":rows,"researchOnly":True});return
                if path=="/v1/preclose":
                    rows=_rows(conn,"""SELECT c.* FROM candidates c
                      WHERE c.day=(SELECT day FROM candidate_scans WHERE window LIKE 'PRE_CLOSE%'
                         ORDER BY at_ms DESC LIMIT 1)
                        AND c.window=(SELECT window FROM candidate_scans WHERE window LIKE 'PRE_CLOSE%'
                         ORDER BY at_ms DESC LIMIT 1)
                      ORDER BY c.rank_score DESC LIMIT 30""")
                    for r in rows:r["details"]=json.loads(r["details"])
                    self._send(200,{"modelVersion":VERSION,"stocks":rows,"researchOnly":True});return
                if path=="/v1/events":
                    now=int(time.time()*1000)
                    rows=_rows(conn,"SELECT symbol,event_type,title,published_ms,scheduled_day,source_url FROM events WHERE published_ms<=? ORDER BY published_ms DESC LIMIT 100",(now,))
                    self._send(200,{"items":rows,"source":"Imported, timestamped filings; no auto-scraping configured"});return
                if path=="/v1/misses":
                    items=_rows(conn,"SELECT * FROM outcomes ORDER BY day DESC,peak_return_pct DESC LIMIT 150")
                    done=conn.execute("SELECT COUNT(*) n FROM outcomes").fetchone()["n"]
                    self._send(200,{"items":items,"measuredMajorMovers":done,
                        "rule":"EOD baseline to next five completed EOD sessions' high >=+10%",
                        "selection":"Saved candidate universe, at original as-of time"});return
                if path=="/v1/audit":
                    limit=min(100,max(1,int(parse_qs(urlparse(self.path).query).get("limit",["40"])[0])))
                    rows=_rows(conn,"SELECT * FROM audit ORDER BY id DESC LIMIT ?",(limit,))
                    for r in rows:r["details"]=json.loads(r["details"])
                    self._send(200,{"items":rows});return
            self._send(404,{"error":"Unknown route"})
        except Exception:
            LOG.exception("Server handler error")
            self._send(500,{"error":"Research data temporarily unavailable"})
def serve(path,scanner,token,host="0.0.0.0",port=8765):
    server=Api((host,int(port)),path,scanner,token)
    thread=threading.Thread(target=scanner.run_forever,daemon=True)
    thread.start()
    try:
        LOG.info("MathFormula API listening; place HTTPS reverse proxy in front")
        server.serve_forever(poll_interval=.5)
    finally:
        scanner.stop_event.set();server.server_close();thread.join(timeout=10)

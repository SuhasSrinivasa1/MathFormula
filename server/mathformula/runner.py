"""NSE discovery worker. Uses broker API only for *read-only* market observations."""
import os,time,json,threading,logging,urllib.request
from datetime import datetime,date,time as clock
from pathlib import Path
from zoneinfo import ZoneInfo
from .storage import db,ms,audit,import_universe,import_eod,import_events,import_factors,import_execution_evidence
from .recommendations import record_discovery,reevaluate
from .discovery import shortlist
from .groww import Groww,GrowwError
from .nse_import import fetch_universe,fetch_daily
IST=ZoneInfo("Asia/Kolkata")
LOG=logging.getLogger("mathformula")
def local_now():return datetime.now(IST)
def _csv_files(folder):
    if not folder or not Path(folder).is_dir():return []
    return sorted(Path(folder).glob("*.csv"))
class Scanner:
    def __init__(self,path):
        self.path=path
        self.groww=Groww()
        self.imported={}
        self.last_jobs={}
        self.stop_event=threading.Event()
        self.lock=threading.Lock()
        self.latest_error=""
        self.batch_delay=float(os.getenv("BATCH_PAUSE_SECONDS","0.10"))
        self.min_value=float(os.getenv("MIN_AVG_TRADED_VALUE_INR","10000000"))
        self.nse_holidays=set(x.strip() for x in os.getenv("NSE_HOLIDAYS","").split(",") if x.strip())
        self.webhook=os.getenv("ALERT_WEBHOOK_URL","").strip()
        self.webhook_token=os.getenv("ALERT_WEBHOOK_TOKEN","").strip()
        self.poll_input_files()
    def poll_input_files(self):
        source=[("universe",os.getenv("UNIVERSE_FILE",""),import_universe)]
        source +=[("eod",str(x),import_eod) for x in _csv_files(os.getenv("EOD_DIR","/data/eod"))]
        source +=[("events",os.getenv("EVENTS_FILE",""),import_events),
                  ("factors",os.getenv("FACTORS_FILE",""),import_factors),
                  ("execution",os.getenv("EXECUTION_EVIDENCE_FILE",""),import_execution_evidence)]
        if not source:return
        with db(self.path) as conn:
            for name,file,fn in source:
                if not file or not Path(file).is_file():continue
                stamp=(Path(file).stat().st_mtime_ns,Path(file).stat().st_size)
                if self.imported.get(file)==stamp:continue
                try:
                    count=fn(conn,file)
                    self.imported[file]=stamp
                    LOG.info("Imported %d rows from %s",count,Path(file).name)
                except Exception as exc:
                    self.latest_error=f"{name} import: {type(exc).__name__}"
                    LOG.error("Input file import failed: %s (%s)",Path(file).name,type(exc).__name__)
    def allowed(self,now):
        return now.weekday()<5 and now.date().isoformat() not in self.nse_holidays and (
            clock(9,15)<=now.time()<clock(15,30))
    def due(self,key,now,at_minute):
        label=(now.date().isoformat(),key,now.hour,now.minute)
        if not at_minute:return False
        if self.last_jobs.get(key)==label:return False
        self.last_jobs[key]=label
        return True
    def scan_universe_quotes(self,now):
        with db(self.path) as conn:
            symbols=[x["symbol"] for x in conn.execute("SELECT symbol FROM universe ORDER BY symbol")]
        if not symbols:
            self.latest_error="No NSE EQ universe loaded; mount a verified NSE/Groww instruments CSV"
            return
        good=0;failed=0
        with db(self.path) as conn:
            for at in range(0,len(symbols),50):
                batch=symbols[at:at+50]
                try:
                    prices=self.groww.batch_ltp(batch);observed=ms()
                    conn.executemany("""INSERT INTO ltp(symbol,price,observed_ms,source) VALUES(?,?,?,?)
                      ON CONFLICT(symbol) DO UPDATE SET price=excluded.price,
                        observed_ms=excluded.observed_ms,source=excluded.source""",
                      [(s,p,observed,"Groww LTP (exchange trade time unavailable)") for s,p in prices.items()])
                    good+=len(prices)
                except Exception as ex:
                    failed+=len(batch);self.latest_error="Groww LTP batch failed: "+type(ex).__name__
                    LOG.warning("LTP batch %d failed: %s",at//50,type(ex).__name__)
                if self.batch_delay:time.sleep(self.batch_delay)
            audit(conn,"BROAD_QUOTES","","NSE universe LTP refresh",
                  {"universe":len(symbols),"observedPrices":good,"missingOrFailed":len(symbols)-good,
                   "exchangeQuoteTimestampsAvailable":False,"time":now.isoformat()})
            conn.commit()
        LOG.info("Broad-market LTP snapshot: %d/%d instruments (not Alpha-30)",good,len(symbols))
    def discover(self,window,now):
        stamp=ms();day=now.date().isoformat()
        with db(self.path) as conn:
            result=shortlist(conn,window,day,stamp,
                top_n=int(os.getenv("SHORTLIST_SIZE","30")),min_traded_value=self.min_value)
            scan=conn.execute("""INSERT INTO candidate_scans(day,window,at_ms,universe_total,prices_updated,eligible,reason,scanner_version)
              VALUES(?,?,?,?,?,?,?,?)""",
              (day,window,stamp,result["universe"],
                conn.execute("SELECT COUNT(*) n FROM ltp WHERE observed_ms>=?",(stamp-20*60*1000,)).fetchone()["n"],
                result["eligible"],"Technical DISCOVERY ONLY; Alpha-30 BUY not evaluated","discovery-v1.0")).lastrowid
            for rank,(h,s,p,ref,stage,ascore,coverage,details) in enumerate(result["items"],1):
                details["rank"]=rank
                conn.execute("""INSERT OR REPLACE INTO candidates(day,window,symbol,created_ms,price,reference,
                  rank_score,stage,alpha_score,coverage_pct,details) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    (day,window,s,stamp,p,ref,round(h,4),stage,ascore,coverage,json.dumps(details,separators=(',',':'))))
            record_discovery(conn,result["items"],stamp)
            audit(conn,"SHORTLIST","","Discovery window complete",
              {"window":window,"universe":result["universe"],"eligible":result["eligible"],
               "selected":len(result["items"]),"scanId":scan})
            conn.commit()
        if window.startswith("PRE_CLOSE"):
            self.send_webhook({"kind":"PRE_CLOSE_WATCH","window":window,
                "count":len(result["items"]),"observedAt":stamp,
                "message":"Pre-close research candidates. NO BUY signal.","symbols":[x[1] for x in result["items"][:5]]})
        return result
    def quote_shortlist(self,now):
        with db(self.path) as conn:
            latest=conn.execute("SELECT day,window FROM candidate_scans WHERE day=? ORDER BY at_ms DESC LIMIT 1",
                (now.date().isoformat(),)).fetchone()
            picked=[r["symbol"] for r in conn.execute(
                "SELECT symbol FROM candidates WHERE day=? AND window=? ORDER BY rank_score DESC LIMIT 30",
                (latest["day"],latest["window"]))] if latest else []
            for entry in conn.execute("SELECT symbol FROM recommendations WHERE closed_ms IS NULL ORDER BY last_seen_ms DESC LIMIT 100"):
                if entry["symbol"] not in picked:picked.append(entry["symbol"])
        if not picked:return
        changed=0
        with db(self.path) as conn:
            for symbol in dict.fromkeys(picked):
                try:
                    quote=self.groww.quote(symbol)
                    conn.execute("""INSERT OR REPLACE INTO quotes(symbol,price,trade_ms,observed_ms,vwap,volume,bid,ask,source)
                         VALUES (?,?,?,?,?,?,?,?,?)""",
                         (symbol,quote["price"],quote["tradeMs"],quote["observedMs"],quote["vwap"],
                         quote["volume"],quote["bid"],quote["ask"],quote["source"]))
                    changed+=1
                except Exception as exc:
                    LOG.warning("Shortlist quote %s unavailable: %s",symbol,type(exc).__name__)
            conn.commit()
        LOG.info("Shortlist quotes refreshed %d/%d",changed,len(picked))
        with db(self.path) as conn:
            signals=reevaluate(conn,ms(),self.allowed(local_now()))
        for item in signals:
            self.send_webhook(item)
    def send_webhook(self,event):
        if not self.webhook or not self.webhook.startswith("https://"):return
        try:
            body=json.dumps(event).encode()
            headers={"Content-Type":"application/json"}
            if self.webhook_token:headers["Authorization"]="Bearer "+self.webhook_token
            request=urllib.request.Request(self.webhook,data=body,headers=headers,method="POST")
            with urllib.request.urlopen(request,timeout=6) as response:response.read(256)
        except Exception as exc:LOG.warning("Alert webhook failed (%s)",type(exc).__name__)
    def update_outcomes(self,now):
        """Evaluate 5 *completed* subsequent EOD sessions, including major movers not shortlisted."""
        count=0;misses=0
        with db(self.path) as conn:
            # Only dates with at least five distinct subsequent market sessions qualify.
            dates=[r["day"] for r in conn.execute("SELECT DISTINCT day FROM candidate_scans ORDER BY day DESC LIMIT 30")]
            for day in dates:
                sessions=[r["day"] for r in conn.execute("SELECT DISTINCT day FROM eod WHERE day>? ORDER BY day LIMIT 5",(day,))]
                if len(sessions)<5:continue
                expiry=sessions[4]
                sample=[r for r in conn.execute("SELECT symbol,close FROM eod WHERE day=?",(day,))]
                selected={r["symbol"] for r in conn.execute("SELECT symbol FROM candidates WHERE day=? AND window LIKE 'PRE_CLOSE%'",(day,))}
                for current in sample:
                    s=current["symbol"];entry=current["close"]
                    if not entry or entry<=0:continue
                    future=[r for r in conn.execute("SELECT day,high,close FROM eod WHERE symbol=? AND day>? AND day<=? ORDER BY day",(s,day,expiry))]
                    if len(future)<5:continue
                    gain=100*(max(r["high"] for r in future)/entry-1)
                    end=100*(future[-1]["close"]/entry-1)
                    if gain<10:continue
                    captured=int(s in selected)
                    conn.execute("""INSERT OR REPLACE INTO outcomes(day,window,symbol,horizon,peak_return_pct,
                        close_return_pct,captured,computed_ms) VALUES(?,?,?,?,?,?,?,?)""",
                        (day,"PRE_CLOSE",s,5,round(gain,3),round(end,3),captured,ms()))
                    count+=1;misses+=not captured
            if count:audit(conn,"OUTCOMES","","Five-session NSE movers reviewed",
                  {"majorMovers":count,"notShortlisted":misses,"definition":"+10% observed high within next 5 completed sessions"})
            conn.commit()
        return count,misses
    def run_tick(self):
        now=local_now()
        # Re-check imported files once per minute, even after close.
        if self.due("files",now,True):self.poll_input_files()
        if os.getenv("AUTO_NSE_IMPORT","0")=="1":
            if self.due("nse_universe",now,(now.hour,now.minute)==(8,0)):
                try:
                    target=os.getenv("UNIVERSE_FILE","/data/universe.csv")
                    count=fetch_universe(target)
                    LOG.info("Official NSE equity list downloaded: %d raw rows",count)
                    self.poll_input_files()
                except Exception as exc:
                    self.latest_error="NSE instruments auto-import unavailable: "+type(exc).__name__
                    LOG.warning("NSE universe auto-fetch unavailable (%s)",type(exc).__name__)
            if self.due("nse_eod",now,(now.hour,now.minute) in {(16,45),(17,45),(19,15)}):
                today=now.date()
                try:
                    count=fetch_daily(today,os.getenv("EOD_DIR","/data/eod"))
                    LOG.info("Official NSE EOD %s fetched %d EQ rows",today.isoformat(),count)
                    self.poll_input_files()
                except Exception as exc:
                    self.latest_error="NSE EOD not published or not accessible: "+type(exc).__name__
                    LOG.warning("NSE bhavdata unavailable (%s)",type(exc).__name__)
        if self.allowed(now):
            accelerated=now.time()>=clock(14,30)
            every=5 if accelerated else 15
            if self.due("breadth",now,now.minute%every==0):
                self.scan_universe_quotes(now)
                self.discover("INTRADAY_"+now.strftime("%H%M"),now)
            if self.due("detail",now,True):self.quote_shortlist(now)
            if self.due("preclose",now,(now.hour,now.minute) in
                        {(14,45),(15,0),(15,15),(15,25)}):
                self.discover("PRE_CLOSE_"+now.strftime("%H%M"),now)
        # EOD scan imports newly provided official closing data, then writes a recorded postclose snapshot.
        if self.due("eod",now,(now.hour,now.minute) in {(16,15),(19,30)}):
            self.discover("AFTER_CLOSE_"+now.strftime("%H%M"),now)
            self.update_outcomes(now)
        if self.due("outcomes",now,(now.hour,now.minute)==(20,0)):
            self.update_outcomes(now)
    def run_forever(self):
        LOG.info("MathFormula scanner started: NSE EQ only, no trades or auto-BUY")
        while not self.stop_event.is_set():
            start=time.monotonic()
            try:self.run_tick()
            except Exception as exc:
                self.latest_error=type(exc).__name__
                LOG.exception("Scan error")
                with db(self.path) as conn:
                    audit(conn,"ERROR","","Scanner tick failed",{"errorType":type(exc).__name__})
                    conn.commit()
            self.stop_event.wait(max(1,10-(time.monotonic()-start)))

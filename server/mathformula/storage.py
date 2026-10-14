"""SQLite persistence, ingestion and point-in-time monitoring audit."""
import csv,io,os,sqlite3,time,json,hashlib
from datetime import date,datetime,timezone
from pathlib import Path

SCHEMA="""
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS universe(symbol TEXT PRIMARY KEY,name TEXT NOT NULL DEFAULT '',series TEXT NOT NULL DEFAULT 'EQ',loaded_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS eod(symbol TEXT NOT NULL,day TEXT NOT NULL,open REAL,high REAL NOT NULL,low REAL,close REAL NOT NULL,
volume REAL NOT NULL,turnover_inr REAL,source TEXT NOT NULL,imported_ms INTEGER NOT NULL,
PRIMARY KEY(symbol,day));
CREATE INDEX IF NOT EXISTS idx_eod_date ON eod(day);
CREATE TABLE IF NOT EXISTS ltp(symbol TEXT PRIMARY KEY,price REAL NOT NULL,observed_ms INTEGER NOT NULL,source TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS quotes(symbol TEXT PRIMARY KEY,price REAL,trade_ms INTEGER,observed_ms INTEGER,vwap REAL,
volume REAL,bid REAL,ask REAL,source TEXT);
CREATE TABLE IF NOT EXISTS factors(symbol TEXT NOT NULL,factor INTEGER NOT NULL,value REAL NOT NULL,
observed_ms INTEGER NOT NULL,source TEXT NOT NULL,PRIMARY KEY(symbol,factor));
CREATE TABLE IF NOT EXISTS events(event_id TEXT PRIMARY KEY,symbol TEXT NOT NULL,published_ms INTEGER,scheduled_day TEXT,
event_type TEXT NOT NULL,title TEXT NOT NULL,source_url TEXT NOT NULL,imported_ms INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS candidate_scans(id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL,window TEXT NOT NULL,
at_ms INTEGER NOT NULL,universe_total INTEGER NOT NULL,prices_updated INTEGER NOT NULL,eligible INTEGER NOT NULL,
reason TEXT NOT NULL,scanner_version TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS candidates(day TEXT NOT NULL,window TEXT NOT NULL,symbol TEXT NOT NULL,
created_ms INTEGER NOT NULL,price REAL NOT NULL,reference REAL NOT NULL,rank_score REAL NOT NULL,
stage TEXT NOT NULL,alpha_score REAL,coverage_pct REAL,details TEXT NOT NULL,PRIMARY KEY(day,window,symbol));
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,at_ms INTEGER NOT NULL,type TEXT NOT NULL,
symbol TEXT NOT NULL,message TEXT NOT NULL,details TEXT NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS recommendations(
 id TEXT PRIMARY KEY,symbol TEXT NOT NULL,opened_day TEXT NOT NULL,opened_ms INTEGER NOT NULL,
 last_seen_ms INTEGER NOT NULL,last_scan_ms INTEGER NOT NULL,reference REAL NOT NULL,research_rank INTEGER NOT NULL,
 status TEXT NOT NULL,decision TEXT NOT NULL,price REAL,quote_ms INTEGER,score REAL,coverage_pct REAL,
 reasons TEXT NOT NULL DEFAULT '[]',factor_evidence TEXT NOT NULL DEFAULT '[]',
 execution_evidence TEXT NOT NULL DEFAULT '{}',evaluated_ms INTEGER,
 closed_ms INTEGER,close_code TEXT,close_reason TEXT);
CREATE INDEX IF NOT EXISTS idx_recommendations_active ON recommendations(symbol,closed_ms);
CREATE TABLE IF NOT EXISTS recommendation_events(
 id INTEGER PRIMARY KEY AUTOINCREMENT,recommendation_id TEXT NOT NULL,symbol TEXT NOT NULL,
 at_ms INTEGER NOT NULL,previous_status TEXT NOT NULL,next_status TEXT NOT NULL,
 details TEXT NOT NULL DEFAULT '{}');
CREATE TABLE IF NOT EXISTS execution_evidence(
 symbol TEXT PRIMARY KEY,observed_ms INTEGER NOT NULL,source TEXT NOT NULL,
 breakout15m INTEGER NOT NULL,confirmation5m INTEGER NOT NULL,
 vwap5m INTEGER NOT NULL,vwap15m INTEGER NOT NULL,
 rvol REAL,sector_positive INTEGER NOT NULL,liquidity_verified INTEGER NOT NULL,
 spread_bps REAL,net_rr REAL,entry REAL,stop_loss REAL,target REAL,
 trading_allowed INTEGER NOT NULL,material_risk_clear INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS outcomes(day TEXT NOT NULL,window TEXT NOT NULL,symbol TEXT NOT NULL,
horizon INTEGER NOT NULL,peak_return_pct REAL NOT NULL,close_return_pct REAL NOT NULL,
captured INTEGER NOT NULL,computed_ms INTEGER NOT NULL,PRIMARY KEY(day,window,symbol,horizon));
"""
def ms(): return int(time.time()*1000)
class _ClosingConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc, tb):
        try:return super().__exit__(exc_type,exc,tb)
        finally:self.close()
def db(path):
    Path(path).parent.mkdir(parents=True,exist_ok=True)
    conn=sqlite3.connect(path,timeout=30,factory=_ClosingConnection)
    conn.row_factory=sqlite3.Row
    conn.executescript(SCHEMA)
    return conn
def audit(conn,kind,symbol,message,details=None):
    conn.execute("INSERT INTO audit(at_ms,type,symbol,message,details) VALUES (?,?,?,?,?)",
                 (ms(),kind,symbol,message,json.dumps(details or {},separators=(',',':'))))
def _upper(row):
    return {''.join(c for c in str(k).upper() if c.isalnum()): (v.strip() if isinstance(v,str) else v)
            for k,v in row.items() if k is not None}
def _get(row,*names,default=""):
    for name in names:
        key=''.join(c for c in name.upper() if c.isalnum())
        if row.get(key) not in (None,''): return row[key]
    return default
def import_universe(conn,path):
    """Accept official NSE EQUITY_L.csv or Groww instruments CSV; EQ cash equities only."""
    count=0; seen=set(); now=ms()
    with open(path,newline='',encoding='utf-8-sig') as f:
        for raw in csv.DictReader(f):
            r=_upper(raw)
            exchange=_get(r,"EXCHANGE",default="NSE").upper()
            series=_get(r,"SERIES",default="EQ").upper()
            segment=_get(r,"SEGMENT",default="CASH").upper()
            symbol=_get(r,"SYMBOL","TRADING_SYMBOL","SECURITYID").upper()
            if exchange!="NSE" or segment!="CASH" or series!="EQ" or not symbol or symbol in seen:continue
            if not all(c.isalnum() or c in '-&' for c in symbol):continue
            name=_get(r,"NAME OF COMPANY","NAME","COMPANY NAME",default=symbol)
            conn.execute("INSERT INTO universe(symbol,name,series,loaded_ms) VALUES(?,?,?,?) ON CONFLICT(symbol) DO UPDATE SET name=excluded.name,loaded_ms=excluded.loaded_ms",(symbol,name,series,now))
            seen.add(symbol);count+=1
    if count:
        conn.execute("DELETE FROM universe WHERE loaded_ms<>?",(now,))
    audit(conn,"UNIVERSE","","Universe import",{"count":count,"file":Path(path).name})
    conn.commit()
    return count
def _numeric(v,default=None):
    try:
        out=float(str(v).replace(',',''))
        if out>=0 and out<1e16: return out
    except (ValueError,TypeError):pass
    return default
def import_eod(conn,path):
    """Supported explicit CSV with symbol,date,open,high,low,close,volume,turnover_inr.
    NSE data can be converted offline to this schema. No fabricated zero turnover."""
    count=0; now=ms()
    with open(path,newline='',encoding='utf-8-sig') as f:
        for raw in csv.DictReader(f):
            r=_upper(raw)
            if _get(r,"SERIES",default="EQ").upper()!="EQ":continue
            symbol=_get(r,"SYMBOL","SECURITYID").upper()
            day=_get(r,"DATE","TRADINGDATE","TIMESTAMP")
            if not symbol or not day:continue
            try:
                day=date.fromisoformat(day[:10]).isoformat()
            except ValueError: continue
            o,h,l,c,v=(_numeric(_get(r,k)) for k in ("OPEN","HIGH","LOW","CLOSE","VOLUME"))
            if h is None or c is None or v is None or h<=0 or c<=0:continue
            turnover=_numeric(_get(r,"TURNOVER_INR","TURNOVER","TRADEDVALUE"),default=None)
            conn.execute("""INSERT INTO eod(symbol,day,open,high,low,close,volume,turnover_inr,source,imported_ms)
                VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(symbol,day) DO UPDATE SET
                open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,
                volume=excluded.volume,turnover_inr=excluded.turnover_inr,source=excluded.source,
                imported_ms=excluded.imported_ms""",(symbol,day,o,h,l,c,v,turnover,Path(path).name,now))
            count+=1
    audit(conn,"EOD","","EOD import",{"count":count,"file":Path(path).name})
    conn.commit()
    return count
def import_events(conn,path):
    """Require genuine origin/source URL and a publication time; scheduled events are optional."""
    count=0; now=ms()
    with open(path,newline='',encoding='utf-8-sig') as f:
        for raw in csv.DictReader(f):
            r=_upper(raw);sym=_get(r,"SYMBOL").upper()
            source=_get(r,"SOURCE_URL");typ=_get(r,"EVENT_TYPE",default="UNKNOWN")
            title=_get(r,"TITLE",default=typ)
            published=_get(r,"PUBLISHED_AT_UTC")
            scheduled=_get(r,"SCHEDULED_DATE")
            if not sym or not source.startswith("https://") or not published:continue
            try:
                ts=datetime.fromisoformat(published.replace('Z','+00:00'))
                if ts.tzinfo is None:continue
                public_ms=int(ts.timestamp()*1000)
                if public_ms>now+60000:continue
                if scheduled: scheduled=date.fromisoformat(scheduled).isoformat()
            except(ValueError,OverflowError):continue
            event_id=hashlib.sha256((sym+source+published).encode()).hexdigest()[:28]
            conn.execute("INSERT OR REPLACE INTO events VALUES(?,?,?,?,?,?,?,?)",
                       (event_id,sym,public_ms,scheduled,typ,title,source,now))
            count+=1
    if count:audit(conn,"EVENT","","Disclosed event import",{"count":count,"file":Path(path).name})
    conn.commit();return count
def import_factors(conn,path):
    """Long-format rows: symbol,factor,value,observed_at_utc,source.
    Only valid point-in-time inputs with explicit provenance are accepted."""
    count=0; now=ms()
    with open(path,newline='',encoding='utf-8-sig') as f:
        for raw in csv.DictReader(f):
            r=_upper(raw);sym=_get(r,"SYMBOL").upper()
            val=_numeric(_get(r,"VALUE"));src=_get(r,"SOURCE");iso=_get(r,"OBSERVED_AT_UTC")
            try:
                factor=int(_get(r,"FACTOR"))
                ts=datetime.fromisoformat(iso.replace('Z','+00:00'))
                if ts.tzinfo is None:continue
                when=int(ts.timestamp()*1000)
            except(ValueError,TypeError,OverflowError):continue
            if not sym or factor<1 or factor>30 or val is None or val>1 or not src or when>now+3000:continue
            conn.execute("INSERT INTO factors(symbol,factor,value,observed_ms,source) VALUES(?,?,?,?,?) ON CONFLICT(symbol,factor) DO UPDATE SET value=excluded.value,observed_ms=excluded.observed_ms,source=excluded.source",(sym,factor,val,when,src))
            count+=1
    audit(conn,"FACTORS","","Point-in-time factor import",{"count":count,"file":Path(path).name})
    conn.commit();return count

def import_execution_evidence(conn,path):
    """Operator-controlled verified gate feed. Never infer candles from a single LTP.
    Format: symbol,observed_at_utc,source,breakout15m,confirmation5m,
    vwap5m,vwap15m,rvol,sector_positive,liquidity_verified,spread_bps,
    net_rr,entry,stop_loss,target,trading_allowed,material_risk_clear.
    Values must be based on completed candles and a sourced net-RR computation.
    """
    count=0; now=ms()
    keys=("breakout15m","confirmation5m","vwap5m","vwap15m","sector_positive",
          "liquidity_verified","trading_allowed","material_risk_clear")
    with open(path,newline='',encoding='utf-8-sig') as f:
        for raw in csv.DictReader(f):
            row=_upper(raw)
            symbol=_get(row,"SYMBOL").upper()
            source=_get(row,"SOURCE")
            observed=_get(row,"OBSERVED_AT_UTC")
            try:
                timestamp=datetime.fromisoformat(observed.replace("Z","+00:00"))
                if timestamp.tzinfo is None: continue
                t=int(timestamp.timestamp()*1000)
                if t>now+3000 or t<now-300000: continue
                gates=[]
                for name in keys:
                    value=_get(row,name)
                    if value not in ("0","1"):raise ValueError("Boolean gate not 0 or 1")
                    gates.append(int(value))
                numbers=[float(_get(row,name)) for name in
                    ("RVOL","SPREAD_BPS","NET_RR","ENTRY","STOP_LOSS","TARGET")]
                if not all(__import__("math").isfinite(n) for n in numbers):continue
                rv,spread,rr,entry,stop,target=numbers
                if not (rv>=0 and spread>=0 and rr>=0 and 0<stop<entry<target):
                    continue
                if rr>(target-entry)/(entry-stop)+0.001:continue
                if not source.startswith("https://") or not symbol or not symbol.replace("-","").replace("&","").isalnum():continue
            except (ValueError,TypeError,OverflowError,ZeroDivisionError):continue
            data=(symbol,t,source,*gates[:4],rv,*gates[4:6],spread,rr,
                entry,stop,target,*gates[6:])
            conn.execute("""INSERT INTO execution_evidence
              (symbol,observed_ms,source,breakout15m,confirmation5m,
               vwap5m,vwap15m,rvol,sector_positive,liquidity_verified,
               spread_bps,net_rr,entry,stop_loss,target,trading_allowed,material_risk_clear)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(symbol) DO UPDATE SET
                 observed_ms=excluded.observed_ms,source=excluded.source,
                 breakout15m=excluded.breakout15m,confirmation5m=excluded.confirmation5m,
                 vwap5m=excluded.vwap5m,vwap15m=excluded.vwap15m,rvol=excluded.rvol,
                 sector_positive=excluded.sector_positive,liquidity_verified=excluded.liquidity_verified,
                 spread_bps=excluded.spread_bps,net_rr=excluded.net_rr,entry=excluded.entry,
                 stop_loss=excluded.stop_loss,target=excluded.target,
                 trading_allowed=excluded.trading_allowed,
                 material_risk_clear=excluded.material_risk_clear""",data)
            count+=1
    audit(conn,"EXECUTION_FEED","","Verified-gate import",{"count":count,"sourceFile":Path(path).name})
    conn.commit()
    return count

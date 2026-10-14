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
CREATE TABLE IF NOT EXISTS outcomes(day TEXT NOT NULL,window TEXT NOT NULL,symbol TEXT NOT NULL,
horizon INTEGER NOT NULL,peak_return_pct REAL NOT NULL,close_return_pct REAL NOT NULL,
captured INTEGER NOT NULL,computed_ms INTEGER NOT NULL,PRIMARY KEY(day,window,symbol,horizon));
"""
def ms(): return int(time.time()*1000)
def db(path):
    Path(path).parent.mkdir(parents=True,exist_ok=True)
    conn=sqlite3.connect(path,timeout=30)
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

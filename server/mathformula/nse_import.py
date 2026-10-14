"""Official NSE EQ universe and legacy full-bhavdata CSV adapter.
Source availability and permitted re-use are deployment responsibilities. Fail closed on unknown schemas.
"""
import csv,io,os,ssl,time,urllib.request,logging
from pathlib import Path
from datetime import date,datetime,timedelta
from zoneinfo import ZoneInfo

LOG=logging.getLogger("mathformula")
ROOT="https://nsearchives.nseindia.com"
EQUITY_URL=ROOT+"/content/equities/EQUITY_L.csv"
DAILY_URL=ROOT+"/products/content/sec_bhavdata_full_{stamp}.csv"
REQUIRED={"SYMBOL","SERIES","DATE1","OPEN_PRICE","HIGH_PRICE","LOW_PRICE",
          "CLOSE_PRICE","TTL_TRD_QNTY","TURNOVER_LACS"}
def fetch_csv(url):
    if not url.startswith(ROOT+"/"):raise ValueError("NSE allowlisted domain only")
    request=urllib.request.Request(url,headers={"User-Agent":"MathFormula-Research/1.0 (read-only)",
        "Accept":"text/csv,*/*"})
    with urllib.request.urlopen(request,timeout=20) as response:
        data=response.read(3_000_000)
    text=data.decode("utf-8-sig")
    if not text or ("<html" in text[:200].lower()):raise ValueError("Exchange file unavailable or blocked")
    return text
def convert_bhavdata(text,day):
    reader=csv.DictReader(io.StringIO(text))
    if not reader.fieldnames:raise ValueError("No CSV header")
    fields={x.strip().upper() for x in reader.fieldnames}
    if not REQUIRED.issubset(fields):raise ValueError("NSE bhavdata schema mismatch; nothing imported")
    rows=[]
    for raw in reader:
        r={str(k).strip().upper():str(v or "").strip() for k,v in raw.items() if k is not None}
        if r.get("SERIES")!="EQ":continue
        symbol=r.get("SYMBOL","")
        if not symbol or not all(c.isalnum() or c in '-&' for c in symbol):continue
        try:
            stamp=r["DATE1"]
            parsed=None
            for fmt in ("%d-%b-%Y","%d-%b-%y","%d/%m/%Y","%Y-%m-%d"):
                try:parsed=datetime.strptime(stamp,fmt).date();break
                except ValueError:pass
            if parsed!=day:continue
            op,high,low,close=[float(r[k].replace(",","")) for k in
              ("OPEN_PRICE","HIGH_PRICE","LOW_PRICE","CLOSE_PRICE")]
            vol=float(r["TTL_TRD_QNTY"].replace(",",""))
            turnover_lacs=float(r["TURNOVER_LACS"].replace(",",""))
            if close<=0 or high<low or vol<0 or turnover_lacs<0:continue
            # Official full bhavdata reports TURNOVER_LACS, not rupees.
            turnover_inr=round(turnover_lacs*100000,2)
            rows.append({"symbol":symbol,"date":day.isoformat(),"open":op,
                "high":high,"low":low,"close":close,"volume":vol,
                "turnover_inr":turnover_inr})
        except(KeyError,ValueError):continue
    if not rows:raise ValueError("NSE file has no matching EQ rows for "+day.isoformat())
    return rows
def safe_write_csv(path,columns,rows):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_suffix(".tmp")
    with temp.open("w",newline="",encoding="utf-8") as f:
        w=csv.DictWriter(f,fieldnames=columns);w.writeheader();w.writerows(rows)
    temp.replace(path)
def fetch_universe(out_path):
    text=fetch_csv(EQUITY_URL)
    r=csv.DictReader(io.StringIO(text))
    headers={str(k or "").strip().upper() for k in (r.fieldnames or [])}
    if not {"SYMBOL","SERIES"}.issubset(headers):raise ValueError("NSE equity file schema mismatch")
    rows=list(csv.DictReader(io.StringIO(text)))
    if len(rows)<1000:raise ValueError("NSE equity file unexpectedly short")
    safe_write_csv(out_path,rows[0].keys(),rows)
    return len(rows)
def fetch_daily(day,out_dir,overwrite=False):
    output=Path(out_dir)/(day.isoformat()+".csv")
    if output.exists() and not overwrite:return 0
    text=fetch_csv(DAILY_URL.format(stamp=day.strftime("%d%m%Y")))
    rows=convert_bhavdata(text,day)
    safe_write_csv(output,["symbol","date","open","high","low","close","volume","turnover_inr"],rows)
    return len(rows)
def bootstrap(out_dir,lookback_days=130):
    if not 70<=lookback_days<=240:raise ValueError("lookback must be 70..240 calendar days")
    today=datetime.now(ZoneInfo("Asia/Kolkata")).date()
    success=0;failed=0;count=0
    for ago in range(lookback_days,0,-1):
        d=today-timedelta(days=ago)
        if d.weekday()>=5:continue
        try:
            added=fetch_daily(d,out_dir)
            count+=added;success+=bool(added)
        except Exception as exc:
            failed+=1
            LOG.info("No verifiable NSE file for %s (%s)",d,type(exc).__name__)
        time.sleep(.6)
    return {"newFiles":success,"unavailableTradingDaysOrFailures":failed,"rowsImported":count}

"""Authoritative, conservative Alpha-30 recommendation records.

The stock-discovery heuristic is never a BUY. A live BUY is possible only
when all 30 normalized inputs and independent execution evidence are fresh,
sourced and consistent. This module never places orders.
"""
import json
from datetime import datetime
from math import isfinite
from .alpha30 import score, WEIGHTS, VERSION
from .storage import ms, audit

FACTOR_NAMES=(
 "Revenue growth YoY","Revenue acceleration","Normalized EPS/PAT growth",
 "Operating-margin change","Operating-profit acceleration","ROCE / ROA / ROE",
 "Cash-flow conversion","Leverage / credit quality","Valuation vs peers and growth",
 "Earnings quality / dilution","News freshness and materiality","Earnings surprise",
 "Management guidance revision","Orders / backlog materiality","Policy / regulatory exposure",
 "Analyst revisions","Ownership / governance","Nifty trend and volatility",
 "Sector relative strength","Sector breadth","FII/DII flows","Overnight global indices",
 "USD/INR exposure","Crude / commodity exposure","Rates and geopolitical stress",
 "Time-adjusted relative volume","20-day breakout strength","Trend / extension",
 "Closing strength","VWAP, liquidity and net reward/risk")
assert len(FACTOR_NAMES)==30
# Maximum source age in milliseconds. Time-sensitive factors intentionally expire rapidly.
FACTOR_MAX_AGE_MS=(
 *((180*86400000,) * 10),
 3*86400000,45*86400000,45*86400000,45*86400000,
 45*86400000,60*86400000,90*86400000,
 2*86400000,2*86400000,2*86400000,2*86400000,
 2*86400000,2*86400000,2*86400000,2*86400000,
 5*60000,5*60000,5*60000,5*60000,5*60000)
assert len(FACTOR_MAX_AGE_MS)==30

def _positive(x):
    return isinstance(x,(int,float)) and not isinstance(x,bool) and isfinite(x) and x>0

def _numeric(x):
    return isinstance(x,(int,float)) and not isinstance(x,bool) and isfinite(x)

def read_factors(conn,symbol,now):
    """No future records, stale points, unknown sources, or invalid normalized values."""
    values=[None]*30
    proof=[]
    rows={r["factor"]:r for r in conn.execute(
        "SELECT factor,value,observed_ms,source FROM factors WHERE symbol=?",(symbol,))}
    for i in range(30):
        r=rows.get(i+1)
        fresh=bool(r and r["source"] and r["observed_ms"]<=now
                   and now-r["observed_ms"]<=FACTOR_MAX_AGE_MS[i]
                   and _numeric(r["value"]) and 0<=r["value"]<=1)
        if fresh: values[i]=r["value"]
        proof.append({"number":i+1,"name":FACTOR_NAMES[i],"weight":WEIGHTS[i],
                      "value":values[i],"fresh":fresh,
                      "source":r["source"] if fresh else None,
                      "observedMs":r["observed_ms"] if fresh else None})
    return values,proof

def evaluate(values,quote,evidence,reference,now,market_open):
    """Pure gate evaluator. Mirrors Java's immutable Alpha30Engine thresholds."""
    scored=score(values)
    reasons=[]
    trade_ms=quote["trade_ms"] if quote else None
    last=quote["price"] if quote else None
    if not market_open: reasons.append("NSE market closed")
    if not isinstance(trade_ms,int) or trade_ms>now+3000 or now-trade_ms>10000:
        reasons.append("Missing or stale exchange quote (>10s)")
    if scored["coveragePct"]<90:
        reasons.append("30-factor source coverage below 90%")
    if values[29] is None: reasons.append("Mandatory T30 live execution factor missing")
    if not _positive(last) or not _positive(reference):
        reasons.append("Verified quote/breakout reference unavailable")
    if not evidence or not evidence.get("source") or not isinstance(evidence.get("observed_ms"),int) or (
        evidence["observed_ms"]>now+3000 or now-evidence["observed_ms"]>10000):
        reasons.append("Independent execution evidence missing or stale (>10s)")
    # A lack of verified evidence is not a failed setup; remain NO_SIGNAL.
    if reasons:
        return {"decision":"NO_SIGNAL","score":scored["score"],
                "coveragePct":scored["coveragePct"],"reasons":reasons}
    if scored["score"]<78: reasons.append("Alpha-30 score below 78")
    if not evidence["trading_allowed"] or not evidence["material_risk_clear"]:
        reasons.append("Tradability/material risk clearance failed")
    if not (evidence["breakout15m"] and evidence["confirmation5m"] and last>=reference):
        reasons.append("15m breakout and two completed 5m closes unverified")
    if not (evidence["vwap5m"] and evidence["vwap15m"]):
        reasons.append("5m/15m VWAP confirmation failed")
    if not _numeric(evidence["rvol"]) or evidence["rvol"]<1.5:
        reasons.append("Time-adjusted relative volume below 1.5")
    if not evidence["sector_positive"]: reasons.append("Sector strength not verified")
    spread=evidence["spread_bps"]
    if not evidence["liquidity_verified"] or not _numeric(spread) or spread>30:
        reasons.append("Verified liquidity/spread <=30bps not satisfied")
    rr=evidence["net_rr"]
    if not _numeric(rr) or rr<1.25:
        reasons.append("Net reward/risk below 1.25 or missing")
    entry,stop,target=(evidence.get(k) for k in ("entry","stop_loss","target"))
    if not all(_positive(v) for v in (entry,stop,target)) or not stop<entry<target:
        reasons.append("Entry/stop/target not independently established")
    elif _numeric(rr) and rr> ((target-entry)/(entry-stop))+0.001:
        reasons.append("Net reward/risk exceeds gross reward/risk; input inconsistent")
    # Some risk/reward controls are source-provided and cannot be inferred from a quote.
    return {"decision":"BUY_ELIGIBLE" if not reasons else "WAIT",
            "score":scored["score"],"coveragePct":scored["coveragePct"],"reasons":reasons}

def _append_event(conn,rec_id,symbol,previous,next_state,detail,now):
    conn.execute("""INSERT INTO recommendation_events
        (recommendation_id,symbol,at_ms,previous_status,next_status,details)
        VALUES(?,?,?,?,?,?)""",(rec_id,symbol,now,previous,next_state,
          json.dumps(detail,separators=(',',':'))))
    audit(conn,"RECOMMENDATION",symbol,previous+" -> "+next_state,detail)

def record_discovery(conn,items,now):
    """A pre-existing watch never closes just because its top-30 rank changed."""
    for heuristic,symbol,price,reference,stage,ascore,coverage,details in items:
        active=conn.execute("SELECT * FROM recommendations WHERE symbol=? AND closed_ms IS NULL ORDER BY opened_ms DESC LIMIT 1",(symbol,)).fetchone()
        if active:
            conn.execute("""UPDATE recommendations
                SET last_seen_ms=?,last_scan_ms=?,research_rank=?,reference=? WHERE id=?""",
                (now,now,details["rank"],reference,active["id"]))
        else:
            day=datetime.fromtimestamp(now/1000).strftime("%Y-%m-%d")
            if conn.execute("SELECT 1 FROM recommendations WHERE symbol=? AND opened_day=?",(symbol,day)).fetchone():
                continue
            rec_id=symbol+"-"+str(now)
            status="BREAKOUT_OBSERVED" if price>=reference else "WATCHING"
            conn.execute("""INSERT INTO recommendations
                (id,symbol,opened_day,opened_ms,last_seen_ms,last_scan_ms,reference,research_rank,
                 status,decision,price,quote_ms,score,coverage_pct,reasons,factor_evidence,execution_evidence)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (rec_id,symbol,day,now,now,now,reference,details["rank"],status,
                 "NO_SIGNAL",price,None,ascore,coverage,
                 json.dumps(["Full Alpha-30 execution not yet verified"]),
                 "[]","{}"))
            _append_event(conn,rec_id,symbol,"NEW",status,{"source":"verified discovery scan"},now)

def reevaluate(conn,now,market_open):
    """Persist sourced score, missing-factor explanations and state transitions."""
    rows=conn.execute("SELECT * FROM recommendations WHERE closed_ms IS NULL ORDER BY opened_ms").fetchall()
    messages=[]
    for rec in rows:
        symbol=rec["symbol"]
        values,proof=read_factors(conn,symbol,now)
        q=conn.execute("SELECT * FROM quotes WHERE symbol=?",(symbol,)).fetchone()
        e=conn.execute("SELECT * FROM execution_evidence WHERE symbol=?",(symbol,)).fetchone()
        result=evaluate(values,dict(q) if q else None,dict(e) if e else None,
                        rec["reference"],now,market_open)
        d=result["decision"]
        price=q["price"] if q else rec["price"]
        quote_ms=q["trade_ms"] if q else None
        status="BUY_ELIGIBLE" if d=="BUY_ELIGIBLE" else (
            "BREAKOUT_OBSERVED" if _positive(price) and price>=rec["reference"] else "WATCHING")
        # Missing/stale evidence never produces BUY, even when the price has crossed.
        reasons=result["reasons"] or ["All frozen Alpha-30 mandatory gates verified"]
        evidence=dict(e) if e else {}
        before=rec["status"]
        conn.execute("""UPDATE recommendations SET
            status=?,decision=?,price=?,quote_ms=?,score=?,coverage_pct=?,
            reasons=?,factor_evidence=?,execution_evidence=?,evaluated_ms=?
            WHERE id=?""",(status,d,price,quote_ms,result["score"],result["coveragePct"],
             json.dumps(reasons),json.dumps(proof,separators=(',',':')),
             json.dumps(evidence,separators=(',',':')),now,rec["id"]))
        if before!=status:
            _append_event(conn,rec["id"],symbol,before,status,{"decision":d,"reasons":reasons},now)
            if d=="BUY_ELIGIBLE":messages.append({"symbol":symbol,"kind":"BUY_ELIGIBLE","atMs":now,
                  "id":rec["id"],"message":"All Alpha-30 gates verified; check immediate quote and risk plan"})
        # Close ONLY against actual stored exchange-day EOD history, never using weekday approximations.
        sessions=conn.execute("""SELECT COUNT(DISTINCT day) AS n FROM eod
            WHERE symbol=? AND day>? AND day<=date(?)""",
            (symbol,rec["opened_day"],datetime.fromtimestamp(now/1000).strftime("%Y-%m-%d"))).fetchone()["n"]
        if sessions>=3 and d!="BUY_ELIGIBLE":
            conn.execute("UPDATE recommendations SET status='CLOSED',closed_ms=?,close_code='EXPIRED',close_reason=? WHERE id=?",
                (now,"Research review expired after three recorded subsequent EOD sessions",rec["id"]))
            _append_event(conn,rec["id"],symbol,status,"CLOSED",{"reason":"EXPIRED"},now)
    conn.commit()
    return messages

def response_rows(conn,closed=False,limit=100):
    rows=conn.execute("""SELECT * FROM recommendations WHERE closed_ms IS
       """+("NOT NULL" if closed else "NULL")+
       " ORDER BY "+("closed_ms" if closed else "last_seen_ms")+" DESC LIMIT ?",(limit,)).fetchall()
    items=[]
    for r in rows:
        item=dict(r)
        for name in ("reasons","factor_evidence","execution_evidence"):
            try:item[name]=json.loads(item[name] or ("[]" if name!="execution_evidence" else "{}"))
            except (ValueError,TypeError):item[name]=[] if name!="execution_evidence" else {}
        item["breakoutLevel"]=item.pop("reference")
        item["alpha30Score"]=item.pop("score")
        item["coveragePct"]=item.pop("coverage_pct")
        item["tradeMs"]=item.pop("quote_ms")
        item["closedAtMs"]=item.pop("closed_ms")
        item["closeCode"]=item.pop("close_code")
        item["closeReason"]=item.pop("close_reason")
        item["openedAtMs"]=item.pop("opened_ms")
        item["lastSeenAtMs"]=item.pop("last_seen_ms")
        item["evaluationTimeMs"]=item.pop("evaluated_ms")
        item["signal"]=item.pop("decision")
        items.append(item)
    return items

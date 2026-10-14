"""Discovery rank uses only verified price/EOD data. Not Alpha-30 or a BUY signal."""
from math import isfinite
from .alpha30 import score
from .storage import ms
def bounded(x,lo,hi):return max(lo,min(x,hi))
def shortlist(conn,window,day,asof_ms,top_n=30,min_traded_value=10_000_000):
    universe=[x["symbol"] for x in conn.execute("SELECT symbol FROM universe ORDER BY symbol")]
    found=[]
    quotes={row["symbol"]:row for row in conn.execute("SELECT symbol,price,observed_ms FROM ltp")}
    eod_mode=window.startswith("AFTER_CLOSE")
    for symbol in universe:
        latest=quotes.get(symbol)
        if eod_mode:
            closing=conn.execute("SELECT close,imported_ms FROM eod WHERE symbol=? AND day=?",(symbol,day)).fetchone()
            if closing is None or closing["imported_ms"]>asof_ms:continue
            price=closing["close"]
        else:
            if latest is None or asof_ms-latest["observed_ms"]>20*60*1000:continue
            price=latest["price"]
        history=list(conn.execute("SELECT day,open,high,low,close,volume,turnover_inr FROM eod WHERE symbol=? AND day<? ORDER BY day DESC LIMIT 60",(symbol,day)))
        if len(history)<60:continue
        if any(row["turnover_inr"] is None for row in history[:20]):continue
        avg_turnover=sum(row["turnover_inr"] for row in history[:20])/20
        if avg_turnover<min_traded_value:continue
        prev_close=history[0]["close"]
        high20=max(row["high"] for row in history[:20])
        if not isfinite(price) or price<=0 or high20<=0 or prev_close<=0:continue
        momentum=100*(price/prev_close-1)
        distance=100*(price/high20-1)
        # Defined non-model discovery heuristic only. No future data and no probability semantics.
        prior_volume=history[0]["volume"]
        avg_vol=sum(row["volume"] for row in history[:20])/20
        prior_rvol=prior_volume/avg_vol if avg_vol else 0
        daily_close_location=(history[0]["close"]-history[0]["low"])/max(1e-9,history[0]["high"]-history[0]["low"])
        heuristic=(45*bounded(1+distance/6,0,1)+
            20*bounded((momentum+2)/9,0,1)+
            20*bounded(prior_rvol/3,0,1)+
            15*bounded(daily_close_location,0,1))
        # Include early-stage candidates approaching breakout; avoid extremely extended gaps.
        if distance< -5 or distance>20:continue
        phase="ABOVE BREAKOUT" if distance>=0 else "NEAR BREAKOUT" if distance>=-1.5 else "WATCHING"
        values=[None]*30
        for f in conn.execute("SELECT factor,value,observed_ms FROM factors WHERE symbol=?",(symbol,)):
            if f["observed_ms"]<=asof_ms:values[f["factor"]-1]=f["value"]
        alpha=score(values,overnight=True)
        score_value=alpha["score"] if alpha["qualifiedForScoring"] else None
        details={"priorClose":prev_close,"priorDay":history[0]["day"],"avg20TurnoverInr":round(avg_turnover),
          "lastDayVolume":prior_volume,"lastDayRvol":round(prior_rvol,2),
          "distancePct":round(distance,2),"momentumPct":round(momentum,2),
          "preliminaryRankIsNOTAlpha30":True,"scoreCoveragePct":alpha["coveragePct"],
          "eligibleAsAlpha30Overnight":bool(score_value is not None and score_value>=68),
          "dataAsOfMs":asof_ms,"priceSource":"Imported completed NSE EOD" if eod_mode else "Groww batch LTP (no exchange last-trade timestamp)"}
        found.append((heuristic,symbol,price,high20,phase,score_value,alpha["coveragePct"],details))
    found.sort(key=lambda x:(-x[0],x[1]))
    return {"universe":len(universe),"eligible":len(found),
            "items":found[:top_n],"excluded":len(universe)-len(found)}

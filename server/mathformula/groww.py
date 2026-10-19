"""Read-only Groww REST adaptor, TOTP credentials from environment; no order routes."""
import os,time,hmac,hashlib,base64,struct,urllib.request,urllib.parse,urllib.error,json,threading
from collections import deque
from datetime import datetime
from zoneinfo import ZoneInfo

IST=ZoneInfo("Asia/Kolkata")
class GrowwError(Exception):pass
def totp(secret, now=None):
    secret=''.join(c for c in secret.upper() if c not in ' -=')
    try: key=base64.b32decode(secret+'='*((-len(secret))%8),casefold=True)
    except Exception as e:raise GrowwError("Invalid Groww TOTP secret") from e
    counter=int(now if now is not None else time.time())//30
    digest=hmac.new(key,struct.pack('>Q',counter),hashlib.sha1).digest()
    i=digest[-1]&15
    return str((struct.unpack('>I',digest[i:i+4])[0]&0x7fffffff)%1000000).zfill(6)
class Groww:
    def __init__(self):
        self.api_key=os.getenv("GROWW_TOTP_TOKEN","")
        self.secret=os.getenv("GROWW_TOTP_SECRET","")
        self.access=os.getenv("GROWW_ACCESS_TOKEN","")
        self.access_day="" # explicitly supplied access token may be stale
        self.lock=threading.Lock()
        self.last_call=0.0
        # Deliberately stay below the app's conservative 35/min internal
        # live-data policy. These are SERVER-only counts, not account-wide.
        self.min_interval=1.05
        self.live_calls=deque()
        self.cooldown_until=0.0
    def _request(self,path,access,body=None):
        if not (path.startswith("/v1/live-data/ltp?") or path.startswith("/v1/live-data/quote?")
                or path=="/v1/token/api/access"):
            raise GrowwError("Read-only endpoint allowlist rejected request")
        with self.lock:
            while True:
                now=time.monotonic()
                if now<self.cooldown_until:
                    raise GrowwError("Groww 429 cooldown active; no requests permitted")
                is_live=path.startswith("/v1/live-data/")
                while self.live_calls and now-self.live_calls[0]>=60:
                    self.live_calls.popleft()
                delay=max(0,self.min_interval-(now-self.last_call))
                if is_live and len(self.live_calls)>=35:
                    delay=max(delay,60-(now-self.live_calls[0])+.05)
                if delay<=0:
                    self.last_call=now
                    if is_live:self.live_calls.append(now)
                    break
                time.sleep(min(delay,2.0))
        url="https://api.groww.in"+path
        headers={"Authorization":"Bearer "+access,"X-API-VERSION":"1.0",
                 "Accept":"application/json"}
        if body is not None:headers["Content-Type"]="application/json"
        data=json.dumps(body).encode() if body is not None else None
        req=urllib.request.Request(url,data=data,headers=headers,method="POST" if body else "GET")
        try:
            with urllib.request.urlopen(req,timeout=12) as res:
                result=json.loads(res.read(1_000_000))
        except urllib.error.HTTPError as e:
            if e.code==429:
                with self.lock:
                    self.cooldown_until=max(self.cooldown_until,time.monotonic()+30*60)
                raise GrowwError("Groww HTTP 429; backend paused for 30 minutes") from e
            raise GrowwError("Groww HTTP "+str(e.code)) from e
        except Exception as e:
            raise GrowwError("Groww request failed: "+type(e).__name__) from e
        if not isinstance(result,dict) or result.get("status")=="FAILURE":
            raise GrowwError("Groww response indicates failure")
        return result
    def token(self):
        day=datetime.now(IST).strftime("%Y-%m-%d")
        if self.access and (not self.api_key or self.access_day==day):return self.access
        if not self.api_key or not self.secret:raise GrowwError("Groww TOTP environment credentials not configured")
        result=self._request("/v1/token/api/access",self.api_key,{"key_type":"totp","totp":totp(self.secret)})
        access=result.get("token") or (result.get("payload") or {}).get("token")
        if not access:raise GrowwError("Groww token exchange returned no token")
        self.access=access;self.access_day=day
        return access
    def batch_ltp(self,symbols):
        if not symbols:return {}
        if len(symbols)>50:raise ValueError("Groww batch LTP supports max 50 instruments")
        qs=urllib.parse.urlencode({"segment":"CASH","exchange_symbols":",".join("NSE_"+s for s in symbols)})
        data=self._request("/v1/live-data/ltp?"+qs,self.token()).get("payload") or {}
        out={}
        for s in symbols:
            try:
                p=float(data.get("NSE_"+s))
                if p>0:out[s]=p
            except(TypeError,ValueError):pass
        return out
    def quote(self,symbol):
        qs=urllib.parse.urlencode({"exchange":"NSE","segment":"CASH","trading_symbol":symbol})
        p=self._request("/v1/live-data/quote?"+qs,self.token()).get("payload") or {}
        def number(name):
            try:return float(p[name])
            except(KeyError,TypeError,ValueError):return None
        trade=p.get("last_trade_time")
        try:
            trade=int(trade)
            if trade<10**11:trade*=1000
        except(TypeError,ValueError):trade=None
        return {"symbol":symbol,"price":number("last_price"),"tradeMs":trade,
            "observedMs":int(time.time()*1000),"vwap":number("average_price"),
            "volume":number("volume"),"bid":number("bid_price"),"ask":number("offer_price"),
            "changePct":number("day_change_perc"),"source":"Groww quote"}

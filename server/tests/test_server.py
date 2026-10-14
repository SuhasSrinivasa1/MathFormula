import csv,os,tempfile,time,unittest
from datetime import date,timedelta
from pathlib import Path
from mathformula.alpha30 import WEIGHTS,score,VERSION
from mathformula.storage import db,import_universe,import_eod,import_events,import_factors
from mathformula.groww import totp
from mathformula.discovery import shortlist

class EngineTests(unittest.TestCase):
    def test_immutable_formula_and_missing_factor(self):
        self.assertEqual(sum(WEIGHTS),100)
        self.assertEqual(len(WEIGHTS),30)
        self.assertEqual(VERSION,"Alpha-30-v1.0")
        self.assertEqual(score([1.0]*30)["score"],100.0)
        overnight=score([1.0]*29+[None],overnight=True)
        self.assertEqual(overnight["score"],100.0)
        self.assertEqual(overnight["coveragePct"],100.0)
        self.assertLess(score([None]*30)["coveragePct"],90)
    def test_known_rfc_totp(self):
        # RFC 6238 HMAC-SHA1 result at time 59 is 94287082 (8 digits); last six 287082.
        self.assertEqual(totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",59),"287082")

class DataTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path=str(Path(self.temp.name)/"data.db")
    def write(self,name,fields,rows):
        p=Path(self.temp.name)/name
        with p.open("w",newline="") as f:
            w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
        return str(p)
    def seed(self):
        u=self.write("universe.csv",["SYMBOL","NAME OF COMPANY","SERIES"],
            [{"SYMBOL":"TARIL","NAME OF COMPANY":"Research sample","SERIES":"EQ"},
            {"SYMBOL":"FOO","NAME OF COMPANY":"Another","SERIES":"EQ"},
            {"SYMBOL":"INDEX","NAME OF COMPANY":"Not equity","SERIES":"ETF"}])
        days=[];day=date(2026,7,1)
        while len(days)<62:
            if day.weekday()<5:days.append(day)
            day+=timedelta(days=1)
        rows=[]
        for d in days:
            rows.append({"symbol":"TARIL","date":d.isoformat(),"open":100,"high":106,
              "low":95,"close":102,"volume":200000,"turnover_inr":20200000})
        e=self.write("eod.csv",["symbol","date","open","high","low","close","volume","turnover_inr"],rows)
        with db(self.path) as conn:
            self.assertEqual(import_universe(conn,u),2)
            self.assertEqual(import_eod(conn,e),62)
        return days
    def test_universe_requires_60_sessions_and_real_history(self):
        days=self.seed()
        day=(days[-1]+timedelta(days=1)).isoformat()
        now=int(time.time()*1000)
        with db(self.path) as conn:
            conn.execute("INSERT INTO ltp(symbol,price,observed_ms,source) VALUES(?,?,?,?)",
                ("TARIL",108.0,now,"fake for unit test ONLY"))
            result=shortlist(conn,"INTRADAY",day,now)
            self.assertEqual(result["universe"],2)
            self.assertEqual(result["eligible"],1)
            self.assertEqual(result["items"][0][1],"TARIL")
            self.assertEqual(result["items"][0][3],106)
            self.assertIsNone(result["items"][0][5]) # no fake full Alpha score
    def test_after_close_only_uses_genuine_current_eod(self):
        days=self.seed()
        final=days[-1].isoformat()
        with db(self.path) as conn:
            # No current-date LTP needed: afterclose uses only genuinely imported current EOD.
            result=shortlist(conn,"AFTER_CLOSE_1930",final,int(time.time()*1000))
            self.assertEqual(result["eligible"],1)
            self.assertEqual(result["items"][0][7]["priceSource"],"Imported completed NSE EOD")
    def test_future_events_rejected(self):
        tomorrow=date.today()+timedelta(days=1)
        ev=self.write("events.csv",["symbol","event_type","title","published_at_utc","scheduled_date","source_url"],[
            {"symbol":"TARIL","event_type":"EARNINGS","title":"Future disclosure","published_at_utc":(tomorrow.isoformat()+"T20:00:00Z"),"scheduled_date":"","source_url":"https://example.com/future"},
            {"symbol":"TARIL","event_type":"EARNINGS","title":"Historical filing","published_at_utc":"2026-01-01T11:00:00Z","scheduled_date":"","source_url":"https://example.com/real"}
        ])
        with db(self.path) as conn:
            self.assertEqual(import_events(conn,ev),1)
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM events").fetchone()[0],1)
    def test_factor_inputs_require_timestamp_and_provenance(self):
        path=self.write("factors.csv",["symbol","factor","value","observed_at_utc","source"],[
          {"symbol":"TARIL","factor":1,"value":".9","observed_at_utc":"2026-01-01T11:00:00Z","source":"actual"},
          {"symbol":"TARIL","factor":2,"value":"1.5","observed_at_utc":"2026-01-01T11:00:00Z","source":"actual"},
          {"symbol":"TARIL","factor":3,"value":".8","observed_at_utc":"2026-01-01T11:00:00Z","source":""}
        ])
        with db(self.path) as conn:
            self.assertEqual(import_factors(conn,path),1)
if __name__=="__main__":unittest.main()

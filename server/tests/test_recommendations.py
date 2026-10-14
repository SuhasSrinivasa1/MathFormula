"""v2.1 conservative Alpha-30 tests; fully synthetic data, never live recommendations."""
import os,tempfile,unittest
from datetime import datetime,timezone
from mathformula.recommendations import (
    evaluate,read_factors,record_discovery,reevaluate,response_rows)
from mathformula.storage import db,import_execution_evidence

NOW=1791978000000
def quote(trade=NOW,price=105):
    return {"trade_ms":trade,"price":price}
def gates(t=NOW):
    return {"source":"https://example.org/signed-observations",
      "observed_ms":t,"trading_allowed":1,"material_risk_clear":1,
      "breakout15m":1,"confirmation5m":1,"vwap5m":1,"vwap15m":1,
      "rvol":2.0,"sector_positive":1,"liquidity_verified":1,
      "spread_bps":8.0,"net_rr":1.8,
      "entry":105.0,"stop_loss":102.0,"target":111.0}
class GateEvaluation(unittest.TestCase):
    def test_all_verified_only(self):
        x=evaluate([1.0]*30,quote(),gates(),100,NOW,True)
        self.assertEqual(x["decision"],"BUY_ELIGIBLE")
        self.assertEqual(x["score"],100)
        self.assertEqual(x["coveragePct"],100)
    def test_stale_exchange_quote(self):
        x=evaluate([1.0]*30,quote(NOW-11000),gates(),100,NOW,True)
        self.assertEqual(x["decision"],"NO_SIGNAL")
        self.assertTrue(any("stale" in y for y in x["reasons"]))
    def test_missing_weighted_factors(self):
        x=evaluate([None]*30,quote(),gates(),100,NOW,True)
        self.assertEqual(x["decision"],"NO_SIGNAL")
        self.assertEqual(x["coveragePct"],0)
    def test_missing_execution_evidence(self):
        x=evaluate([1.0]*30,quote(),None,100,NOW,True)
        self.assertEqual(x["decision"],"NO_SIGNAL")
    def test_market_closed_never_buy(self):
        self.assertEqual(evaluate([1.0]*30,quote(),gates(),100,NOW,False)["decision"],"NO_SIGNAL")
    def test_gate_failure_wait(self):
        g=gates();g["sector_positive"]=0
        x=evaluate([1.0]*30,quote(),g,100,NOW,True)
        self.assertEqual(x["decision"],"WAIT")
        self.assertTrue(any("Sector" in y for y in x["reasons"]))
    def test_inconsistent_net_rr(self):
        g=gates();g["net_rr"]=8.
        x=evaluate([1.0]*30,quote(),g,100,NOW,True)
        self.assertEqual(x["decision"],"WAIT")
        self.assertTrue(any("gross" in y for y in x["reasons"]))
    def test_expired_evidence(self):
        self.assertEqual(evaluate([1.0]*30,quote(),gates(NOW-11000),100,NOW,True)["decision"],"NO_SIGNAL")

class Ledger(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=os.path.join(self.tmp.name,"records.db")
    def tearDown(self):self.tmp.cleanup()
    def test_missing_evidence_keeps_research_only(self):
        with db(self.path) as c:
            items=[(90.0,"TARIL",105.0,100.0,"ABOVE BREAKOUT",None,0.0,{"rank":1})]
            record_discovery(c,items,NOW)
            c.commit()
            x=response_rows(c)
            self.assertEqual(len(x),1)
            self.assertEqual(x[0]["signal"],"NO_SIGNAL")
            self.assertEqual(x[0]["status"],"BREAKOUT_OBSERVED")
            c.execute("""INSERT INTO quotes(symbol,price,trade_ms,observed_ms,source)
                VALUES('TARIL',105,?,?,?)""",(NOW,NOW,"test quote"))
            reevaluate(c,NOW,True)
            x=response_rows(c)[0]
            self.assertEqual(x["signal"],"NO_SIGNAL")
            self.assertGreater(len(x["reasons"]),0)
            self.assertEqual(len(x["factor_evidence"]),30)
            # A stock dropping out of the top-30 is NOT falsely closed.
            record_discovery(c,[],NOW+60000)
            c.commit()
            self.assertEqual(len(response_rows(c)),1)
            self.assertEqual(len(response_rows(c,closed=True)),0)
    def test_future_dated_factor_rejected(self):
        with db(self.path) as c:
            c.execute("INSERT INTO factors VALUES('GAEL',1,1,?,'test')",(NOW+60000,))
            values,proof=read_factors(c,"GAEL",NOW)
            self.assertIsNone(values[0])
            self.assertFalse(proof[0]["fresh"])
    def test_closed_after_real_eod_sessions(self):
        with db(self.path) as c:
            record_discovery(c,[(90,"GAEL",101,100,"NEAR BREAKOUT",None,0,{"rank":1})],NOW)
            day=datetime.fromtimestamp(NOW/1000,timezone.utc).date()
            for offset in (1,2,3):
                d=day.fromordinal(day.toordinal()+offset).isoformat()
                c.execute("INSERT INTO eod VALUES(?,?,?,?,?,?,?,?,?,?)",
                    ("GAEL",d,99,101,98,100,10000,1000000,"test",NOW))
            c.execute("INSERT INTO quotes(symbol,price,trade_ms,observed_ms,source) VALUES(?,?,?,?,?)",
                      ("GAEL",101,NOW+3*86400000,NOW+3*86400000,"test"))
            reevaluate(c,NOW+3*86400000,True)
            self.assertEqual(len(response_rows(c,closed=True)),1)
            self.assertEqual(response_rows(c,closed=True)[0]["closeCode"],"EXPIRED")
if __name__=="__main__":unittest.main()

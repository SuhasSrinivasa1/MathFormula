import json,threading,tempfile,urllib.request,urllib.error,unittest
from pathlib import Path
from mathformula.storage import db
from mathformula.api import Api
class FakeScanner: latest_error=""
class ExportTests(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup)
        path=str(Path(tmp.name)/"market.db")
        with db(path) as c:
            c.execute("INSERT INTO candidate_scans(day,window,at_ms,universe_total,prices_updated,eligible,reason,scanner_version) VALUES(?,?,?,?,?,?,?,?)",
                ("2026-10-13","PRE_CLOSE_1525",1,3800,3550,19,"Discovery only","server-v1"))
            c.execute("INSERT INTO candidates(day,window,symbol,created_ms,price,reference,rank_score,stage,alpha_score,coverage_pct,details) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                ("2026-10-13","PRE_CLOSE_1525","TARIL",1,304,308.55,77,
                 "NEAR BREAKOUT",None,35.0,'{"preliminaryRankIsNOTAlpha30":true}'))
        self.token="X"*48
        self.server=Api(("127.0.0.1",0),path,FakeScanner(),self.token)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start();self.addCleanup(self.close)
    def close(self):
        self.server.shutdown();self.server.server_close();self.thread.join(timeout=3)
    def req(self,method="GET",authorized=True):
        u=f"http://127.0.0.1:{self.server.server_port}/v1/export"
        headers={"Authorization":"Bearer "+self.token} if authorized else {}
        request=urllib.request.Request(u,headers=headers,method=method,data=b"{}" if method=="POST" else None)
        return urllib.request.urlopen(request,timeout=4)
    def test_bounded_export_and_no_secret(self):
        with self.req() as r:data=json.load(r)
        self.assertEqual(data["modelVersion"],"Alpha-30-v1.0")
        self.assertEqual(data["returnedCounts"]["candidates"],1)
        self.assertEqual(data["candidates"][0]["decision"],"NO SIGNAL")
        self.assertNotIn(self.token,json.dumps(data))
        self.assertEqual(data["countInDb"]["scans"],1)
    def test_auth_required_and_read_only(self):
        for method,auth,expected in [("GET",False,401),("POST",True,405)]:
            with self.assertRaises(urllib.error.HTTPError) as x:self.req(method,auth)
            self.assertEqual(x.exception.code,expected)
if __name__=="__main__":unittest.main()

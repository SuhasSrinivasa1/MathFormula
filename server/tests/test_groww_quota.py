import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from mathformula.groww import Groww, GrowwError

class QuotaGuardTest(unittest.TestCase):
    def test_provider_429_opens_circuit_breaker(self):
        groww=Groww()
        groww.min_interval=0
        response=HTTPError("https://api.groww.in/",429,"too many requests",{},None)
        with patch("urllib.request.urlopen",side_effect=response) as request:
            with self.assertRaisesRegex(GrowwError,"429"):
                groww._request("/v1/live-data/ltp?segment=CASH","test-token")
            with self.assertRaisesRegex(GrowwError,"cooldown"):
                groww._request("/v1/live-data/ltp?segment=CASH","test-token")
            self.assertEqual(request.call_count,1)

    def test_rejects_unapproved_endpoint_before_call(self):
        groww=Groww()
        with patch("urllib.request.urlopen") as request:
            with self.assertRaisesRegex(GrowwError,"allowlist"):
                groww._request("/v1/order/place","test-token")
            request.assert_not_called()

if __name__=="__main__":
    unittest.main()

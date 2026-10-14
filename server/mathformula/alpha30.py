"""Frozen Alpha-30 v1.0 formula. This code is NOT a calibrated probability."""
from math import isfinite
WEIGHTS=(3,2,4,3,3,2,3,3,2,2, 5,5,4,3,2,2,2, 3,4,2,2,2,2,2,2, 5,6,6,5,9)
VERSION="Alpha-30-v1.0"
assert len(WEIGHTS)==30 and sum(WEIGHTS)==100
def score(values,overnight=False):
    if len(values)!=30: raise ValueError("exactly 30 factors required")
    upto=29 if overnight else 30
    available=sum(WEIGHTS[i] for i in range(upto) if values[i] is not None and isinstance(values[i],(float,int))
                  and isfinite(values[i]) and 0<=values[i]<=1)
    total=sum(WEIGHTS[i]*values[i] for i in range(upto) if values[i] is not None
              and isinstance(values[i],(float,int)) and isfinite(values[i]) and 0<=values[i]<=1)
    denominator=91 if overnight else 100
    return {"version":VERSION,"score":round(total*100/denominator,3),
            "coveragePct":round(available*100/denominator,3),
            "qualifiedForScoring":available*100/denominator>=90,
            "mode":"overnight" if overnight else "live"}

import os,sys,argparse,logging
from .storage import db,import_universe,import_eod,import_events,import_factors
from .runner import Scanner
from .api import serve
def main():
    parser=argparse.ArgumentParser(description="MathFormula NSE discovery server — no orders")
    parser.add_argument("command",choices=("run","import-universe","import-eod","import-events","import-factors"))
    parser.add_argument("filename",nargs="?")
    args=parser.parse_args()
    logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"),format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    path=os.getenv("DATABASE_PATH","/data/mathformula.db")
    if args.command!="run":
        if not args.filename:parser.error("filename required for imports")
        method={"import-universe":import_universe,"import-eod":import_eod,
                "import-events":import_events,"import-factors":import_factors}[args.command]
        with db(path) as conn:print(f"Imported rows: {method(conn,args.filename)}")
        return
    token=os.getenv("APP_READ_TOKEN","")
    if len(token)<32:parser.error("APP_READ_TOKEN must be 32+ characters; server refused to start")
    scanner=Scanner(path)
    serve(path,scanner,token,host=os.getenv("HOST","0.0.0.0"),port=int(os.getenv("PORT","8765")))
if __name__=="__main__":main()

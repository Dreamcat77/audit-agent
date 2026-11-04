"""Keep test credentials in memory and off the process command line and evidence."""
import argparse
import getpass
import json
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('base')
parser.add_argument('username')
parser.add_argument('fixtures')
parser.add_argument('executable')
args=parser.parse_args()
password=getpass.getpass('QA password (memory only): ')
config=dict(base=args.base,username=args.username,password=password,fixtures=args.fixtures,executable=args.executable)
root=Path(__file__).resolve().parents[2]
try:
    result=subprocess.run(['node',str(root/'tools/unified-material-refactor/browser.mjs')],input=json.dumps(config),text=True,cwd=root)
    raise SystemExit(result.returncode)
finally:
    password=None
    config.clear()

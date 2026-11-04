"""Reuse the proven QA switcher against this task's freshly captured original baseline."""
import argparse
import importlib.util
from pathlib import Path
import sys

parser=argparse.ArgumentParser()
parser.add_argument('snapshot')
parser.add_argument('archive')
parser.add_argument('sha256')
parser.add_argument('revision')
args=parser.parse_args()
snapshot=Path(args.snapshot)
assert snapshot.parent==Path('/home/admin/audit-agent-backups') and snapshot.name.startswith('unified-material-')
assert (snapshot/'checkpoint-public.json').is_file()
spec=importlib.util.spec_from_file_location('qa_switch','/home/admin/unified-qa-switch.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.BACKUP=snapshot
sys.argv=[sys.argv[0],args.archive,args.sha256,args.revision]
module.main()

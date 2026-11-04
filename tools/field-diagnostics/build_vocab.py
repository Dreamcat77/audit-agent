"""Build-time only: public technical identifiers from application source, not customer data."""
import json
from pathlib import Path
import re

root=Path(__file__).resolve().parents[2]
values=set('PROVIDER_DIAGNOSTIC OTHER_OR_UNAVAILABLE SUCCESS FAILED ERROR PARTIAL COMPLETE COMPLETED RUNNING QUEUED WAITING READY UNKNOWN MISSING STOP LENGTH PASS FAIL NOT_EVALUATED CONFIRMED SYSTEM_AUTO_DECISION SOURCE_VERIFIED AGENT_PRECHECK TENDER_COMPLIANCE'.split())
for directory in ('backend/src/main/java','services/audit-ai/src/audit_ai'):
    for file in (root/directory).rglob('*'):
        if file.suffix not in ('.java','.py'):continue
        values.update(re.findall(r'''["']([A-Z][A-Z0-9_]{0,95})["']''',file.read_text(encoding='utf-8')))
(Path(__file__).parent/'vocabulary.json').write_text(json.dumps(sorted(values),ensure_ascii=True,indent=2)+'\n',encoding='utf-8')
print('PUBLIC_TECHNICAL_VOCABULARY='+str(len(values)))

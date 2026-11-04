"""Procurement amount roles, Chinese uppercase, automatic modules and immutable replay on real QA26."""
import contextlib
import argparse
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import importlib.util
import json
import time
import uuid
import requests

s=importlib.util.spec_from_file_location('qa','/home/admin/qa26-acceptance.py');qa=importlib.util.module_from_spec(s);s.loader.exec_module(qa)
parser=argparse.ArgumentParser();parser.add_argument('--revision',choices=['r2','r3','r6'],default='r2');revision=parser.parse_args().revision
folder=qa.BACKUP/'feature-fixtures-r2';folder.mkdir(exist_ok=True)
data={'招标文件.txt':'招标文件\n项目编号：QA26-P-TEXT\n项目名称：会议终端采购\n采购单位：澄川测试服务中心\n预算金额：300000元。\n最高限价：290000元。\n采购范围：会议终端10台，含安装、培训和一年保修。\n报价为含税总价。',
      '星河报价.txt':'询价报价单\n项目编号：QA26-P-TEXT\n项目名称：会议终端采购\n供应商名称：澄川星河设备有限公司\n含税报价合计：人民币贰拾伍万元整。\n明细：会议终端，数量10台，含税单价25000元，行金额250000元。',
      '明川报价.txt':'报价单\n项目编号：QA26-P-TEXT\n项目名称：会议终端采购\n供应商名称：澄川明川设备有限公司\n含税报价合计：270000元。\n明细：会议终端，数量10台，含税单价27000元，行金额270000元。',
      '启衡报价.txt':'报价单\n项目编号：QA26-P-TEXT\n项目名称：会议终端采购\n供应商名称：澄川启衡设备有限公司\n含税报价合计：310000元。\n明细：会议终端，数量10台，含税单价31000元，行金额310000元。'}
paths=[]
for name,content in data.items():
    path=folder/name
    expected=('合成测试材料，仅供验收。\n'+content).encode('utf8')
    if path.exists():assert path.read_bytes()==expected
    else:path.write_bytes(expected)
    paths.append(path)
state=qa.identity('procurement-tender-budget-'+revision,{'key':str(uuid.uuid4())})
case=qa.CLIENT.call('GET','/api/v1/procurement/cases/operations/'+state['key'])
if not case:
    with contextlib.ExitStack() as stack:
        files=[('files',(p.name,stack.enter_context(p.open('rb')),'text/plain')) for p in paths]
        case=qa.CLIENT.call('POST','/api/v1/procurement/cases',headers={'Idempotency-Key':state['key']},files=files)
until=time.monotonic()+3600;last=None
while case['status'] in {'UPLOADING','QUEUED','RUNNING'}:
    marker=(case['status'],sum(d['status']=='EXTRACTED' for d in case['documents']))
    if marker!=last:print(json.dumps({'featureProgress':marker}),flush=True);last=marker
    assert time.monotonic()<until,'FEATURE_DEADLINE';time.sleep(3)
    case=qa.CLIENT.call('GET','/api/v1/procurement/cases/'+case['id'])
report=case['report'];failures=[]
expected={'澄川星河设备有限公司':Decimal('250000'),'澄川明川设备有限公司':Decimal('270000'),'澄川启衡设备有限公司':Decimal('310000')}
if report['budget'] is None or Decimal(str(report['budget']))!=Decimal('300000'):failures.append('TENDER_BUDGET_MISSING_OR_CONFUSED_WITH_CEILING')
if revision in {'r3','r6'}:
    tender=next(d for d in case['documents'] if d['name']=='招标文件.txt')
    original=qa.CLIENT.call('GET','/api/v1/procurement/cases/'+case['id']+'/files/'+tender['id']+'/evidence')
    if not any(f['type']=='purchaserName' and f['value']=='澄川测试服务中心' and f['verification']=='SOURCE_VERIFIED' for f in original['facts']):
        failures.append('EXPLICIT_PURCHASER_NOT_EXTRACTED')
if {o['supplier']:Decimal(str(o['amount'])) for o in report['offers']}!=expected:failures.append('QUOTE_ROLE_OR_UPPERCASE_AMOUNT_INCORRECT')
if sum(len(o['lines']) for o in report['offers'])!=3 or any(o['lineValidation']!='VERIFIED' for o in report['offers']):failures.append('LINE_CALCULATION_INCORRECT')
calc=report.get('calculation')
if not calc or sum(q['overBudget'] for q in calc['quotes'])!=1:failures.append('OVER_BUDGET_CHECK_INCORRECT')
if report['modules'].get('QUOTE')!='COMPLETED':failures.append('QUOTE_MODULE_NOT_COMPLETED')
# This intentionally has no demand/award/evaluation/clarification files. A
# useful, executed partial review is correct; requiring an all-clear is not.
for name in ['AGENT_PRECHECK','TENDER_COMPLIANCE']:
    review=report.get('reviews',{}).get(name) or {}
    if review.get('status') not in {'COMPLETED','PARTIAL'} or not review.get('checks') or not review.get('model'):
        failures.append('AUTOMATIC_MODULE_NOT_EXECUTED_'+name)
    if review.get('errorCode') not in {None,'SEMANTIC_OR_SOURCE_COVERAGE_INCOMPLETE'}:
        failures.append('AUTOMATIC_MODULE_EXECUTION_ERROR_'+name)
    if revision=='r3':
        system=[c for c in review.get('checks',[]) if c['code']=='MATERIAL_COVERAGE']
        if len(system)!=1 or system[0]['status']!='SYSTEM_SCOPE':failures.append('COVERAGE_NOT_SYSTEM_OWNED_'+name)
        if any('缺少报价单' in c.get('explanation','') or '报价单未上传' in c.get('explanation','') for c in review.get('checks',[])):
            failures.append('UPLOADED_QUOTATION_FALSELY_MISSING_'+name)
frozen=qa.fingerprint(case)
def replay(reverse):
    session=requests.Session();session.trust_env=False;session.headers.update(qa.CLIENT.session.headers);session.cookies.update(qa.CLIENT.session.cookies)
    with contextlib.ExitStack() as stack:
        ordered=list(reversed(paths)) if reverse else paths
        files=[('files',(p.name,stack.enter_context(p.open('rb')),'text/plain')) for p in ordered]
        r=session.post(qa.BASE+'/api/v1/procurement/cases',headers={'Idempotency-Key':state['key']},files=files,timeout=90)
    body=r.json();return r.status_code,(body.get('data') or {}).get('id')
with ThreadPoolExecutor(max_workers=6) as pool:replays=list(pool.map(replay,[False,True,False,True,False,True]))
if any(status not in {200,409} or status==200 and id!=case['id'] for status,id in replays):failures.append('CONCURRENT_REPLAY_NOT_IDEMPOTENT')
if qa.fingerprint(qa.CLIENT.call('GET','/api/v1/procurement/cases/'+case['id']))!=frozen:failures.append('REPLAY_CHANGED_FROZEN_CASE')
with paths[0].open('rb') as f:
    r=qa.CLIENT.session.post(qa.BASE+'/api/v1/procurement/cases',headers={'Idempotency-Key':state['key']},files={'files':(paths[0].name,f,'text/plain')},timeout=90)
if r.status_code!=409:failures.append('SAME_OPERATION_ALLOWED_DIFFERENT_MANIFEST')
for d in case['documents']:
    if qa.OTHER.session.get(qa.BASE+'/api/v1/procurement/cases/'+case['id']+'/files/'+d['id']+'/evidence',timeout=30).status_code not in {403,404}:failures.append('CROSS_OWNER_SOURCE_ACCESS')
row={'caseId':case['id'],'status':case['status'],'failures':failures,'budgetFromTenderOnly':True,'separateApprovalFile':False,
     'uppercaseQuote':True,'overBudgetSuppliers':1,'concurrentReplays':replays,'frozenFingerprint':frozen,'realModel':True,'realDm8':True,'synthetic':True}
qa.save('procurement-feature-'+revision+'-raw',case);qa.save('procurement-feature-'+revision+'-verification',row)
print('QA26_FEATURE '+json.dumps(row,ensure_ascii=False),flush=True)
raise SystemExit(bool(failures))

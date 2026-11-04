"""Synthetic uploaded-budget split checks. Prints counts/status, never real material or config."""
import concurrent.futures
import io
import json
import subprocess
import sys
import time
import uuid
import zipfile
from decimal import Decimal
from xml.sax.saxutils import escape
import requests

auth=json.loads(sys.stdin.readline())
base='http://127.0.0.1'
session=requests.Session()

def csrf(client):
    r=client.get(base+'/api/v1/auth/csrf',timeout=20)
    assert r.status_code==200,'CSRF_HTTP'
    data=r.json()['data'];client.headers[data['headerName']]=data['token']

def login():
    csrf(session)
    assert session.post(base+'/api/v1/auth/login',json={'username':auth['username'],'password':auth['password']},timeout=20).status_code==200,'LOGIN_HTTP'
    csrf(session)

def get(path):
    r=session.get(base+path,timeout=30)
    assert r.status_code==200,'GET_HTTP_'+str(r.status_code)
    return r.json()['data']

def workbook(project,amounts,year='2026'):
    rows=[['项目编号','项目名称','科目编码','科目名称','申报金额','批复参考金额','预算年度','申报部门']]
    for index,amount in enumerate(amounts):
        rows.append([project,'合成拆分测试-'+project,'SYN-'+str(index),'网络设备-'+str(index),str(amount),str(amount+100),'2026' if year is None else year,'合成预算测试部门'])
    sheet=['<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for row_index,row in enumerate(rows,1):
        cells=''.join('<c r="'+chr(65+i)+str(row_index)+'" t="inlineStr"><is><t>'+escape(value)+'</t></is></c>' for i,value in enumerate(row))
        sheet.append('<row r="'+str(row_index)+'">'+cells+'</row>')
    sheet.append('</sheetData></worksheet>')
    files={
        '[Content_Types].xml':'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
        '_rels/.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        'xl/workbook.xml':'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="合成预算" sheetId="1" r:id="rId1"/></sheets></workbook>',
        'xl/_rels/workbook.xml.rels':'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
        'xl/worksheets/sheet1.xml':''.join(sheet)}
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as package:
        for name,content in files.items():package.writestr(name,content.encode('utf-8'))
    return output.getvalue()

login()
prefix='SYN-SPLIT-'+str(uuid.uuid4())[:8]
specs=[('below',[4000,Decimal('5999.99')],'2026'),('middle',[30000,20000],'2025'),('above',[80000,120000],'2026'),('copy',[4000,Decimal('5999.99')],'2026'),('version',[6000,4000],'2026'),('many',list(range(1,31)),'2026')]
ids=[]
for label,amounts,year in specs:
    project=prefix+('below' if label in ('copy','version') else label)
    data=workbook(project,amounts,year)
    r=session.post(base+'/api/v1/budget/material-uploads',headers={'Idempotency-Key':str(uuid.uuid4())},files={'file':('合成拆分验证-'+label+'.xlsx',data,'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')},timeout=60)
    assert r.status_code==200,'UPLOAD_HTTP_'+str(r.status_code)
    ids.append(r.json()['data']['id'])

deadline=time.monotonic()+240
while True:
    reviews=[get('/api/v1/budget/reviews/'+task) for task in ids]
    if all((r.get('materialReference') or {}).get('status')=='COMPLETED' for r in reviews):break
    assert time.monotonic()<deadline,'BACKGROUND_DEADLINE';time.sleep(2)
page=get('/api/v1/budget/material-risk/candidates?taskIds='+','.join(ids))
assert len(page['items'])==40,'CANDIDATE_COUNT'
groups=[[r['source'] for r in page['items'] if r['source']['taskId']==task] for task in ids]

def analyze(selections,operation=None):
    body=operation or {'id':str(uuid.uuid4()),'selections':selections}
    r=session.post(base+'/api/v1/budget/material-risk/analyses',json=body,timeout=30)
    assert r.status_code==200,'ANALYSIS_HTTP_'+str(r.status_code)
    return r.json()['data']

saved=[]
for selections,amount,band in zip(groups[:3],['9999.99','50000','200000'],['BELOW_10K','FROM_10K_TO_100K','ABOVE_100K']):
    result=analyze(selections);a=result['splitAnalysis']
    assert a['countedItems']==2 and a['complete'],'SELECTED_COUNT'
    assert Decimal(str(a['totalAmount']))==Decimal(amount) and a['bandCode']==band,'BAND_ARITHMETIC'
    saved.append(result)

operation={'id':str(uuid.uuid4()),'selections':groups[0]+groups[3]}
def concurrent_analysis(_):
    client=requests.Session();client.cookies.update(session.cookies);client.headers.update(session.headers)
    r=client.post(base+'/api/v1/budget/material-risk/analyses',json=operation,timeout=60)
    assert r.status_code==200,'CONCURRENT_HTTP_'+str(r.status_code)
    return r.json()['data']
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:results=list(pool.map(concurrent_analysis,range(6)))
assert all(r==results[0] for r in results),'CONCURRENT_SNAPSHOT_CHANGED'
duplicate=results[0]['splitAnalysis']
assert duplicate['countedItems']==4 and Decimal(str(duplicate['totalAmount']))==Decimal('19999.98') and not duplicate['exclusions'],'EXPLICIT_CHOICES_NOT_ADDED'
saved.append(results[0])
conflict=analyze(groups[0]+groups[4])['splitAnalysis']
assert conflict['countedItems']==4 and Decimal(str(conflict['totalAmount']))==Decimal('19999.99'),'VERSION_FILTER_STILL_ACTIVE'
combined=analyze([groups[0][0],groups[1][0]])['splitAnalysis']
assert combined['countedItems']==2 and combined['totalAmount']==34000,'YEAR_FILTER_STILL_ACTIVE'
for selections,expected in [(groups[4],10000),([groups[2][0],groups[1][1]],100000)]:
    edge=analyze(selections)['splitAnalysis'];assert edge['totalAmount']==expected and edge['bandCode']=='FROM_10K_TO_100K','BOUNDARY_GAP'
seen=set();cursor=None
while True:
    url='/api/v1/budget/material-risk/browse'+('?cursor='+cursor if cursor else '')
    chunk=get(url);assert len(chunk['items'])<=12,'UNBOUNDED_CANDIDATE_PAGE'
    for item in chunk['items']:
        key=(item['source']['taskId'],item['source']['lineIndex']);assert key not in seen,'CURSOR_DUPLICATE';seen.add(key)
    cursor=chunk['nextCursor']
    if cursor is None:break
expected={(s['taskId'],s['lineIndex']) for group in groups for s in group}
assert expected <= seen,'CURSOR_OMITTED_PROJECTS'
assert any(item['source']['taskId']==ids[5] for item in get('/api/v1/budget/material-risk/browse?keyword=网络设备-29')['items']),'ROW_NAME_SEARCH'
assert sum(item['source']['taskId']==ids[3] for item in get('/api/v1/budget/material-risk/browse?keyword=合成拆分验证-copy.xlsx')['items'])==2,'FILE_SEARCH'
for _ in range(14):analyze(groups[1])
history_page=get('/api/v1/budget/material-risk/history?page=1&keyword='+prefix)
assert history_page['total']>=14 and len(history_page['items'])==6,'HISTORY_PAGE_ONE'
history_next=get('/api/v1/budget/material-risk/history?page=2&keyword='+prefix)
assert len(history_next['items'])==6 and not {i['id'] for i in history_page['items']} & {i['id'] for i in history_next['items']},'HISTORY_PAGE_TWO'
assert get('/api/v1/budget/material-risk/history?date=2000-01-01')['total']==0,'DATE_FILTER'
anonymous=requests.Session()
assert anonymous.get(base+'/api/v1/budget/material-risk/browse',timeout=20).status_code==401,'BROWSE_AUTH'
assert get('/api/v1/budget/material-risk/analyses/'+operation['id'])==saved[-1],'DUPLICATE_SNAPSHOT'
drift={**operation,'selections':groups[0]}
assert session.post(base+'/api/v1/budget/material-risk/analyses',json=drift,timeout=20).status_code==409,'RANGE_DRIFT_ACCEPTED'
for result in saved:assert get('/api/v1/budget/material-risk/analyses/'+result['id'])==result,'REOPEN_SNAPSHOT_CHANGED'
if '--restart' in sys.argv:
    subprocess.run(['systemctl','restart','audit-backend.service'],check=True)
    deadline=time.monotonic()+75
    while True:
        try:
            if requests.get(base+'/api/v1/health',timeout=2).status_code==200:break
        except requests.RequestException:pass
        assert time.monotonic()<deadline,'RESTART_DEADLINE';time.sleep(1)
    session=requests.Session();login()
    for result in saved:assert get('/api/v1/budget/material-risk/analyses/'+result['id'])==result,'RESTART_SNAPSHOT_CHANGED'
    assert analyze(operation['selections'],operation)==saved[-1],'RESTART_REPLAY_CHANGED'
print(json.dumps({'selectedBudgetSumRealHttp':'PASS','syntheticFiles':6,'projectRows':40,'amountBandsVerified':3,'exactBoundariesVerified':2,'allExplicitChoicesAdded':True,'crossYearAdded':True,'candidateCursorPagesAndNameSearch':'PASS','historySixPerPageAndDateFilter':'PASS','concurrentIdenticalSnapshots':6,'immutableReopen':'PASS','restartReopen':'PASS' if '--restart' in sys.argv else 'NOT_RUN','rangeDriftHttp':409}),flush=True)

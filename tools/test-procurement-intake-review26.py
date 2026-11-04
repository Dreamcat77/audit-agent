"""Synthetic acceptance: saved procurement intake -> automatic review -> durable reopen.

Generated test-account credentials arrive only through BudgetBackgroundRunner's pipe.
No customer materials or environment values are printed or exported.
"""
import hashlib
import io
import json
import sys
import time
import uuid
import zipfile
from xml.sax.saxutils import escape
import requests

auth = json.loads(sys.stdin.readline())
client = requests.Session()
base = 'http://127.0.0.1/api/v1'

def csrf():
    token = client.get(base+'/auth/csrf', timeout=15).json()['data']
    client.headers[token['headerName']] = token['token']

def call(method, path, **kwargs):
    response = client.request(method, base+path, timeout=kwargs.pop('timeout', 900), **kwargs)
    assert response.status_code == 200, 'HTTP_'+str(response.status_code)+'_'+path
    data = response.json()
    assert data['code'] == 200, 'BUSINESS_REJECTED_'+path
    return data['data']

def login():
    client.cookies.clear(); csrf()
    call('POST', '/auth/login', json={k:auth[k] for k in ('username','password')})
    csrf()

def docx(lines):
    output = io.BytesIO()
    body = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
    body += ''.join('<w:p><w:r><w:t>'+escape(line)+'</w:t></w:r></w:p>' for line in lines)
    body += '</w:body></w:document>'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        archive.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        archive.writestr('word/document.xml',body)
    return output.getvalue()

login()
run = uuid.uuid4().hex[:8]
name = '仿真办公设备采购自动审查-'+run
metadata = ['本材料全部为合成测试内容，非真实业务资料。','项目名称：'+name,'项目编号：TEST-AUTO-'+run,
    '申请部门：信息中心','采购类型：货物','预算金额：人民币壹拾万元整（100000.00元）',
    '预算科目编码：A1001','预算科目名称：办公设备','采购方式：公开招标','申请人：仿真经办员']
inputs = [('仿真采购需求-'+run+'.docx',docx(['采购需求书']+metadata+['需求内容：采购二十台办公计算机，按性能配置验收，交货期三十个日历日。'])),
          ('仿真招标文件-'+run+'.docx',docx(['采购招标文件']+metadata+['采购内容：采购二十台办公计算机。',
              '台式计算机必须使用星桥品牌，仅限星桥品牌参与。','投标单位须为本地注册企业，注册资本不低于1000万元。',
              '设备提供三年保修，按配置和数量验收，交付期三十个日历日。']) )]
intakes=[]
for filename, content in inputs:
    operation = str(uuid.uuid4())
    row = call('POST','/procurement/materials',headers={'Idempotency-Key':operation},
        files={'file':(filename,content,'application/vnd.openxmlformats-officedocument.wordprocessingml.document')})
    intakes.append(row['id'])
deadline = time.monotonic()+900
while True:
    rows=[call('GET','/procurement/materials/'+identifier) for identifier in intakes]
    if all(row['status']=='PARSED' for row in rows): break
    assert not any(row['status'] in ('FAILED','UNKNOWN') for row in rows), 'INTAKE_EXTRACTION_INCOMPLETE'
    assert time.monotonic()<deadline, 'INTAKE_EXTRACTION_TIMEOUT'
    time.sleep(2)
print('SYNTHETIC_INTAKE_PARSED files=2',flush=True)
created=call('POST','/procurement/projects',json={'sourceProjectId':intakes[0],'sourceMaterialIds':intakes,
    'projectName':name,'departmentName':'人工核对后的信息中心','procurementType':'货物','budgetAmount':'100000.00',
    'subjectCode':'A1001','subjectName':'办公设备','procurementMethod':'公开招标','applicantName':'仿真经办员',
    'requirementDescription':'采购二十台办公计算机','submitForApproval':False})
project=created['id']; root='/procurement/projects/'+project
result=call('POST',root+'/review-materials/from-intake/analyze')
assert result.get('report'), 'AUTOMATIC_REVIEW_NO_REPORT'
assert len(result['files'])==2, 'REVIEW_SCOPE_LOST'
assert len({row['document']['id'] for row in result['files']})==2, 'REVIEW_SOURCE_DUPLICATED'
for filename,content in inputs:
    file=next(row for row in result['files'] if row['fileName']==filename)
    assert file['document']['sha256']==hashlib.sha256(content).hexdigest(), 'ORIGINAL_BYTES_CHANGED'
assert result['report']['project']['budgetAmount']==100000, 'CONFIRMED_BUDGET_CHANGED'
findings=result['report']['tender']['findings']
assert findings and any('品牌' in json.dumps(f,ensure_ascii=False) for f in findings), 'TARGETED_BRAND_RISK_MISSING'
print('SYNTHETIC_AUTO_REVIEW report=GENERATED sourceFiles=2 targetedBrandRisk=FOUND',flush=True)
same=call('POST',root+'/review-materials/from-intake/analyze')
assert same['report']['id']==result['report']['id'], 'REPEATED_COMMAND_REGENERATED_REPORT'
assert {r['document']['id'] for r in same['files']}=={r['document']['id'] for r in result['files']}, 'REPEATED_COMMAND_DUPLICATED_FILES'
login()
reopened=call('GET',root+'/review-report')
assert reopened['id']==result['report']['id'], 'NEW_SESSION_REPORT_CHANGED'
assert reopened['tender']['findings']==result['report']['tender']['findings'], 'REOPENED_RISKS_CHANGED'
listed=call('GET','/procurement/projects',params={'keyword':name,'page':1,'size':20})
record=next(p for p in listed['items'] if p['id']==project)
assert record['departmentName']=='人工核对后的信息中心', 'USER_CORRECTION_LOST'
print(json.dumps({'case':'procurement-save-and-auto-review','result':'PASS','projectId':project,
    'reportId':reopened['id'],'sourceFiles':2,'duplicateFiles':0,'secondUploadRequests':0,
    'repeatedPostReport':'SAME','newSessionReport':'SAME','confirmedForm':'PRESERVED'},ensure_ascii=False),flush=True)

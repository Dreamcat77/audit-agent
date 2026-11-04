"""Isolated loopback service using existing model configuration; synthetic inputs only.

Run as the operator on the representative server. The running services and their
configuration are read, never stopped, changed or dumped.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import httpx


def run():
    root=Path(__file__).resolve().parents[1]
    pid=subprocess.check_output(['systemctl','show','audit-ai.service','-p','MainPID','--value'],text=True).strip()
    if not pid.isdigit() or pid=='0':raise RuntimeError('AI_SERVICE_NOT_RUNNING')
    env=dict(os.environ)
    for pair in Path('/proc',pid,'environ').read_bytes().split(b'\0'):
        if b'=' not in pair:continue
        key,value=pair.split(b'=',1)
        if key.startswith(b'AUDIT_'):env[key.decode()]=value.decode()
    env['PYTHONPATH']=str(root/'services/audit-ai/src')
    env['AUDIT_MATERIAL_CHECKPOINT_ROOT']=''
    env['PYTHONUNBUFFERED']='1'
    # Allocate one loopback port and fail if another process wins the close/bind race.
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    base=f'http://127.0.0.1:{port}'
    output=root/'isolated-model-results.json'
    summary={};observed={}
    with (root/'isolated-ai.log').open('wb') as log:
        process=subprocess.Popen([sys.executable,'-m','uvicorn','audit_ai.main:create_app','--factory',
            '--host','127.0.0.1','--port',str(port)],env=env,cwd=root,stdout=log,stderr=subprocess.STDOUT)
        try:
            with httpx.Client(base_url=base,timeout=180) as client:
                for _ in range(40):
                    if process.poll() is not None:raise RuntimeError('ISOLATED_SERVICE_START_FAILED')
                    try:
                        health=client.get('/health')
                        if health.status_code==200:break
                    except httpx.TransportError:pass
                    time.sleep(.25)
                else:raise RuntimeError('ISOLATED_HEALTH_TIMEOUT')
                curl=subprocess.run(['curl','--fail','--silent','--show-error',base+'/health'],
                    capture_output=True,text=True,check=True)
                summary['curlHealth']=json.loads(curl.stdout)
                policy=client.get('/ai/v1/materials/policy').json()['policyId']
                for kind,text in [('BUDGET','项目名称：合成设备更新项目；科目名称：办公设备。该科目本次申请人民币壹拾贰万元整，经批复核准人民币壹拾万元整。'),
                    ('PROCUREMENT','项目名称：合成办公设备采购；项目编号：SYNTH-01；预算金额：人民币壹拾捌万元整；申请部门：信息中心；申请人：张老师；采购类型：货物；采购方式：公开招标；科目编码：31002；科目名称：办公设备。')]:
                    data=dict(businessType=kind,materialId='synthetic-'+kind.lower(),sha256=hashlib.sha256(text.encode()).hexdigest(),
                        segments=[dict(locator='synthetic/p1',text=text)],policyId=policy,callLimit=32,timeLimitMillis=120000)
                    result=client.post('/ai/v1/materials/extract-fields',json=data)
                    observed[kind]=result.json();assert result.status_code==200,(kind,result.status_code)
                    facts=[f for r in result.json()['data']['rows'] for f in r['facts']]
                    assert result.json()['data']['callCount']>0
                    for fact in facts:assert fact['excerpt'] in text and fact['value'] in fact['excerpt']
                    required={'projectName','subjectName','declaredAmount','approvalAmount'} if kind=='BUDGET' else {'projectName','projectNo','budgetAmount','departmentName','applicantName'}
                    assert required<={f['field'] for f in facts},kind+'_MISSING_REQUIRED_FIELDS'
                    if kind=='BUDGET':
                        assert any(f['field']=='declaredAmount' and '壹拾贰万' in f['value'] for f in facts)
                        assert any(f['field']=='approvalAmount' and '壹拾万元' in f['value'] for f in facts)
                    summary[kind]='GROUNDED_FIELDS_AND_USAGE_PASS'
                for name,qualification in [('same-item','补充说明：台式计算机允许同等性能品牌参与。'),
                    ('other-item','补充说明：打印机允许同等性能品牌参与；台式计算机品牌要求维持不变。')]:
                    segments=[dict(sourceLocator='p1',pageNumber=1,text='台式计算机必须使用星桥品牌。'),
                              dict(sourceLocator='p2',pageNumber=2,text='设备应提供安装、培训和售后服务。'*110),
                              dict(sourceLocator='p3',pageNumber=3,text=qualification)]
                    result=client.post('/ai/v1/agent/procurement-tender-compliance',json=dict(projectId='synthetic',reviewId=name,
                        segments=segments,fullText='\n'.join(s['text'] for s in segments),ruleFindings=[dict(ruleCode='synthetic-brand',
                        riskCategory='指定品牌',riskLevel='WARNING',matchedText='台式计算机必须使用星桥品牌。',sourceLocator='p1',remediationSuggestion='核对品牌适用范围。')]))
                    observed[name]=result.json();assert result.status_code==200,(name,result.status_code)
                    brand=[f for f in result.json()['data']['findings'] if f['sourceLocator']=='p1' and '品牌' in f['riskCategory']]
                    assert brand and any('原文另有说明' in f['rationale'] for f in brand)
                    if name=='other-item':assert all(f['riskLevel']!='INFO' for f in brand),'FOREIGN_ITEM_EXCEPTION_MUST_NOT_CLEAR_RISK'
                    summary[name]='COUNTEREVIDENCE_REVIEW_PASS'
                summary['health']=health.json();summary['base']=base
                output.write_text(json.dumps(dict(summary=summary,syntheticResponses=observed),ensure_ascii=False,indent=2),encoding='utf-8')
                print(json.dumps(summary,ensure_ascii=True),flush=True)
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
            if observed and not output.exists():output.write_text(json.dumps(dict(summary=summary,syntheticResponses=observed),ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__':run()

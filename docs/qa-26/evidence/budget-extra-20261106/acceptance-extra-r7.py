"""Synthetic acceptance: preserve receipts before uploading; inspect actual saved model output."""
import hashlib,json,sys,time,uuid
from pathlib import Path
import requests
root=Path(__file__).resolve().parent
auth=json.loads(sys.stdin.readline());session=requests.Session();session.trust_env=False
base='http://127.0.0.1'
def csrf():
    r=session.get(base+'/api/v1/auth/csrf',timeout=20);r.raise_for_status();d=r.json()['data'];session.headers[d['headerName']]=d['token']
def get(path):
    r=session.get(base+path,timeout=30);assert r.status_code==200,(path,r.status_code);return r.json()['data']
csrf();r=session.post(base+'/api/v1/auth/login',json=auth,timeout=30);assert r.status_code==200,'LOGIN_FAILED';csrf()
scenarios=[
 ('01门槛下差一分',[('资金申请.txt','项目名称：青松阅览室设备更新项目\n项目预算：999999.99元\n以上为项目整体预算，拟用于设备更新。')]),
 ('02年度百万非整体',[('年度经费.txt','项目名称：云杉楼维保项目\n本年度项目预算：1200000元，仅用于2026年度，并非全部三年金额。')]),
 ('03历史显式标签排除',[('综合说明.txt','项目名称：松林服务站修缮项目\n本次项目预算：800000元。\n以下为其他项目的历史参考，不属于本次预算：\n历史项目预算：3000000元。\n旧项目名称：旧楼扩建项目。')]),
 ('04分包不得当整项',[('采购包预算.txt','项目名称：枫桥终端更新项目\n采购包一预算金额：600000元。\n采购包二预算金额：700000元。\n以上仅列分包金额，未列整项预算，不将任一分包金额作为整项金额。')]),
 ('05精确到分的差额',[('申报.txt','项目名称：海棠档案设备项目\n预算年度：2026\n申报合计：100000.10元'),('批复.txt','项目名称：海棠档案设备项目\n预算年度：2026\n批复总额：100000.09元')]),
 ('06跨文件用途冲突',[('资金安排意见.txt','关于白桦图书室改造的资金安排意见\n核定本项目经费仅限书架采购与阅览设施更新，不得列支职工旅游。'),('实施请示.txt','白桦图书室改造实施请示\n本次沿用图书室改造项目经费，具体支出调整为组织全体职工外出旅游，不再采购书架，亦未办理用途变更审批。')]),
 ('07修订已批准不误报',[('原批复.txt','项目名称：水杉照明改造项目\n2026年1月5日原批复规定仅可购买灯具，不包含配电柜。'),('调整批复及实施说明.txt','项目名称：水杉照明改造项目\n2026年2月5日正式调整批复：批准新增配电柜购置，原1月5日批复中不含配电柜的范围限制由本调整批复替代。\n本次实施方案依据2月5日调整批复购买配电柜，未实施职工福利等其他支出。')]),
 ('08审批顺序正常',[('审批执行记录.txt','项目名称：紫荆值守设施更新项目\n本项目规定：须经财务部门书面批准后方可付款。\n执行记录：2026年3月1日财务部门已书面批准，2026年3月5日向供货单位付款；不存在未批先付。')]),
 ('09无金额仍应有意见',[('执行说明.txt','关于银杏实验室防护设施更新的执行说明\n资金用途经批复限定为实验室安全防护设施。经办人员在本次执行中将全部经费用于购置员工生日礼品，未办理预算用途变更。')]),
 ('10同文件副本不重复预算',[('资金计划.txt','项目名称：榆树大厅修缮项目\n项目预算：600000元\n该金额为本项目整体预算。'),('资金计划副本.txt','项目名称：榆树大厅修缮项目\n项目预算：600000元\n该金额为本项目整体预算。')]),
 ('11批复版本保留不武断',[('原批复.txt','项目名称：栾树配电设施更新项目\n预算年度：2026\n2026年1月原批复总额：100000元'),('调整批复.txt','项目名称：栾树配电设施更新项目\n预算年度：2026\n2026年2月调整批复总额：150000元\n本次调整替代1月原批复。')]),
 ('12非预算材料仍收尾',[('会议通知.txt','关于召开办公会议的通知\n本周五上午九点在二楼会议室召开档案管理工作例会，请各部门准时参会。')]),
]
from PIL import Image
import io
scan=Path('/home/admin/office-formats-v9-20261015/fixtures/sample.png')
for angle in [90,180]:
 out=io.BytesIO();Image.open(scan).rotate(angle,expand=True).save(out,format='PNG')
 scenarios.append((f'13扫描图片旋转{angle}',[(f'预算扫描旋转{angle}.png',out.getvalue())]))
filler='本项工作落实设备盘点与资产登记，实施时按实际验收进度完善记录。\n'*240
scenarios+=[
 ('14长文末尾用途问题',[('执行情况长文.txt','项目名称：棕榈业务终端更新项目\n'+filler+'批复明确本项目资金限用于业务终端更新，不包含员工福利。\n'+filler+'本次实际执行不购置业务终端，拟将全部经费用于向员工发放节日礼盒，未作预算用途调整。')]),
 ('15同义措辞用途一致',[('经费批复.txt','项目名称：香樟档案数字化项目\n资金仅用于纸质档案扫描及电子数据整理。'),('执行方案.txt','项目名称：香樟档案数字化项目\n实施内容为将纸质卷宗逐页图像化、整理电子目录与数据，不涉及其他支出。')]),
 ('16重复词不应误判历史',[('改造计划.txt','项目名称：旧设备更新项目\n旧设备更新项目预算：850000元。\n本次项目不属于历史项目预算：900000元。\n两处金额是同一现行项目不同版本，尚未确认采用哪一版。')]),
]
scenarios=[s for s in scenarios if s[0][:2] in {'03','06','08','13','14'}]
statefile=root/'acceptance-extra-r7-receipts.json'
state=json.loads(statefile.read_text()) if statefile.exists() else []
for label,files in scenarios:
    row=next((x for x in state if x['label']==label),None)
    if row is None:
        row={'label':label,'batchId':str(uuid.uuid4()),'members':[{'operationKey':str(uuid.uuid4()),'fileName':name,'fileSize':len(text if isinstance(text,bytes) else text.encode())} for name,text in files],'ids':[]}
        state.append(row);statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2))
    r=session.post(base+'/api/v1/budget/material-uploads/batches',json={'batchId':row['batchId'],'members':row['members']},timeout=30);assert r.status_code==200,'REGISTER_FAILED'
    for index,((name,text),member) in enumerate(zip(files,row['members'])):
        if len(row['ids'])>index:continue
        raw=text if isinstance(text,bytes) else text.encode()
        r=session.post(base+'/api/v1/budget/material-uploads',headers={'Idempotency-Key':member['operationKey']},data={'batchId':row['batchId']},files={'file':(name,raw,'application/octet-stream')},timeout=180)
        assert r.status_code==200,('UPLOAD_FAILED',r.status_code)
        row['ids'].append(r.json()['data']['id']);statefile.write_text(json.dumps(state,ensure_ascii=False,indent=2))
    print(json.dumps({'submitted':label,'batchId':row['batchId']},ensure_ascii=False),flush=True)
deadline=time.monotonic()+1800;last=''
while True:
    progress=[];finished=True
    for row in state:
        batch=get('/api/v1/budget/material-uploads/batches/'+row['batchId']);row['status']=batch['status']
        progress.append((row['label'],batch['status']))
        if batch['status']!='REVIEWED':finished=False
    signature=json.dumps(progress,ensure_ascii=False)
    if signature!=last:print(signature,flush=True);last=signature
    if finished:break
    assert time.monotonic()<deadline,'ACCEPTANCE_DEADLINE'
    time.sleep(5)
results=[]
for row in state:
    refs=[get('/api/v1/budget/reviews/'+i)['materialReference'] for i in row['ids']]
    assert all(r and r['status']=='COMPLETED' for r in refs),'RESULT_MISSING'
    assert refs==[get('/api/v1/budget/reviews/'+i)['materialReference'] for i in row['ids']],'SAVED_RESULT_CHANGED'
    # Only these synthetic test materials are exported; customer diagnostics must never export text.
    sources={i:get('/api/v1/budget/material-uploads/'+i+'/source-text?start=0&size=20000') for i in row['ids']}
    found={json.dumps(a,sort_keys=True,ensure_ascii=False):a for r in refs for a in r.get('assessments',[])}
    for r in refs:
        for e in [e for a in r.get('assessments',[]) for e in a['sources']]+[e for l in r['lines'] for e in l['sources']]:
            if e.get('start') is not None:assert sources[e['taskId']]['text'][e['start']:e['end']]==e['excerpt'],'BAD_EXACT_QUOTE'
    results.append({**row,'references':refs,'uniqueAssessments':list(found.values()),'sourceLengths':{i:s['totalChars'] for i,s in sources.items()}})
    print(json.dumps({'finished':row['label'],'batchId':row['batchId'],'codes':[a['code'] for a in found.values()],'coverage':[r.get('analysisCoverage') for r in refs]},ensure_ascii=False),flush=True)
(root/'acceptance-extra-r7-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print('SYNTHETIC_ACCEPTANCE_SAVED',flush=True)

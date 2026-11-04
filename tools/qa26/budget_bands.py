"""Real uploaded-budget selection, exact boundary sums, history and owner isolation."""
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import importlib.util
import io
import json
import time
import uuid
import zipfile
from xml.sax.saxutils import escape
from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--after-r4', action='store_true')
args = parser.parse_args()
if args.after_r4:
    root = Path('/home/admin/audit-agent-backups/qa26-stage2-20261027')
    deadline = time.monotonic() + 1800
    print('WAITING_FOR_VERIFIED_R4_SWITCH', flush=True)
    while True:
        phase = json.loads((root / 'advance-r3-state.json').read_text())
        assert phase['status'] != 'FAILED', 'PRIOR_R3_STAGE_FAILED'
        ready = root / 'finish-ui-state.json'
        if ready.exists():
            assert json.loads(ready.read_text())['status'] == 'PASS', 'R4_SWITCH_NOT_VERIFIED'
            break
        assert time.monotonic() < deadline, 'R4_WAIT_DEADLINE'
        time.sleep(10)

spec = importlib.util.spec_from_file_location('qa', '/home/admin/qa26-acceptance.py')
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)
amounts = ['4000', '5999.99', '6000', '30000', '20000', '80000']
rows = [['项目编号', '项目名称', '科目编码', '科目名称', '申报金额', '批复参考金额', '预算年度']]
for i, amount in enumerate(amounts):
    rows.append(['QA26-BAND-' + str(i), '合成临界项目' + str(i), 'QA-BAND', '电子设备', amount, '200000', '2026'])
sheet = ['<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
for index, row in enumerate(rows, 1):
    cells = ''.join('<c r="' + chr(65 + j) + str(index) + '" t="inlineStr"><is><t>' + escape(v) + '</t></is></c>' for j, v in enumerate(row))
    sheet.append('<row r="' + str(index) + '">' + cells + '</row>')
sheet.append('</sheetData></worksheet>')
parts = {
    '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>',
    '_rels/.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    'xl/workbook.xml': '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="合成预算" sheetId="1" r:id="rId1"/></sheets></workbook>',
    'xl/_rels/workbook.xml.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
    'xl/worksheets/sheet1.xml': ''.join(sheet),
}
buffer = io.BytesIO()
with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as package:
    for name, content in parts.items():
        package.writestr(name, content.encode('utf8'))
data = buffer.getvalue()
state = qa.identity('budget-bands-r4', {'key': str(uuid.uuid4())})
job = qa.CLIENT.call('GET', '/api/v1/budget/material-uploads/operations/' + state['key'])
if not job:
    job = qa.CLIENT.call('POST', '/api/v1/budget/material-uploads', headers={'Idempotency-Key': state['key']},
                         files={'file': ('合成金额临界测试.xlsx', data, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')})
deadline = time.monotonic() + 1800
while True:
    review = qa.CLIENT.call('GET', '/api/v1/budget/reviews/' + job['id'])
    if (review.get('materialReference') or {}).get('status') == 'COMPLETED':
        break
    assert time.monotonic() < deadline, 'BUDGET_BANDS_DEADLINE'
    time.sleep(3)
page = qa.CLIENT.call('GET', '/api/v1/budget/material-risk/candidates?taskIds=' + job['id'])
assert len(page['items']) == len(amounts) and not page['hasMore'], 'BUDGET_BANDS_CANDIDATE_COUNT'
selected = {}
for row in page['items']:
    value = str(Decimal(str(row['line']['declaredAmount'])).normalize())
    assert value not in selected, 'DUPLICATE_DECLARED_AMOUNT'
    selected[value] = row['source']

def key(value):
    return str(Decimal(value).normalize())

results = []
for left, right, expected, band in [
    ('4000', '5999.99', '9999.99', 'BELOW_10K'),
    ('4000', '6000', '10000', 'FROM_10K_TO_100K'),
    ('30000', '20000', '50000', 'FROM_10K_TO_100K'),
    ('80000', '20000', '100000', 'FROM_10K_TO_100K'),
    ('80000', '30000', '110000', 'ABOVE_100K'),
]:
    operation = qa.identity('budget-bands-r4-' + expected, {'id': str(uuid.uuid4()), 'selections': [selected[key(left)], selected[key(right)]]})
    result = qa.CLIENT.call('POST', '/api/v1/budget/material-risk/analyses', json=operation)
    analysis = result['splitAnalysis']
    assert analysis['complete'] and analysis['countedItems'] == 2, 'INCOMPLETE_SELECTED_SUM'
    assert Decimal(str(analysis['totalAmount'])) == Decimal(expected) and analysis['bandCode'] == band, 'WRONG_EXACT_BOUNDARY'
    frozen = qa.fingerprint(result)
    assert qa.fingerprint(qa.CLIENT.call('GET', '/api/v1/budget/material-risk/analyses/' + operation['id'])) == frozen, 'READ_CHANGED_RESULT'
    assert qa.OTHER.session.get(qa.BASE + '/api/v1/budget/material-risk/analyses/' + operation['id'], timeout=30).status_code in {403, 404}, 'CROSS_OWNER_BUDGET_ANALYSIS'
    if expected == '10000':
        def replay(_):
            import requests
            client = requests.Session()
            client.trust_env = False
            client.cookies.update(qa.CLIENT.session.cookies)
            client.headers.update(qa.CLIENT.session.headers)
            response = client.post(qa.BASE + '/api/v1/budget/material-risk/analyses', json=operation, timeout=60)
            assert response.status_code == 200, 'BUDGET_REPLAY_HTTP'
            return response.json()['data']
        with ThreadPoolExecutor(max_workers=6) as pool:
            assert all(qa.fingerprint(r) == frozen for r in pool.map(replay, range(6))), 'BUDGET_REPLAY_CHANGED_SNAPSHOT'
        changed = dict(operation, selections=operation['selections'][:1])
        assert qa.CLIENT.session.post(qa.BASE + '/api/v1/budget/material-risk/analyses', json=changed, timeout=30).status_code == 409, 'MUTABLE_SELECTION_OPERATION'
    results.append({'total': expected, 'band': band, 'analysisId': operation['id'], 'fingerprint': frozen})
new_login = qa.Client(qa.ACCESS['primary'])
for row in results:
    assert qa.fingerprint(new_login.call('GET', '/api/v1/budget/material-risk/analyses/' + row['analysisId'])) == row['fingerprint'], 'NEW_LOGIN_CHANGED_BUDGET_RESULT'
for index in range(8):
    operation = qa.identity('budget-bands-r4-history-' + str(index), {
        'id': str(uuid.uuid4()), 'selections': [selected[key('30000')], selected[key('20000')]]})
    qa.CLIENT.call('POST', '/api/v1/budget/material-risk/analyses', json=operation)
first = qa.CLIENT.call('GET', '/api/v1/budget/material-risk/history?page=1&keyword=合成临界项目')
second = qa.CLIENT.call('GET', '/api/v1/budget/material-risk/history?page=2&keyword=合成临界项目')
assert first['total'] >= 13 and len(first['items']) == len(second['items']) == 6, 'RISK_HISTORY_PAGINATION'
assert not {r['id'] for r in first['items']} & {r['id'] for r in second['items']}, 'RISK_HISTORY_PAGE_OVERLAP'
assert qa.CLIENT.call('GET', '/api/v1/budget/material-risk/history?date=2000-01-01')['total'] == 0, 'RISK_HISTORY_DATE_FILTER'
seen, cursor = set(), None
for _ in range(100):
    page = qa.CLIENT.call('GET', '/api/v1/budget/material-risk/browse' + ('?cursor=' + cursor if cursor else ''))
    assert len(page['items']) <= 12, 'UNBOUNDED_RISK_CANDIDATE_PAGE'
    for row in page['items']:
        item = (row['source']['taskId'], row['source']['lineIndex'])
        assert item not in seen, 'RISK_CURSOR_REPEATED_ITEM'
        seen.add(item)
    cursor = page['nextCursor']
    if not cursor:
        break
else:
    raise AssertionError('RISK_CURSOR_NOT_BOUNDED')
assert {(r['taskId'], r['lineIndex']) for r in selected.values()} <= seen, 'RISK_CURSOR_OMITTED_SELECTION'
summary = {'realModel': True, 'realDm8': True, 'synthetic': True, 'scenarios': results,
           'sixWayIdempotentReplay': True, 'changedSelectionHttp': 409, 'crossOwnerDenied': True,
           'newLoginReopenUnchanged': True, 'historyPagesDoNotOverlap': True,
           'historyDateFilter': True, 'allSelectedCandidatesDiscoverable': True, 'failures': []}
qa.save('budget-bands-r4-verification', summary)
print(json.dumps(summary, ensure_ascii=False), flush=True)

"""Local-only probe. stdout is a fixed statistical projection, never provider data."""
import argparse
from collections import Counter
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import sys
import uuid

FIELDS=frozenset('documentType projectName projectNo purchaserName agencyName budgetAmount ceilingAmount supplierName quoteAmount awardAmount productDescription itemName quantity unitPrice lineAmount'.split())
KEYWORDS=('预算','本项目','人民币','采购人','招标人','委托方','供应商','投标报价','最高限价','中标金额','合同金额')

def compact(text):
    return ''.join(text.split())

def project_model(payload, segments):
    """The only provider-output export boundary. Never return unknown text/keys."""
    result={'fieldCounts':{},'supportedCounts':{},'amountMatchesSource':{},'keywordHits':{},'unknownFields':0,'invalidItems':0}
    if not isinstance(payload,dict) or not isinstance(payload.get('rows'),list):
        return dict(result,envelope='INVALID')
    if len(payload['rows'])>100:return dict(result,envelope='TOO_MANY_ROWS')
    fields=Counter();supported=Counter();amounts=Counter();hits=Counter()
    for row in payload['rows']:
        if not isinstance(row,dict) or not isinstance(row.get('facts'),list):result['invalidItems']+=1;continue
        for fact in row['facts'][:100]:
            if not isinstance(fact,dict):result['invalidItems']+=1;continue
            name=fact.get('field')
            if not isinstance(name,str) or name not in FIELDS:result['unknownFields']+=1;continue
            fields[name]+=1
            value,excerpt,locator=(fact.get(k) for k in ('value','excerpt','locator'))
            if not all(isinstance(v,str) and v for v in (value,excerpt,locator)):result['invalidItems']+=1;continue
            sources=[s['text'] for s in segments if s['locator']==locator]
            valid=any(compact(excerpt) in compact(s) and compact(value) in compact(excerpt) for s in sources)
            if valid:
                supported[name]+=1
                if name.endswith('Amount') or name in ('unitPrice','lineAmount'):amounts[name]+=1
                for word in KEYWORDS:
                    if word in excerpt:hits[word]+=1
    result.update(envelope='VALID',fieldCounts=dict(fields),supportedCounts=dict(supported),amountMatchesSource=dict(amounts),keywordHits=dict(hits))
    return result

def model_probe(input_path):
    logging.disable(logging.CRITICAL)
    from audit_ai.config import Settings
    from audit_ai.model.gateway import OpenAiCompatibleModelGateway,ModelGatewayFailure
    gateway=OpenAiCompatibleModelGateway(Settings.from_env())
    documents=json.loads(Path(input_path).read_text())
    selected=sorted((d for d in documents if d.get('segments')),key=lambda d:not d.get('budgetMoneyHint',False))[:2]
    result=[]
    for doc in selected:
        segments=doc['segments'];rid='diagnostic-'+str(uuid.uuid4())
        request={'businessType':'PROCUREMENT_AUTO','materialId':doc['fileId'],'segments':segments,
                 'sha256':hashlib.sha256(json.dumps(segments,ensure_ascii=False).encode()).hexdigest()}
        row={'fileId':doc['fileId'],'requestId':rid,'suppliedChars':sum(len(s['text']) for s in segments),'readOnlyProbe':True}
        try:
            completion=gateway.extract_material_fields('PROCUREMENT_AUTO',json.dumps(request,ensure_ascii=False),rid,timeout_seconds=180)
            row.update(project_model(completion.payload,segments))
            row['finishReason']=completion.finish_reason if completion.finish_reason in ('STOP','LENGTH') else 'OTHER'
            row['status']='RECEIVED'
        except ModelGatewayFailure as exc:
            safe={'READ_TIMEOUT','CONNECT_TIMEOUT','CONNECT_ERROR','OUTPUT_TOKEN_LIMIT','INVALID_PROVIDER_JSON','INVALID_PROVIDER_RESPONSE','HTTP_ERROR','CAPACITY_BUSY'}
            row.update(status='MODEL_ERROR',reason=exc.reason if exc.reason in safe else 'OTHER_MODEL_ERROR')
        except Exception:
            row.update(status='PROBE_UNAVAILABLE')
        result.append(row)
    return {'mode':'EXISTING_CONFIGURED_MODEL_LOCAL_ONLY','maxCalls':2,'calls':result,'notFullDocumentReview':True,
            'rawModelOutputExported':False,'businessWrites':False}

def pdf_probe(path):
    import pypdfium2 as pdfium
    # Do not render pages or initialize OCR. Native text/rotation inspection only.
    with pdfium.PdfDocument(path) as doc:
        pages=list(range(min(256,len(doc))))
        result={'status':'OK','pageCount':len(doc),'sampledPages':[],'pageLimitReached':len(doc)>256,'imageOrientationMeasured':False,'ocrRepeated':False}
        for index in pages:
            page=doc[index]
            try:
                tp=page.get_textpage()
                try:text=tp.get_text_range()
                finally:tp.close()
                w,h=page.get_size()
                result['sampledPages'].append({'page':index+1,'rotation':page.get_rotation(),'width':round(w),'height':round(h),
                    'nativeChars':len(text),'budgetKeywordHits':text.count('预算'),'moneyKeywordHits':sum(text.count(w) for w in ('金额','人民币','元')),
                    'replacementChars':text.count('\ufffd'),'purchaserKeywordHits':sum(text.count(k) for k in ('采购人','招标人','委托方','采购单位')),
                    'budgetNearCurrencyHits':len(re.findall(r'预算[^\r\n]{0,160}?(?:人民币|[0-9壹贰叁肆伍陆柒捌玖拾佰仟万亿零〇一二三四五六七八九十百]+\s*(?:万|亿)?(?:元|圆))',text))})
            finally:page.close()
        return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['model','pdf']);parser.add_argument('input');args=parser.parse_args()
    try:
        if args.mode=='pdf':
            import resource
            resource.setrlimit(resource.RLIMIT_AS,(1_500_000_000,1_500_000_000))
            result=pdf_probe(args.input)
        else:result=model_probe(args.input)
    except Exception:result={'status':'PROBE_UNAVAILABLE','contentExported':False}
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()

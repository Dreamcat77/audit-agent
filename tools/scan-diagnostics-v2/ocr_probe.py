"""Small-PDF replay via the running OCR service. Only statistics are exported."""
import io,json,logging,math,os,re,sys,time,uuid
from pathlib import Path

SIGNALS={
 'BUDGET':r'预算|核定经费|经费安排|资金总额|项目总投资',
 'DECLARED':r'申报金额|申请金额|申请经费|本次申报|本次申请',
 'APPROVAL':r'批复金额|批准金额|批复参考金额|核定金额',
 'CEILING':r'最高限价|控制价|限价',
 'MONEY':r'[0-9０-９][0-9０-９,，.． ]{0,35}(?:万元|千元|元|人民币)|[壹贰叁肆伍陆柒捌玖貳參陸拾佰仟萬億零〇一二三四五六七八九十百千万亿]{2,}[元圆圓]',
}
def features(text):
 compact=''.join(text.split());counts={key:len(re.findall(pattern,compact)) for key,pattern in SIGNALS.items()}
 nearby=0
 for match in re.finditer(SIGNALS['BUDGET'],compact):
  nearby+=bool(re.search(SIGNALS['MONEY'],compact[max(0,match.start()-30):match.end()+120]))
 return {'chars':len(text),'nonWhitespaceChars':len(compact),'hanChars':sum('\u4e00'<=c<='\u9fff' for c in text),
         'replacementChars':text.count('\ufffd'),'signals':counts,'budgetNearMoney':nearby,
         'financialNumeralChars':sum(c in '壹贰叁肆伍陆柒捌玖貳參陸拾佰仟萬億' for c in text)}

def source_path(doc,storage):
 """DB references are untrusted; resolve only the existing storage namespaces."""
 base=Path(storage).resolve()
 if doc.get('businessType')=='BUDGET':
  reference=doc.get('budgetReference','')
  if not isinstance(reference,str) or not re.fullmatch(r'[a-fA-F0-9-]{36}\.[a-zA-Z0-9]{1,8}',reference):return None
  parent=base/'budget-materials';path=parent/reference
 else:
  key=doc.get('storageKey','')
  if not isinstance(key,str) or not re.fullmatch('[a-f0-9]{64}',key):return None
  parent=base/'objects'/key[:2];path=parent/(key+'.bin')
 if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(base):return None
 if path.stat().st_size>500*1024*1024:return None
 return path

def excerpt_segments(texts):
 """Bounded diagnostic snippets, retained privately on the server only."""
 ranked=sorted(enumerate(texts,1),key=lambda p:-(features(p[1])['budgetNearMoney']*100+features(p[1])['signals']['BUDGET']))
 segments=[]
 for page,text in ranked[:2]:
  match=re.search(SIGNALS['BUDGET'],' '.join(text.split()))
  # Search raw OCR with whitespace-tolerant characters to keep exact citations.
  raw=re.search(r'预\s*算|批\s*复|申\s*报|申\s*请',text)
  start=max(0,raw.start()-120) if raw else 0
  value=text[start:start+2200]
  if value.strip():segments.append({'locator':'diagnostic/ocr/page:'+str(page)+'/offset:'+str(start),'text':value})
 return segments

def replay(inputs_path,selected_path):
 import httpx
 import pypdfium2 as pdfium
 logging.disable(logging.CRITICAL)
 docs=json.loads(Path(inputs_path).read_text(encoding='utf-8'));selected=json.loads(Path(selected_path).read_text(encoding='utf-8'))
 by_id={d['fileId']:d for d in docs};rows=[]
 endpoint=os.environ.get('AUDIT_OCR_BASE_URL') or os.environ.get('AUDIT_AI_BASE_URL')
 if not endpoint:return {'status':'OCR_ENDPOINT_UNAVAILABLE','files':[]}
 # Keep the configured internal HTTP route; no external service or new OCR instance.
 url=endpoint.rstrip('/')+'/ai/v1/ocr/extract'
 with httpx.Client(timeout=httpx.Timeout(connect=5,read=600,write=60,pool=5),trust_env=False,follow_redirects=False) as client:
  for item in selected[:3]:
   doc=by_id.get(item.get('fileId'));row={'fileId':item.get('fileId'),'status':'NOT_STARTED','pages':[],'businessResultsModified':False}
   rows.append(row)
   if not doc:row['status']='SOURCE_METADATA_UNAVAILABLE';continue
   path=source_path(doc,os.environ['AUDIT_FILE_STORAGE_ROOT'])
   if path is None:row['status']='SOURCE_NOT_READABLE_IN_STORAGE';continue
   try:
    texts=[]
    with pdfium.PdfDocument(path) as pdf:
     row['pageCount']=len(pdf)
     if len(pdf)!=4:row['status']='NOT_FOUR_PAGE_PDF';continue
     for index in range(len(pdf)):
      page=pdf[index];bitmap=None;image=None
      try:
       width,height=page.get_size()
       if min(width,height)<=0 or max(width,height)>20000:raise ValueError('PAGE_SIZE')
       scale=min(150/72,2400/max(width,height));bitmap=page.render(scale=scale);image=bitmap.to_pil();buffer=io.BytesIO();image.save(buffer,format='PNG')
       rid='diagnostic-'+str(uuid.uuid4());started=time.monotonic()
       response=client.post(url,files={'file':('diagnostic-page.png',buffer.getvalue(),'image/png')},headers={'X-Request-Id':rid})
       stats={'page':index+1,'requestId':rid,'httpStatus':response.status_code,'elapsedMs':round((time.monotonic()-started)*1000),'renderWidth':image.width,'renderHeight':image.height}
       row['pages'].append(stats)
       if response.status_code!=200:
        stats['status']='OCR_HTTP_ERROR';row['status']='OCR_HTTP_ERROR';break
       payload=response.json();data=payload.get('data')
       if payload.get('code')!=200 or not isinstance(data,dict) or not isinstance(data.get('text'),str):
        stats['status']='OCR_RESPONSE_INVALID';row['status']='OCR_RESPONSE_INVALID';break
       text=data['text']
       if len(text)>192000:stats['status']='OCR_RESPONSE_TEXT_BOUND';row['status']='OCR_RESPONSE_TEXT_BOUND';break
       stats.update(features(text));stats['status']='OK' if text.strip() else 'EMPTY'
       confidence=data.get('confidence')
       if isinstance(confidence,(float,int)) and not isinstance(confidence,bool) and math.isfinite(confidence):stats['confidenceMilli']=round(max(0,min(1,confidence))*1000)
       texts.append(text)
      finally:
       if image is not None:image.close()
       if bitmap is not None:bitmap.close()
       page.close()
     else:
      row['status']='COMPLETED';row['features']=features('\n'.join(texts))
      doc['segments']=excerpt_segments(texts);doc['budgetMoneyHint']=row['features']['budgetNearMoney']>0;doc['ocrReplay']=True
   except httpx.TimeoutException:row['status']='OCR_TIMEOUT_OUTCOME_UNKNOWN'
   except Exception:row['status']='OCR_PROBE_UNAVAILABLE'
 # Private input contains the selected local OCR snippets, never copied to export.
 Path(inputs_path).write_text(json.dumps(docs,ensure_ascii=False),encoding='utf-8')
 return {'status':'COLLECTED','files':rows,'maxFiles':3,'pagesPerFile':4,'renderDpi':150,'maxRenderDimension':2400,'perPageReadTimeoutSeconds':600,'httpEnvironmentProxyUsed':False,
         'sameOcrService':True,'separateDiagnosticReplay':True,'notProofOfOriginalRead':True,'databaseWrites':0,'contentExported':False}

if __name__=='__main__':
 try:result=replay(sys.argv[1],sys.argv[2])
 except Exception:result={'status':'OCR_PROBE_UNAVAILABLE','contentExported':False,'files':[]}
 print(json.dumps(result))

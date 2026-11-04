import contextlib,io,json,os,sys,tempfile,threading,unittest
from http.server import BaseHTTPRequestHandler,HTTPServer
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent))
import ocr_probe,probe,report

FID='a2f9da97-93f9-4365-a755-c28f110b5f40'
SECRET='绝密单位_PERSON_CANARY'
TEXT=SECRET+'\n本项目的预\n算为人民币壹佰万元整。\n申报金额：1000000元。'

class BudgetDiagTest(unittest.TestCase):
 def test_projection_never_returns_business_values(self):
  result=ocr_probe.features(TEXT)
  self.assertGreater(result['budgetNearMoney'],0)
  self.assertGreater(result['financialNumeralChars'],0)
  self.assertNotIn(SECRET,json.dumps(result));self.assertNotIn('1000000',json.dumps(result))

 def test_budget_amount_fact_is_supported_in_budget_model_projection(self):
  payload={'rows':[{'facts':[{'field':'declaredAmount','value':'1000000元','excerpt':'申报金额：1000000元','locator':'p1'}]}]}
  result=probe.project_model(payload,[{'locator':'p1','text':TEXT}])
  self.assertEqual(result['supportedCounts'],{'declaredAmount':1})
  self.assertNotIn('1000000',json.dumps(result))

 def test_budget_reference_rejects_escape_and_symlink(self):
  with tempfile.TemporaryDirectory() as folder:
   for reference in ('../'+SECRET,'/etc/passwd','a/../../etc/shadow',SECRET+'.pdf'):
    self.assertIsNone(ocr_probe.source_path({'businessType':'BUDGET','budgetReference':reference},folder))
   root=Path(folder);(root/'budget-materials').mkdir();p=root/'budget-materials'/(FID+'.pdf');p.write_bytes(b'%PDF-')
   self.assertEqual(ocr_probe.source_path({'businessType':'BUDGET','budgetReference':p.name},folder),p)
   outside=root/'other.pdf';outside.write_bytes(b'%PDF-');p.unlink()
   try:p.symlink_to(outside)
   except OSError:return
   self.assertIsNone(ocr_probe.source_path({'businessType':'BUDGET','budgetReference':p.name},folder))

 def test_budget_excerpts_are_never_misreported_as_full_ocr_coverage(self):
  database={'connection':'OK','cases':{'BUDGET_REVIEW_TASK':{'rows':[{'caseId':FID,'documents':[{'fileId':FID,'status':'FAILED','text':{'chars':0},'textKind':'FACT_EXCERPTS_NOT_FULL_OCR','persistedFactCount':0}]}]}}}
  with tempfile.TemporaryDirectory() as directory:
   p=Path(directory)/'events';p.write_text('')
   result=report.build_findings(database,[{'fileId':FID,'pageCount':4,'sampledPages':[]}],p,{})['files'][0]
  self.assertIsNone(result['missingSavedTextPages']);self.assertIsNone(result['savedChars'])
  self.assertNotIn('NO_SAVED_TEXT_READ_FAILURE_OR_UNCERTAIN',result['observations'])

 def test_replay_has_money_is_hint_not_proof_of_original_ocr(self):
  db={'cases':{'BUDGET_REVIEW_TASK':{'rows':[{'documents':[{'fileId':FID,'factTypes':{},'resultLineCount':0,'extractionIssues':{'AMOUNT_ROLE_UNVERIFIED':1}}]}]}}}
  result=report.budget_findings(db,{'files':[{'fileId':FID,'status':'COMPLETED','features':{'budgetNearMoney':1,'chars':200}}]})[0]
  self.assertFalse(result['rootCauseConfirmed'])
  self.assertIn('REPLAY_OCR_HAS_BUDGET_MONEY_WITHOUT_STORED_BUDGET_AMOUNT',result['observations'])
  self.assertIn('AMOUNT_ROLE_REJECTED_RECORDED',result['observations'])

 def test_real_four_page_render_and_http_ocr_privacy(self):
  from reportlab.pdfgen.canvas import Canvas
  from PIL import Image,ImageDraw
  from reportlab.lib.utils import ImageReader
  requests=[]
  class Handler(BaseHTTPRequestHandler):
   def do_POST(self):
    body=self.rfile.read(int(self.headers['Content-Length']));requests.append(body)
    self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
    self.wfile.write(json.dumps({'code':200,'data':{'text':TEXT,'confidence':0.98}}).encode())
   def log_message(self,*args):pass
  server=HTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  try:
   with tempfile.TemporaryDirectory() as folder:
    root=Path(folder);(root/'budget-materials').mkdir();pdf=root/'budget-materials'/(FID+'.pdf')
    image=Image.new('RGB',(500,700),'white');ImageDraw.Draw(image).text((20,20),'BUDGET PRIVATE 1000000',fill='black')
    c=Canvas(str(pdf))
    for angle in (0,90,180,270):c.setPageRotation(angle);c.drawImage(ImageReader(image),30,30,450,600);c.showPage()
    c.save();image.close()
    inputs=root/'private.json';selected=root/'selected.json'
    inputs.write_text(json.dumps([{'fileId':FID,'businessType':'BUDGET','budgetReference':pdf.name,'size':pdf.stat().st_size}]))
    selected.write_text(json.dumps([{'fileId':FID}]))
    with patch.dict(os.environ,{'AUDIT_FILE_STORAGE_ROOT':folder,'AUDIT_OCR_BASE_URL':'http://127.0.0.1:'+str(server.server_port)},clear=False):
     result=ocr_probe.replay(inputs,selected)
    self.assertEqual(result['files'][0]['status'],'COMPLETED');self.assertEqual(len(requests),4)
    self.assertTrue(all(b'Content-Type: image/png' in body for body in requests))
    self.assertEqual([row['page'] for row in result['files'][0]['pages']],[1,2,3,4])
    encoded=json.dumps(result,ensure_ascii=False)
    for secret in (SECRET,'1000000','壹佰万元整',pdf.name,folder):self.assertNotIn(secret,encoded)
    private=json.loads(inputs.read_text(encoding='utf-8'));self.assertTrue(private[0]['ocrReplay']);self.assertTrue(private[0]['segments'])
    self.assertTrue(result['notProofOfOriginalRead']);self.assertEqual(result['databaseWrites'],0)
  finally:server.shutdown();server.server_close();thread.join()

if __name__=='__main__':unittest.main(verbosity=2)

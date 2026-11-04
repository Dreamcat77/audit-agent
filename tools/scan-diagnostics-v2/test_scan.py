import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from types import SimpleNamespace
import contextlib
import io
import os

ROOT=Path(__file__).parent
sys.path.insert(0,str(ROOT))
import report
import probe
spec=importlib.util.spec_from_file_location('scan_collector',ROOT/'collect.py');collector=importlib.util.module_from_spec(spec);spec.loader.exec_module(collector)
FID='a2f9da97-93f9-4365-a755-c28f110b5f40'
OTHER='b2f9da97-93f9-4365-a755-c28f110b5f40'
SECRET='CANARY_PRIVATE_CUSTOMER_公司'

class ScanTest(unittest.TestCase):
    def summary(self,doc,pdf=None,events=None):
        doc={'fileId':FID,'status':'FAILED','fileOrder':1,'text':{'chars':0},'calls':0,'persistedFactCount':0,**doc}
        database={'connection':'OK','cases':{'AUDIT_CASE':{'rows':[{'caseId':OTHER,'documents':[doc]}]}}}
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'events';p.write_text(''.join(json.dumps(e)+'\n' for e in events or []))
            result=report.build_findings(database,[{'fileId':FID,**(pdf or {})}],p,{'procurementLimits':{'classDefaults':{'maxPages':100}}})
        self.assertNotIn(SECRET,json.dumps(result,ensure_ascii=False));return result['files'][0]

    def test_162_pages_default_is_hint_not_proven_cause(self):
        r=self.summary({}, {'pageCount':162,'sampledPages':[{'page':i+1,'nativeChars':0} for i in range(162)]})
        self.assertIn('PDF_EXCEEDS_RUNNING_CLASS_DEFAULT_PAGE_LIMIT_CHECK_OVERRIDES',r['observations'])
        self.assertNotIn('READ_BLOCKED_BY_PAGE_LIMIT',r['observations'])
        self.assertEqual(len(r['missingSavedTextPages']),162)

    def test_page_limit_log_correlates_only_same_file(self):
        event={'resourceId':FID,'reason':'PAGE_LIMIT_EXCEEDED'}
        self.assertIn('READ_BLOCKED_BY_PAGE_LIMIT',self.summary({},events=[event])['observations'])
        event['resourceId']=OTHER
        self.assertNotIn('READ_BLOCKED_BY_PAGE_LIMIT',self.summary({},events=[event])['observations'])

    def test_two_page_failure_not_explained_by_large_pdf(self):
        r=self.summary({}, {'pageCount':2,'sampledPages':[{'page':1,'nativeChars':0},{'page':2,'nativeChars':0}]})
        self.assertNotIn('READ_BLOCKED_BY_PAGE_LIMIT',r['observations'])
        self.assertNotIn('PDF_EXCEEDS_RUNNING_CLASS_DEFAULT_PAGE_LIMIT_CHECK_OVERRIDES',r['observations'])
        self.assertIn('NATIVE_TEXT_SPARSE_SCAN_OR_RASTER_PDF_LIKELY',r['observations'])

    def test_saved_budget_signal_not_published(self):
        r=self.summary({'text':{'chars':90,'signals':{'BUDGET':{'count':1}}},'calls':1,'status':'EXTRACTED'})
        self.assertIn('TEXT_EXISTS_WITHOUT_PUBLISHED_FACTS',r['observations'])
        self.assertIn('BUDGET_WORDING_EXISTS_WITHOUT_PUBLISHED_BUDGET',r['observations'])

    def test_ledger_incomplete_units_preserved_as_unknown(self):
        r=self.summary({'extractionLedgers':[{'attempt':1,'ledgerId':OTHER,'rows':[{'STATUS':'UNKNOWN','UNIT_COUNT':18,'NEXT_UNIT':6,'CALL_COUNT':7,'USED_MILLIS':900000}]}]})
        self.assertIn('MODEL_EXTRACTION_UNITS_INCOMPLETE',r['observations'])
        self.assertEqual(r['modelLedgers'][0]['completedUnits'],6)

    def test_whitespace_pdf_not_treated_as_native_body(self):
        r=self.summary({}, {'pageCount':2,'sampledPages':[{'page':1,'nativeChars':100,'nonWhitespaceChars':0}]})
        self.assertIn('NATIVE_TEXT_SPARSE_SCAN_OR_RASTER_PDF_LIKELY',r['observations'])

    def test_failed_page_probe_is_not_evidence_of_a_scan(self):
        r=self.summary({}, {'pageCount':2,'sampledPages':[{'page':1,'status':'NATIVE_PAGE_INSPECTION_FAILED'}]})
        self.assertNotIn('NATIVE_TEXT_SPARSE_SCAN_OR_RASTER_PDF_LIKELY',r['observations'])
        self.assertIn('NATIVE_PAGE_INSPECTION_INCOMPLETE',r['observations'])

    def test_raw_business_details_not_projected(self):
        r=self.summary({'name':SECRET,'errorCode':SECRET,'status':SECRET,'facts':[{'value':'2200000','excerpt':SECRET}]})
        self.assertEqual(r['status'],'OTHER_OR_UNAVAILABLE');self.assertEqual(r['recordedReasons'],[])
        self.assertNotIn('2200000',json.dumps(r))

    def test_new_log_metrics_and_operation_id_kept(self):
        vocab={'MATERIAL_EXTRACTION_UNIT','START'}
        r=collector.safe_event({'MESSAGE':'MATERIAL_EXTRACTION_UNIT operationId='+FID+' unitIndex=2 unitCount=18 elapsedMillis=4000 outcome=START filename='+SECRET},vocab)
        self.assertEqual(r['operationId'],FID);self.assertEqual(r['unitCount'],18);self.assertNotIn(SECRET,json.dumps(r))

    def test_running_class_default_and_private_env(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'app.jar'
            with zipfile.ZipFile(p,'w') as z:z.writestr('BOOT-INF/classes/com/fuquan/audit/procurement/autocase/AuditCaseMaterialLimits.class',b'\xca\xfe\xba\xbe${audit.procurement.material.max-pages:100}')
            r=report.processing_limits(p,{'AUDIT_DB_PASSWORD':SECRET,'AUDIT_PROCUREMENT_MATERIAL_MAX_PAGES':'200'})
            self.assertEqual(r['classDefaults']['maxPages'],100);self.assertFalse(r['effectiveLimitsVerified']);self.assertNotIn(SECRET,json.dumps(r))

    def test_real_pdf_162_native_pages(self):
        from reportlab.pdfgen.canvas import Canvas
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'native.pdf';c=Canvas(str(path))
            for i in range(162):c.drawString(30,700,SECRET+' 2200000');c.showPage()
            c.save();result=probe.pypdf_probe(path)
            self.assertEqual(result['pageCount'],162);self.assertEqual(len(result['sampledPages']),162)
            self.assertTrue(all(p['nativeChars']>0 for p in result['sampledPages']));self.assertNotIn(SECRET,json.dumps(result));self.assertNotIn('2200000',json.dumps(result))

    def test_real_scanned_pdf_with_rotated_page(self):
        from reportlab.pdfgen.canvas import Canvas
        from PIL import Image,ImageDraw
        from reportlab.lib.utils import ImageReader
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'scan.pdf';image=Image.new('RGB',(600,800),'white');ImageDraw.Draw(image).text((40,40),'PRIVATE AMOUNT 2200000',fill='black')
            c=Canvas(str(path));c.drawImage(ImageReader(image),0,0,600,800);c.showPage();c.setPageRotation(180);c.drawImage(ImageReader(image),0,0,600,800);c.showPage();c.save()
            result=probe.pypdf_probe(path)
            self.assertEqual(result['pageCount'],2);self.assertEqual([p['nativeChars'] for p in result['sampledPages']],[0,0]);self.assertEqual(result['sampledPages'][1]['rotation'],180)
            self.assertFalse(result['imageOrientationMeasured']);self.assertFalse(result['ocrRepeated'])

    def test_encrypted_pdf_returns_fixed_reason(self):
        from pypdf import PdfWriter
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'locked.pdf';w=PdfWriter();w.add_blank_page(width=300,height=300);w.encrypt(SECRET)
            with path.open('wb') as stream:w.write(stream)
            result=probe.pypdf_probe(path);self.assertEqual(result['status'],'PDF_ENCRYPTED_PASSWORD_REQUIRED');self.assertNotIn(SECRET,json.dumps(result))

    def test_collector_end_to_end_with_fake_db_services_and_real_pdf(self):
        from reportlab.pdfgen.canvas import Canvas
        import tarfile
        real_path=Path
        with tempfile.TemporaryDirectory() as directory:
            base=real_path(directory);jar=base/'release/backend/app.jar';jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar,'w') as z:z.writestr('BOOT-INF/classes/com/fuquan/audit/procurement/autocase/AuditCaseMaterialLimits.class',b'${audit.procurement.material.max-pages:100}')
            key='a'*64;pdf=base/'storage/objects/aa'/(key+'.bin');pdf.parent.mkdir(parents=True)
            c=Canvas(str(pdf));c.drawString(20,500,SECRET+' 2200000');c.showPage();c.save()
            environment={'AUDIT_DB_URL':'jdbc:dm:PRIVATE','AUDIT_DB_USERNAME':'PRIVATE','AUDIT_DB_PASSWORD':'PRIVATE_PASSWORD_CANARY','AUDIT_FILE_STORAGE_ROOT':str(base/'storage')}
            data={'connection':'OK','cases':{'AUDIT_CASE':{'rows':[{'caseId':OTHER,'status':'PARTIAL','documents':[{'fileId':FID,'status':'FAILED','errorCode':'PAGE_LIMIT_EXCEEDED','text':{'chars':0},'persistedFactCount':0}]}]}}}
            commands=[]
            def run(args,**kwargs):
                commands.append(args)
                if 'FieldDiag' in args:
                    i=args.index('FieldDiag');real_path(args[i+3]).write_text(json.dumps([{'fileId':FID,'caseId':OTHER,'fileOrder':1,'storageKey':key,'size':pdf.stat().st_size}]))
                    return SimpleNamespace(stdout=json.dumps(data).encode(),returncode=0)
                if 'pdf' in args:return SimpleNamespace(stdout=json.dumps(probe.pypdf_probe(args[args.index('pdf')+1])).encode(),returncode=0)
                raise AssertionError('Unexpected command')
            class Journal:
                returncode=0
                def __init__(self,*a,**k):self.stdout=io.BytesIO((json.dumps({'MESSAGE':'PIPELINE_STAGE_END resourceId='+FID+' reason=PAGE_LIMIT_EXCEEDED text='+SECRET+' amount=2200000'})+'\n').encode())
                def poll(self):return 0
                def wait(self,**k):return 0
            def mapped_path(value):
                if str(value)=='/proc/101/cmdline':return SimpleNamespace(read_bytes=lambda:('java\0-jar\0'+str(jar)+'\0').encode())
                return real_path(value)
            mapped_path.cwd=real_path.cwd
            fake_os=SimpleNamespace(name='posix',geteuid=lambda:0,getpid=lambda:12345,umask=lambda _:0o077,environ={},pathsep=os.pathsep)
            old=os.getcwd()
            try:
                os.chdir(base)
                with patch.object(collector,'os',fake_os),patch.object(collector,'Path',mapped_path),patch.object(collector,'pid_for',side_effect=lambda u:'101' if u.startswith('audit-backend') else None),patch.object(collector,'load_env',side_effect=lambda *a,**k:{} if k.get('fallback') is False else environment),patch.object(collector,'run',side_effect=run),patch.object(collector.subprocess,'Popen',Journal),patch.object(sys,'argv',['collect.py','--day','2026-10-30']),contextlib.redirect_stdout(io.StringIO()):
                    collector.main()
            finally:os.chdir(old)
            archives=list(base.glob('ScanDiag-*.tar.gz'));self.assertEqual(len(archives),1)
            with tarfile.open(archives[0]) as tar:
                self.assertEqual(set(tar.getnames()),{'database.json','pdf.json','model.json','events.jsonl','runtime.json','summary.json','findings.json','ocr.json'})
                exported='\n'.join(tar.extractfile(n).read().decode() for n in tar.getnames())
                for private in (SECRET,'2200000','PRIVATE_PASSWORD_CANARY',key,str(pdf)):self.assertNotIn(private,exported)
                self.assertEqual(json.load(tar.extractfile('model.json'))['mode'],'NOT_REQUESTED')
                self.assertEqual(json.load(tar.extractfile('pdf.json'))['files'][0]['pageCount'],1)
                self.assertIn('READ_BLOCKED_BY_PAGE_LIMIT',json.load(tar.extractfile('findings.json'))['files'][0]['observations'])
            self.assertFalse(any('model' in args for args in commands))

if __name__=='__main__':unittest.main(verbosity=2)

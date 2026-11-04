import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).parent
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

probe=module('probe');collector=module('collect')
SECRET='绝密甲方机构CANARY_XY'
AMOUNT='2200000'
FID='a2f9da97-93f9-4365-a755-c28f110b5f40'

class DiagnosticsTest(unittest.TestCase):
    def assert_private(self,value):
        encoded=json.dumps(value,ensure_ascii=False)
        for secret in (SECRET,AMOUNT,'hidden-person-name','SECRET_CANARY_UPPERCASE'):
            self.assertNotIn(secret,encoded)

    def test_native_currency_and_purchaser_projection(self):
        source=SECRET+'采购人：hidden-person-name。本项目的预算为'+AMOUNT+'人民币。'
        facts=[{'field':'budgetAmount','value':AMOUNT+'人民币','excerpt':'本项目的预算为'+AMOUNT+'人民币','locator':'loc'},
               {'field':'purchaserName','value':'hidden-person-name','excerpt':'采购人：hidden-person-name','locator':'loc'}]
        r=probe.project_model({'rows':[{'facts':facts}]},[{'locator':'loc','text':source}])
        self.assertEqual(r['supportedCounts'],{'budgetAmount':1,'purchaserName':1});self.assertEqual(r['amountMatchesSource'],{'budgetAmount':1});self.assert_private(r)

    def test_untrusted_key_never_exported(self):
        r=probe.project_model({'rows':[{'facts':[{'field':SECRET,'value':AMOUNT},{'field':['budgetAmount']}]}]},[])
        self.assertEqual(r['unknownFields'],2);self.assert_private(r)

    def test_hallucination_not_counted_as_supported(self):
        fact={'field':'budgetAmount','value':AMOUNT,'excerpt':'预算'+AMOUNT,'locator':'loc'}
        r=probe.project_model({'rows':[{'facts':[fact]}]},[{'locator':'loc','text':'无金额'}])
        self.assertEqual(r['supportedCounts'],{});self.assert_private(r)

    def test_quote_cannot_move_between_locators(self):
        fact={'field':'budgetAmount','value':AMOUNT,'excerpt':'预算'+AMOUNT,'locator':'other'}
        r=probe.project_model({'rows':[{'facts':[fact]}]},[{'locator':'loc','text':'预算'+AMOUNT}])
        self.assertEqual(r['supportedCounts'],{})

    def test_malformed_envelope(self):
        for payload in (None,SECRET,[],{'rows':SECRET}):
            self.assertEqual(probe.project_model(payload,[])['envelope'],'INVALID')

    def test_bounded_output(self):
        self.assertEqual(probe.project_model({'rows':[{}]*101},[])['envelope'],'TOO_MANY_ROWS')

    def test_raw_log_is_not_exported(self):
        raw='OCR_ORIENTATION_SELECTED fileId='+FID+' rotation_degrees=180 duration_ms=543 input_chars=20 filename='+SECRET+' text='+AMOUNT+' reason=SECRET_CANARY_UPPERCASE'
        row=collector.safe_event({'MESSAGE':raw},{'OCR_ORIENTATION_SELECTED'})
        self.assertEqual(row['rotation_degrees'],180);self.assertEqual(row['fileId'],FID);self.assert_private(row)

    def test_json_log_whitelist(self):
        raw={'event':'MODEL_STAGE_END','stage':'EXTRACT','reason':SECRET,'response':AMOUNT,'fileId':FID,'duration_ms':94}
        row=collector.safe_event({'MESSAGE':json.dumps(raw)},{'MODEL_STAGE_END','EXTRACT'})
        self.assertEqual(row['stage'],'EXTRACT');self.assert_private(row)

    def test_unknown_event_dropped(self):
        self.assertIsNone(collector.safe_event({'MESSAGE':'MODEL_SECRET_CANARY_UPPERCASE text='+SECRET},set()))

    def test_provider_diagnostic_keeps_only_schema_codes(self):
        raw='operation=CONTRACT_CASE_ANALYSIS validation_error_fields=excerpt,secret_field validation_error_types=string_too_long,secret_error reason_code=INVALID_PROVIDER_RESPONSE response='+SECRET
        row=collector.safe_event({'MESSAGE':raw},{'INVALID_PROVIDER_RESPONSE'})
        self.assertEqual(row['validation_error_fields'],['excerpt']);self.assertEqual(row['validation_error_types'],['string_too_long']);self.assert_private(row)

    def test_schema_numbers_cannot_be_business_values(self):
        row=collector.safe_event({'MESSAGE':'MODEL_STAGE_END budgetAmount='+AMOUNT+' purchaserName='+SECRET},{'MODEL_STAGE_END'})
        self.assertEqual(row,{'event':'MODEL_STAGE_END'});self.assert_private(row)

    def test_no_write_sql(self):
        java=(ROOT/'FieldDiag.java').read_text(encoding='utf-8')
        self.assertIn('c.setReadOnly(true)',java);self.assertIn('c.rollback()',java)
        for sql in ('INSERT INTO','DELETE FROM','UPDATE ','CREATE TABLE','ALTER TABLE','FOR UPDATE'):
            self.assertNotIn(sql,java)

    def test_isolated_model_launcher_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            script=Path(folder)/'fake.py';script.write_text('import sys,json;print(json.dumps(sys.argv[1:]))',encoding='utf-8')
            launcher="import sys,runpy;sys.path.insert(0,sys.argv.pop(1));script=sys.argv.pop(1);runpy.run_path(script,run_name='__main__')"
            r=subprocess.run([sys.executable,'-I','-c',launcher,folder,str(script),'model','private.json'],capture_output=True,check=True)
            self.assertEqual(json.loads(r.stdout),['model','private.json'])

if __name__=='__main__':unittest.main(verbosity=2)

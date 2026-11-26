"""Offline CPU speed path with bounded accuracy fallback; no native weights required."""
from types import SimpleNamespace
import pytest
from audit_ai.config import Settings
from audit_ai.ocr.paddle_adapter import PaddleOcrAdapter,OcrRuntimeUnavailable

Image=pytest.importorskip('PIL.Image')


def models(root):
    for name in ('PP-OCRv5_mobile_det','PP-OCRv4_mobile_rec'):
        (root/name).mkdir()
        for file in ('inference.json','inference.pdiparams','inference.yml'):(root/name/file).write_text('synthetic')


def test_only_complete_local_models_select_fast_runtime_and_settings_reach_child(tmp_path):
    from audit_ai.ocr.process_adapter import ProcessOcrAdapter
    calls=[]
    class Pipeline:
        def __init__(self,**kwargs):calls.append(kwargs)
        def predict(self,input,**kwargs):return []
    settings=Settings(ocr_orientation_enabled=False,ocr_fast_model_dir=str(tmp_path))
    models(tmp_path)
    module=SimpleNamespace(__version__='3.7.0',PaddleOCR=Pipeline)
    adapter=PaddleOcrAdapter(settings,module_loader=lambda _:module);adapter._get_pipeline()
    assert calls[0]['text_detection_model_dir']==str(tmp_path/'PP-OCRv5_mobile_det')
    assert calls[0]['text_recognition_model_name']=='PP-OCRv4_mobile_rec'
    assert calls[0]['cpu_threads']==1 and calls[0]['enable_mkldnn'] is False
    child=ProcessOcrAdapter(settings)
    assert child._settings.ocr_fast_model_dir==str(tmp_path)
    assert child._settings.ocr_fast_enabled
    child.close()
    calls.clear()
    slow=PaddleOcrAdapter(Settings(ocr_orientation_enabled=False,ocr_fast_model_dir=str(tmp_path),ocr_fast_enabled=False),module_loader=lambda _:module)
    slow._get_pipeline();assert not slow._fast_primary and 'text_detection_model_dir' not in calls[0]


def test_explicit_incomplete_fast_models_do_not_download_or_start_native_runtime(tmp_path):
    calls=[]
    class Pipeline:
        def __init__(self,**kwargs):calls.append(kwargs)
        def predict(self,input,**kwargs):return []
    module=SimpleNamespace(__version__='3.7.0',PaddleOCR=Pipeline)
    with pytest.raises(OcrRuntimeUnavailable,match='offline fast models are incomplete'):
        PaddleOcrAdapter(Settings(ocr_fast_model_dir=str(tmp_path)),module_loader=lambda _:module)._get_pipeline()
    assert not calls


def test_low_confidence_page_is_rechecked_once_by_accuracy_model_without_splicing(tmp_path,monkeypatch):
    from audit_ai.ocr.port import OcrPage
    models(tmp_path);path=tmp_path/'scan.png';Image.new('RGB',(100,150),'white').save(path)
    constructors=[];calls=[]
    class Pipeline:
        def __init__(self,**kwargs):self.fast='text_detection_model_name' in kwargs;constructors.append(self.fast)
        def predict(self,input,**kwargs):return [SimpleNamespace(json={'res':{'page_index':0,
            'rec_texts':['低质量猜测' if self.fast else '本项目预算为2200000人民币。'],'rec_scores':[.75 if self.fast else .99]}})]
    def recognize(image,page,predict,mapper,classifier):
        pages,scores=mapper(predict(image));calls.append(pages[0].text);return OcrPage(page,pages[0].text,pages[0].confidence),scores
    monkeypatch.setattr('audit_ai.ocr.orientation.recognize_page',recognize)
    adapter=PaddleOcrAdapter(Settings(ocr_fast_model_dir=str(tmp_path)),module_loader=lambda _:SimpleNamespace(__version__='3.7.0',PaddleOCR=Pipeline,DocImgOrientationClassification=lambda **_:None))
    first=adapter.extract(path);second=adapter.extract(path)
    assert first.text==second.text=='本项目预算为2200000人民币。'
    assert constructors==[True,False] and calls==['低质量猜测','本项目预算为2200000人民币。']*2


def test_fast_flag_can_be_controlled_without_changing_cpu_safety(monkeypatch):
    monkeypatch.setenv('AUDIT_OCR_FAST_ENABLED','false');assert not Settings.from_env().ocr_fast_enabled
    monkeypatch.setenv('AUDIT_OCR_FAST_ENABLED','invalid')
    with pytest.raises(ValueError):Settings.from_env()


@pytest.mark.parametrize('review_text,review_score',[('',.99),('片段',.99)])
def test_accuracy_review_cannot_replace_a_legible_page_with_blank_or_fragment(tmp_path,monkeypatch,review_text,review_score):
    from audit_ai.ocr.port import OcrPage
    models(tmp_path);path=tmp_path/'scan.png';Image.new('RGB',(100,150),'white').save(path)
    original='本项目预算为2200000人民币，服务内容为档案整理和数字化加工。'
    class Pipeline:
        def __init__(self,**kwargs):self.fast='text_detection_model_name' in kwargs
        def predict(self,input,**kwargs):return [SimpleNamespace(json={'res':{'page_index':0,
            'rec_texts':[original if self.fast else review_text],'rec_scores':[.89 if self.fast else review_score]}})]
    def recognize(image,page,predict,mapper,classifier):
        pages,scores=mapper(predict(image));return OcrPage(page,pages[0].text,pages[0].confidence),scores
    monkeypatch.setattr('audit_ai.ocr.orientation.recognize_page',recognize)
    adapter=PaddleOcrAdapter(Settings(ocr_fast_model_dir=str(tmp_path)),module_loader=lambda _:SimpleNamespace(__version__='3.7.0',PaddleOCR=Pipeline,DocImgOrientationClassification=lambda **_:None))
    assert adapter.extract(path).text==original

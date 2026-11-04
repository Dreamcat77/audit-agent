"""Image invariants are tested independently of installed native OCR weights."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
from types import SimpleNamespace
import threading
import time

import pytest

Image = pytest.importorskip("PIL.Image")
np = pytest.importorskip("numpy")

from audit_ai.config import Settings
from audit_ai.ocr.orientation import page_images, recognize_page, quality
from audit_ai.ocr.paddle_adapter import PaddleOcrAdapter, OcrRuntimeUnavailable
from audit_ai.ocr.port import OcrPage


def mapped(predictions):
    return predictions


def output(text="预算金额 120000.00", confidence=.98):
    return (OcrPage(1, text, confidence),), [confidence]


@pytest.mark.parametrize("rotation", [0, 90, 180, 270])
def test_every_orientation_is_checked_and_only_best_text_is_returned(rotation, monkeypatch, caplog):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    image = Image.new("RGB", (60, 100), "white")
    image.putpixel((0, 0), (0, 0, 0))
    image = image.rotate(rotation, expand=True)
    calls = []
    def predict(array):
        calls.append(array.shape)
        return output() if array.shape[:2] == (100, 60) and array[0, 0, 0] == 0 else output("错误", .2)
    caplog.set_level("INFO")
    page, scores = recognize_page(image, 7, predict, mapped)
    assert page.page_num == 7 and page.text == "预算金额 120000.00" and scores == [.98]
    assert len(calls) == 4
    assert "OCR_ORIENTATION_SELECTED" in caplog.text
    assert "orientation_quality_milli=" in caplog.text
    assert "预算金额" not in caplog.text and "120000" not in caplog.text


def test_close_quality_ties_keep_original_and_never_concatenate_different_amounts(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    calls = iter([output("原文金额10000", .98), output("别向金额90000", .981), output("其他金额80000", .1), output("其他金额70000", .1)])
    page, _ = recognize_page(Image.new("RGB", (40, 80)), 1, lambda image: next(calls), mapped)
    assert page.text == "原文金额10000"


def test_single_logo_does_not_win_against_complete_page():
    assert quality(OcrPage(1, "标志", .99), [.99]) < quality(OcrPage(1, "预算采购申报批复金额来源资料完整可核对", .9), [.9])
    assert quality(OcrPage(1, "预算\n采购", .9), [.9]) > 0
    assert quality(OcrPage(1, "", 0), []) == 0


def test_paddle_receives_bgr_pixels_without_modifying_rgb_source(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    image = Image.new("RGB", (40, 80), (210, 80, 30))
    def predict(array):
        assert tuple(array[0, 0]) == (30, 80, 210)
        return output()
    recognize_page(image, 1, predict, mapped)
    assert image.getpixel((0, 0)) == (210, 80, 30)


@pytest.mark.parametrize("tilt", [-3, 3])
def test_small_deskew_is_selected_only_if_it_improves_recognition(monkeypatch, tilt):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: tilt)
    outputs = iter([output("片段", .7)] * 4 + [output()])
    page, _ = recognize_page(Image.new("RGB", (40, 80)), 1, lambda image: next(outputs), mapped)
    assert page.text == "预算金额 120000.00"
    outputs = iter([output()] * 4 + [output("错误金额", .3)])
    page, _ = recognize_page(Image.new("RGB", (40, 80)), 1, lambda image: next(outputs), mapped)
    assert page.text == "预算金额 120000.00"


def test_transparent_and_exif_images_are_normalized_without_changing_source(tmp_path):
    path = tmp_path / "private.png"
    image = Image.new("RGBA", (40, 80), (0, 0, 0, 0))
    exif = image.getexif(); exif[274] = 6
    image.save(path, exif=exif)
    before = hashlib.sha256(path.read_bytes()).hexdigest()
    for normalized in page_images(path):
        assert normalized.size == (80, 40)
        assert normalized.mode == "RGB" and normalized.getpixel((0, 0)) == (255, 255, 255)
    assert hashlib.sha256(path.read_bytes()).hexdigest() == before


def test_multipage_images_are_read_in_original_order(tmp_path):
    path = tmp_path / "pages.tiff"
    first, second = Image.new("RGB", (40, 80), "white"), Image.new("RGB", (80, 40), "gray")
    first.save(path, save_all=True, append_images=[second])
    assert [image.size for image in page_images(path)] == [(40, 80), (80, 40)]


def test_oversize_page_is_rejected_before_loading_pixels(tmp_path, monkeypatch):
    path = tmp_path / "small.png"; Image.new("RGB", (40, 80)).save(path)
    monkeypatch.setattr("audit_ai.ocr.orientation.MAX_PIXELS", 100)
    with pytest.raises(ValueError, match="pixel limit"):
        list(page_images(path))


def test_invalid_rotation_result_fails_instead_of_publishing_partial_text(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    calls = iter([output(), ((), [])])
    with pytest.raises(ValueError, match="page coverage"):
        recognize_page(Image.new("RGB", (40, 80)), 1, lambda image: next(calls), mapped)


def test_lazy_prediction_mapping_never_runs_concurrently(tmp_path):
    entered = threading.Lock()
    class Lazy:
        @property
        def json(self):
            assert entered.acquire(blocking=False), "concurrent native prediction consumption"
            try:
                time.sleep(.015)
                return {"res": {"rec_texts": ["safe"], "rec_scores": [.9], "page_index": 0}}
            finally:
                entered.release()
    class Pipeline:
        def predict(self, input): return [Lazy()]
    adapter = PaddleOcrAdapter(Settings(ocr_orientation_enabled=False))
    adapter._pipeline = Pipeline()
    with ThreadPoolExecutor(max_workers=4) as pool:
        assert all(x.text == "safe" for x in pool.map(adapter.extract, [tmp_path / "source.png"] * 8))


def test_orientation_configuration_is_explicit_and_passed_to_compute_child(monkeypatch):
    from audit_ai.ocr.process_adapter import ProcessOcrAdapter
    assert Settings().ocr_orientation_enabled
    monkeypatch.setenv("AUDIT_OCR_ORIENTATION_ENABLED", "false")
    settings = Settings.from_env()
    assert not settings.ocr_orientation_enabled
    assert not ProcessOcrAdapter(settings)._settings.ocr_orientation_enabled
    monkeypatch.setenv("AUDIT_OCR_ORIENTATION_ENABLED", "unexpected")
    with pytest.raises(ValueError): Settings.from_env()


def test_direction_probe_is_bounded_but_selected_text_uses_original_pixels(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    shapes=[]
    def predict(array):
        shapes.append(array.shape[:2]); return output()
    result,_=recognize_page(Image.new("RGB", (1654,2200)),1,predict,mapped)
    assert all(max(shape)<=896 for shape in shapes[:4])
    assert shapes[4]==(2200,1654) and len(shapes)==5
    assert result.text=="预算金额 120000.00"


def test_native_predictor_max_side_is_explicit_without_resizing_recognition_pixels():
    calls=[]
    class Pipeline:
        def predict(self,input,*,text_det_limit_side_len,text_det_limit_type):
            calls.append((input,text_det_limit_side_len,text_det_limit_type));return output()
    source=np.zeros((2200,1654,3),dtype=np.uint8)
    PaddleOcrAdapter._bounded_image_predict(Pipeline())(source)
    assert calls[0][0] is source and calls[0][1:]==(1920,"max")
    with pytest.raises(OcrRuntimeUnavailable,match="runtime contract"):
        PaddleOcrAdapter._bounded_image_predict(SimpleNamespace(predict=lambda input: None))


def test_pdf_pages_keep_physical_rotation_and_order(tmp_path):
    pytest.importorskip("pypdfium2")
    path=tmp_path/"scans.pdf"
    first,second=Image.new("RGB",(80,120),"white"),Image.new("RGB",(120,80),"gray")
    first.save(path,save_all=True,append_images=[second],resolution=72)
    shapes=[image.size for image in page_images(path)]
    assert len(shapes)==2 and shapes[0][0]<shapes[0][1] and shapes[1][0]>shapes[1][1]


@pytest.mark.parametrize("rotation", [0,90,180,270])
def test_high_confidence_local_classifier_uses_one_full_ocr_call(monkeypatch,rotation):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    image=Image.new("RGB",(60,100),"white");image.putpixel((0,0),(0,0,0));image=image.rotate(rotation,expand=True)
    class Classifier:
        def predict(self,array):
            return [SimpleNamespace(json={'res':{'label_names':[str((360-rotation)%360)],'scores':[.95]}})]
    calls=[]
    def predict(array):
        calls.append(array)
        assert array.shape[:2]==(100,60) and array[0,0,0]==0
        return output()
    page,_=recognize_page(image,1,predict,mapped,Classifier())
    assert page.text=="预算金额 120000.00" and len(calls)==1


def test_uncertain_classifier_falls_back_to_bounded_direction_probes(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    class Classifier:
        def predict(self,array):return [SimpleNamespace(json={'res':{'label_names':['90'],'scores':[.52]}})]
    calls=[]
    def predict(array):calls.append(array);return output()
    assert recognize_page(Image.new("RGB",(60,100)),1,predict,mapped,Classifier())[0].text=="预算金额 120000.00"
    assert len(calls)==4


def test_confident_short_title_is_kept_without_four_more_predictions(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    classifier=SimpleNamespace(predict=lambda image:[SimpleNamespace(json={'res':{'label_names':['0'],'scores':[.99]}})])
    calls=[]
    def predict(image):calls.append(image.shape);return output("合同书",.99)
    page,_=recognize_page(Image.new("RGB",(1600,2200)),1,predict,mapped,classifier)
    assert page.text=="合同书" and len(calls)==1


def test_weak_direction_probes_do_not_replace_full_page_evidence_or_repeat_same_angle(monkeypatch):
    monkeypatch.setattr("audit_ai.ocr.orientation.deskew_angle", lambda image: 0)
    classifier=SimpleNamespace(predict=lambda image:[SimpleNamespace(json={'res':{'label_names':['0'],'scores':[.99]}})])
    calls=[]
    def predict(image):
        calls.append(image.shape)
        return output("完整正文及预算人民币贰佰贰拾万元",.75) if max(image.shape)>896 else output("片段",.55)
    page,_=recognize_page(Image.new("RGB",(1600,2200)),1,predict,mapped,classifier)
    assert page.text=="完整正文及预算人民币贰佰贰拾万元"
    assert len(calls)==5 and sum(max(shape)>896 for shape in calls)==1


def test_invalid_classifier_output_cannot_publish_partial_result():
    class Classifier:
        def predict(self,array):return [SimpleNamespace(json={'res':{'label_names':['45'],'scores':[.99]}})]
    with pytest.raises(ValueError,match="classification result invalid"):
        recognize_page(Image.new("RGB",(60,100)),1,lambda array:output(),mapped,Classifier())


def test_explicit_missing_model_directory_fails_closed_without_download(tmp_path):
    class Pipeline:
        def __init__(self,**kwargs):pass
        def predict(self,input,**kwargs):return []
    module=SimpleNamespace(__version__='3.7.0',PaddleOCR=Pipeline)
    adapter=PaddleOcrAdapter(Settings(ocr_orientation_model_dir=str(tmp_path/'missing')),module_loader=lambda _:module)
    with pytest.raises(OcrRuntimeUnavailable,match="incomplete"):
        adapter._get_pipeline()

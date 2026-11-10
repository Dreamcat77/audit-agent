import json
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion
from audit_ai.model.material_chunks import bounded_chunks, split_chunk
from audit_ai.model.material_fields import Segment, DocumentContext, text_parts
from audit_ai.model.procurement_context import contextual_renderer


def test_background_and_section_are_bounded_and_do_not_drop_source():
    settings = Settings(material_chunk_chars=512, material_chunk_bytes=1024)
    segments = [Segment(locator='p1', text='第三章 类似项目业绩\n'+'历史例子。'*350),
                Segment(locator='p2', text='历史合同：甲。\n第五章 报价一览表\n投标总价100元。')]
    render = contextual_renderer(segments, DocumentContext(header='封面'*400), settings)
    chunks = bounded_chunks(segments, settings, text_parts, render)
    assert len(chunks) > 3
    for chunk in chunks:
        body=render(chunk, settings.material_context_chars)
        assert len(body)<=512 and len(body.encode('utf-8'))<=1024
    for segment in segments:
        assert ''.join(piece.text for chunk in chunks for piece in chunk if piece.locator==segment.locator)==segment.text
    early = json.loads(render(chunks[1],settings.material_context_chars))[0]
    assert '业绩' in early['background']['section']
    last = json.loads(render(chunks[-1],settings.material_context_chars))
    # A heading inside the core is visible verbatim; subsequent pieces carry it.
    assert any('报价一览表' in view['text'] or '报价一览表' in view.get('background',{}).get('section','') for view in last)
    for sub in split_chunk(chunks[0]) or []:
        assert len(render(sub, settings.material_context_chars))<=512


def test_model_background_cannot_become_evidence_and_scopes_survive_checkpoint(tmp_path, caplog):
    calls=[]
    class Gateway:
        def extract_material_fields(self, kind, source, request_id, **kwargs):
            calls.append(source)
            views=json.loads(source)
            assert views[0]['background']['header']=='采购人：背景学校'
            return ModelCompletion(payload={'rows':[{'rowKey':'meta','facts':[
                {'field':'purchaserName','value':'背景学校','locator':'p1','excerpt':'采购人：背景学校','subjectScope':'CURRENT_PROJECT'},
                {'field':'budgetAmount','value':'100元','locator':'p1','excerpt':'预算100元','subjectScope':'CURRENT_PROJECT','amountScope':'TOTAL'}]}]}, model='TEST', duration_ms=1)
    body={'businessType':'PROCUREMENT_AUTO','materialId':'synthetic','sha256':'a'*64,
          'context':{'header':'采购人：背景学校','section':'采购需求'},'segments':[{'locator':'p1','text':'预算100元'}]}
    with TestClient(create_app(Settings(material_checkpoint_root=str(tmp_path)), model_gateway=Gateway())) as client:
        first=client.post('/ai/v1/materials/extract-fields',json=body)
        second=client.post('/ai/v1/materials/extract-fields',json=body)
    assert first.status_code==second.status_code==200
    assert len(calls)==1
    facts=second.json()['data']['rows'][0]['facts']
    assert len(facts)==1 and facts[0]['field']=='budgetAmount'
    assert facts[0]['subjectScope']=='CURRENT_PROJECT' and facts[0]['amountScope']=='TOTAL'
    assert '背景学校' not in caplog.text and '预算100元' not in caplog.text

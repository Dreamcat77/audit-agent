"""Selective recovery of two scanned sheets pasted onto one physical page."""
from dataclasses import replace
import logging
from audit_ai.logging import log_event
from audit_ai.ocr.orientation import quality, recognize_page
from audit_ai.ocr.port import OcrPage


def split_boxes(image):
    """Require a wide paper gutter and significant ink on both sides."""
    import numpy as np
    small = image.convert('L')
    try:
        small.thumbnail((800, 800))
        ink = np.asarray(small) < 190
        candidates = []
        for axis in (0, 1):
            density = ink.mean(axis=1 if axis == 0 else 0)
            n = len(density)
            if n < 100: continue
            start = None
            for i in range(int(n*.25), int(n*.75)):
                if density[i] < .004:
                    if start is None: start = i
                elif start is not None:
                    end = i
                    if end-start >= max(6, n*.025) and density[:start].mean() > .025 and density[end:].mean() > .025:
                        candidates.append((end-start, axis, (start+end)/2/n))
                    start = None
        if not candidates: return []
        _, axis, fraction = max(candidates)
        w, h = image.size
        if axis == 0:
            at = round(h*fraction)
            return [(0,0,w,at), (0,at,w,h)]
        at = round(w*fraction)
        return [(0,0,at,h), (at,0,w,h)]
    finally:
        small.close()


def recover_compound(image, page, scores, predict, mapper, classifier=None):
    boxes = split_boxes(image)
    if not boxes: return page, scores
    covered = []
    for x0,y0,x1,y1 in boxes:
        covered.append(sum(r.end-r.start for r in page.regions
            if x0 <= sum(x for x,_ in r.polygon)/4*image.width < x1
            and y0 <= sum(y for _,y in r.polygon)/4*image.height < y1))
    if quality(page,scores) >= 100 and page.confidence >= .9 and all(c >= 30 for c in covered):
        return page, scores
    recovered = []
    for box in boxes:
        from audit_ai.ocr.cancellation import check_cancelled
        check_cancelled()
        with image.crop(box) as crop:
            result, confidence = recognize_page(crop,page.page_num,predict,mapper,classifier)
            if quality(result,confidence) < 12: return page,scores
            recovered.append((box,result,confidence))
    new_quality = sum(quality(p,s) for _,p,s in recovered)
    if new_quality <= max(30,quality(page,scores)*1.2): return page,scores
    text = ''; regions = []; confidence = []
    for (x0,y0,x1,y1), result, values in recovered:
        if text: text += '\n'
        offset = len(text); text += result.text; confidence.extend(values)
        for r in result.regions:
            polygon=tuple(((x0+x*(x1-x0))/image.width,(y0+y*(y1-y0))/image.height) for x,y in r.polygon)
            regions.append(replace(r,start=r.start+offset,end=r.end+offset,polygon=polygon))
    log_event(logging.getLogger(__name__),'OCR_COMPOUND_PAGE_RECOVERED',stage='OCR',
        page_count=page.page_num,item_count=len(boxes),input_chars=len(page.text),compact_chars=len(text))
    return OcrPage(page.page_num,text,sum(confidence)/len(confidence) if confidence else 0,
                   tuple(regions),image.width,image.height),confidence

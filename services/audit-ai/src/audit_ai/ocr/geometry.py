"""OCR spans and reversible pixel coordinates; never inferred from model text."""
from dataclasses import dataclass, replace
import math

@dataclass(frozen=True, slots=True)
class OcrRegion:
    start: int
    end: int
    polygon: tuple[tuple[float, float], ...]
    confidence: float
    verification: str = "NOT_RECHECKED"

    def __post_init__(self):
        if (isinstance(self.start, bool) or not isinstance(self.start, int) or self.start < 0
                or not isinstance(self.end, int) or self.end <= self.start
                or not 0 <= self.confidence <= 1 or len(self.polygon) != 4):
            raise ValueError("OCR region invalid")
        points = tuple(tuple(float(v) for v in p) for p in self.polygon)
        if any(len(p) != 2 or any(not math.isfinite(v) or abs(v) > 100000 for v in p) for p in points):
            raise ValueError("OCR polygon invalid")
        object.__setattr__(self, "polygon", points)
        if self.verification not in {"NOT_RECHECKED", "MATCHED", "UNCERTAIN"}:
            raise ValueError("OCR verification invalid")

def restore_regions(page, original_size, rotations):
    """Undo PIL rotate(expand=True), in reverse order; normalized original pixels."""
    regions = []
    w, h = original_size
    for region in page.regions:
        points = region.polygon
        for angle, before, after in reversed(rotations):
            a = math.radians(angle); c, s = math.cos(a), math.sin(a)
            bw, bh = before; aw, ah = after
            points = tuple((c*(x-aw/2)-s*(y-ah/2)+bw/2,
                            s*(x-aw/2)+c*(y-ah/2)+bh/2) for x,y in points)
        if any(x < -2 or y < -2 or x > w+2 or y > h+2 for x,y in points):
            continue
        points = tuple((max(0.,min(1.,x/w)),max(0.,min(1.,y/h))) for x,y in points)
        regions.append(replace(region, polygon=points))
    return replace(page, regions=tuple(regions), width=w, height=h)


def recheck_critical_regions(page, image, recognize, limit=2):
    """Bounded independent crops; disagreeing reads stay uncertain, never silently replace frozen text."""
    import re
    import unicodedata
    regions=list(page.regions)
    candidates=[(i,r) for i,r in enumerate(regions) if r.confidence < .92
                and re.search(r"金额|价款|人民币|[元圆年月]|大写",page.text[r.start:r.end])]
    for i,region in sorted(candidates,key=lambda entry:entry[1].confidence)[:limit]:
        xs=[p[0]*image.width for p in region.polygon];ys=[p[1]*image.height for p in region.polygon]
        box=(max(0,int(min(xs))-6),max(0,int(min(ys))-6),min(image.width,math.ceil(max(xs))+6),min(image.height,math.ceil(max(ys))+6))
        if box[2]<=box[0] or box[3]<=box[1]:continue
        try:
            with image.crop(box) as crop:
                scale=min(2.,(2_000_000/(crop.width*crop.height))**.5)
                with crop.resize((max(1,round(crop.width*scale)),max(1,round(crop.height*scale)))) as enlarged:
                    reviewed=recognize(enlarged)
        except Exception:
            regions[i]=replace(region,verification="UNCERTAIN")
            continue
        def normalized(text):
            return re.sub(r"[\s，,。.;；:：()（）]","",unicodedata.normalize('NFKC',text))
        original=normalized(page.text[region.start:region.end]);second=normalized(reviewed.text)
        regions[i]=replace(region,verification="MATCHED" if original and original==second and reviewed.confidence>=.85 else "UNCERTAIN")
    return replace(page,regions=tuple(regions))

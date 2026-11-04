"""Lossless material spans with bounded, non-authoritative boundary context."""
from dataclasses import dataclass, field
import json


@dataclass(frozen=True)
class SourcePiece:
    locator: str
    source: str = field(repr=False)
    start: int = 0
    end: int | None = None

    @property
    def text(self):
        return self.source[self.start:self.end]

    def view(self, context_chars):
        end = len(self.source) if self.end is None else self.end
        # Context shrinks with the core, so automatic retries can actually fit.
        context = min(context_chars, len(self.text)//4)
        result = {"locator": self.locator, "text": self.text}
        if self.start and self.source[self.start-1] != '\n' and context:
            result['contextBefore'] = self.source[max(0,self.start-context):self.start]
        if end < len(self.source) and self.source[end-1] != '\n' and context:
            result['contextAfter'] = self.source[end:end+context]
        return result


def render(chunk, context_chars):
    return json.dumps([p.view(context_chars) for p in chunk], ensure_ascii=False)


def split_piece(piece):
    if len(piece.text) < 128:
        return None
    middle = len(piece.text)//2
    # Prefer complete rows/sentences. A very long row still has overlapping
    # context rather than silently losing its tail or monetary label.
    cut = max(piece.text.rfind(c, middle//2, middle+1) for c in '\n。；;')
    cut = cut+1 if cut >= middle//2 else middle
    absolute = piece.start+cut
    return [SourcePiece(piece.locator,piece.source,piece.start,absolute),
            SourcePiece(piece.locator,piece.source,absolute,piece.end)]


def split_chunk(chunk):
    if len(chunk)>1:
        middle=len(chunk)//2
        return [chunk[:middle],chunk[middle:]]
    pieces=split_piece(chunk[0])
    return [[p] for p in pieces] if pieces else None


def bounded_chunks(segments, settings, text_parts):
    pieces=[]
    for segment in segments:
        start=0
        for part in text_parts(segment.text):
            pieces.append(SourcePiece(segment.locator,segment.text,start,start+len(part)))
            start+=len(part)
    queue=list(pieces);fitted=[]
    while queue:
        piece=queue.pop(0)
        source=render([piece],settings.material_context_chars)
        if len(source)>settings.material_chunk_chars or len(source.encode('utf-8'))>settings.material_chunk_bytes:
            split=split_piece(piece)
            if split is None:
                raise ValueError('material source metadata cannot fit configured bounds')
            queue[:0]=split
        else:
            fitted.append(piece)
    chunks=[];current=[]
    for piece in fitted:
        source=render([*current,piece],settings.material_context_chars)
        if current and (len(current)>=6 or any(p.locator==piece.locator for p in current)
                        or len(source)>settings.material_chunk_chars
                        or len(source.encode('utf-8'))>settings.material_chunk_bytes):
            chunks.append(current);current=[]
        current.append(piece)
    if current:chunks.append(current)
    return chunks

"""Bounded document/section background; background is never citation evidence."""
import json
import re

_HEADING = re.compile(r'^(?:第[一二三四五六七八九十百0-9]+[章部分节].*|.{0,30}(?:业绩|历史合同|财务报表|人员履历|报价一览表|分项报价表|投标函|采购需求|评审办法|评分办法))$')


def contextual_renderer(segments, context, settings):
    # Keep offsets, including line separators, so a long segment split into
    # several calls carries its actual section rather than the next heading.
    section = context.section if context else ''
    entries = {}
    for segment in segments:
        positions = [(0, section)]
        offset = 0
        for line in segment.text.splitlines(keepends=True):
            title = line.strip()
            if (len(title) <= 100 and _HEADING.fullmatch(title)
                    and not re.search(r'[.．…·]{2,}\s*\d+\s*$', title)):
                section = title
                positions.append((offset, section))
            offset += len(line)
        entries[segment.locator] = positions
    header = context.header if context else '\n'.join(s.text for s in segments[:2])[:800]
    # Reserve most of even the smallest configured request for actual text.
    cap = min(960, settings.material_chunk_chars // 3, settings.material_chunk_bytes // 12)

    def render(chunk, context_chars):
        views = [piece.view(context_chars) for piece in chunk]
        piece = chunk[0]
        current = ''
        for offset, title in entries.get(piece.locator, []):
            if offset > piece.start:
                break
            current = title
        if cap >= 32:
            section_cap = min(120, cap//2)
            views[0]['background'] = {'header': header[:cap-section_cap], 'section': current[:section_cap]}
        return json.dumps(views, ensure_ascii=False)
    return render

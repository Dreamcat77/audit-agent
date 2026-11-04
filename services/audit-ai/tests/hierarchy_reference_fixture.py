"""Offline fixture adapter only; production always decodes the actual provider wire.

Keep existing adversarial canonical providers unchanged, converting their final
mutated response to the new strict transport before the real service sees it.
This is NOT an acceptance parser and must never be used by production.
"""
from dataclasses import replace
import copy
import json

PROTOCOL = 'contract-hierarchy-provider-references-v1'


def encode_fixture(payload, op):
    from test_semantic_hierarchy_execution import decode_claim
    source = json.loads(op.source_text)
    originals = []
    if op.hierarchy_role == 'GROUP':
        for analysis in source['analyses']:
            originals.extend((leaf[0], decode_claim(leaf[1:], source)) for leaf in analysis['leaves'])
        ids = {r[1]: r[0] for r in source['evidence']}
    else:
        for gi, group in enumerate(source['groups']):
            originals.extend((f'G{gi+1}:H{hi+1}', decode_claim(row, source)) for hi, row in enumerate(group['claims']))
        ids = {r[0]: i for i, r in enumerate(source['evidence'])}
    targets, new = [], []
    for i, hint in enumerate(payload['hints']):
        matched = (originals[i][0] if i < len(originals) and originals[i][1] == hint else
                   next((ref for ref, original in originals if original == hint), None))
        if matched is None:
            matched = f'N{len(new)}'; new.append(copy.deepcopy(hint))
        targets.append(matched)
    def indexes(values): return [ids.get(eid, 999999) for eid in values]
    wire = {'protocol': PROTOCOL, 'summary': payload['summary'], 'newHints': new}
    if op.hierarchy_role == 'GROUP':
        wire['lineage'] = [[row[0], row[1], targets[row[2]], indexes(row[3]), row[4]]
                           if type(row) is list and len(row) == 5 and type(row[2]) is int
                           and 0 <= row[2] < len(targets) else copy.deepcopy(row)
                           for row in payload['lineage']]
    else:
        wire['resolutions'] = [[*row['observation'], row['disposition'],
            [targets[i] for i in row['finalHintIndexes']], indexes(row['basisEvidenceIds']), row['reason']]
            for row in payload['resolutions']]
    return wire


class ReferenceFixtureGateway:
    def __init__(self, delegate): self.delegate = delegate
    def analyze_contract(self, op):
        completion = self.delegate.analyze_contract(op)
        if op.hierarchy_role:
            return replace(completion, payload=encode_fixture(completion.payload, op))
        return completion

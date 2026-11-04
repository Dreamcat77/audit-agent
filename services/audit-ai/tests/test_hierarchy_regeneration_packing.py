"""Synthetic pure codec tests: no provider, repository or private material."""
import copy
import importlib.util
import json
import hashlib
import inspect
from pathlib import Path

import pytest

from audit_ai.model import semantic_regeneration_packing as C
VERSION = 'contract-regeneration-hierarchy-columns-v1'


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def independent_inverse(encoding, value):
    if encoding == 'FULL':
        return copy.deepcopy(value)
    result = copy.deepcopy(value['root'])
    for name, table in value['tables'].items():
        rows = []
        for row in table['rows']:
            restored = {}
            for key, cell in zip(table['columns'], row, strict=True):
                pool = value.get('pools', {}).get(key)
                if pool is not None:
                    if isinstance(pool, dict):
                        cell = dict(zip(pool['columns'], pool['rows'][cell], strict=True))
                    else:
                        cell = copy.deepcopy(pool[cell])
                restored[key] = cell
            rows.append(restored)
        result[name] = rows
    return result


def dense():
    return {'summary': 'Synthetic retained summary.', 'hints': [
        {'category': 'CONTRACT_STRUCTURE', 'attention': 'REVIEW',
         'title': 'Synthetic obligation ' + str(i),
         'description': 'Retain this distinct observation and its supporting evidence. ' + str(i),
         'problemEvidenceIds': ['evidence:immutable:1', 'evidence:immutable:2'],
         'comparisonEvidenceIds': ['evidence:immutable:2'], 'unavailableMaterialCategory': None,
         'claimKind': 'AMBIGUOUS_CLAUSE',
         'obligationScope': {'topic': 'WARRANTY' if i % 2 else 'SERVICE_RESPONSE', 'responsibleParty': 'BOTH', 'trigger': None}}
        for i in range(30)], 'resolutions': [
        {'observation': [i // 5, i % 5], 'disposition': 'RETAINED', 'finalHintIndexes': [i % 30],
         'basisEvidenceIds': ['evidence:immutable:1', 'evidence:immutable:2'],
         'reason': 'Retain source observation ' + str(i)} for i in range(39)]}


def pack(value):
    assert hasattr(C, 'pack_hierarchy_candidate'), 'explicit hierarchy metadata codec is absent'
    return C.pack_hierarchy_candidate(value)


def test_dense_metadata_saves_space_without_losing_candidate_or_mutating_default():
    original = dense(); before = copy.deepcopy(original)
    old = C.pack_candidate(original)
    assert len(canonical(old[1])) > 8192
    encoding, payload = pack(original)
    assert encoding == VERSION
    assert len(canonical(payload)) < 8192
    assert canonical(independent_inverse(encoding, payload)) == canonical(before)
    assert canonical(C.unpack_candidate(encoding, payload)) == canonical(before)
    assert original == before and C.pack_candidate(original) == old


@pytest.mark.parametrize('mutation', ['unknown', 'missing', 'null', 'invalid', 'scope_unknown', 'scope_heterogeneous', 'unicode', 'order', 'typed', 'empty'])
def test_all_untrusted_values_presence_types_and_order_are_preserved(mutation):
    value = dense()
    if mutation == 'unknown': value['hints'][0]['PRIVATE_UNKNOWN'] = {'x': [None, False, 1, 1.0]}
    if mutation == 'missing': value['hints'][0].pop('obligationScope')
    if mutation == 'null': value['hints'][0]['obligationScope'] = None
    if mutation == 'invalid': value['hints'][0]['category'] = {'untrusted': 'PRIVATE_CANARY'}
    if mutation == 'scope_unknown': value['hints'][0]['obligationScope']['PRIVATE_SCOPE'] = '保留😀'
    if mutation == 'scope_heterogeneous': value['hints'][0]['obligationScope'] = ['not', 'a', 'scope']
    if mutation == 'unicode': value['hints'][0]['description'] = '中文😀逐字保留'
    if mutation == 'order': value['hints'].reverse(); value['resolutions'].reverse()
    if mutation == 'typed':
        for i, x in enumerate([False, 0, 0.0, None, [], {}, '0']): value['hints'][i]['attention'] = x
    if mutation == 'empty':
        for hint in value['hints']: hint['obligationScope'] = {}
    before = canonical(value)
    encoding, payload = pack(value)
    assert canonical(independent_inverse(encoding, payload)) == before
    assert canonical(C.unpack_candidate(encoding, payload)) == before
    assert canonical(value) == before


@pytest.mark.parametrize('defect', ['duplicate_pool', 'index', 'bool_index', 'negative', 'unknown_pool', 'unknown_column', 'duplicate_column', 'row_width', 'root_overlap', 'scope_column', 'scope_duplicate', 'unused_pool'])
def test_malformed_metadata_fails_closed_without_private_error_text(defect):
    encoding, payload = pack(dense())
    table = payload['tables']['hints']; col = table['columns'].index('category')
    if defect == 'duplicate_pool': payload['pools']['category'].append(payload['pools']['category'][0])
    if defect == 'index': table['rows'][0][col] = 100000
    if defect == 'bool_index': table['rows'][0][col] = True
    if defect == 'negative': table['rows'][0][col] = -1
    if defect == 'unknown_pool': payload['pools']['PRIVATE_CANARY'] = ['x']
    if defect == 'unknown_column': table['columns'][0] = 'PRIVATE_CANARY'
    if defect == 'duplicate_column': table['columns'][0] = table['columns'][1]
    if defect == 'row_width': table['rows'][0].append('PRIVATE_CANARY')
    if defect == 'root_overlap': payload['root']['hints'] = 'PRIVATE_CANARY'
    if defect == 'scope_column': payload['pools']['obligationScope']['columns'][0] = 'PRIVATE_CANARY'
    if defect == 'scope_duplicate': payload['pools']['obligationScope']['columns'][0] = payload['pools']['obligationScope']['columns'][1]
    if defect == 'unused_pool': payload['pools']['claimKind'].append('PRIVATE_CANARY')
    with pytest.raises(ValueError) as error: C.unpack_candidate(encoding, payload)
    assert 'PRIVATE_CANARY' not in str(error.value)


@pytest.mark.parametrize('size', [8192, 8193])
def test_exact_wire_size_is_not_truncated_or_claimed_to_fit(size):
    # Codec does not own the envelope cap. It must return the complete value.
    value = {'summary': ''}
    value['summary'] = 'x' * (size - len(canonical(value)))
    encoding, payload = pack(value)
    assert encoding == 'FULL' and len(canonical(payload)) == size
    assert C.unpack_candidate(encoding, payload) == value


def test_irreducible_prose_and_invalid_utf8_are_not_compressed_or_replaced(capsys):
    value = dense(); value['summary'] = 'PRIVATE_CANARY😀' * 2000
    encoding, payload = pack(value)
    assert len(canonical(payload)) > 8192
    assert canonical(independent_inverse(encoding, payload)) == canonical(value)
    with pytest.raises(UnicodeEncodeError): pack({'summary': '\ud800'})
    assert capsys.readouterr().out == ''


def test_default_codec_bytes_match_unchanged_production_codec():
    # Pinned from the deployed 5db implementation, not compared with itself.
    assert hashlib.sha256(inspect.getsource(C.pack_candidate).encode()).hexdigest() == (
        '85571ca4902daf0961c530ff40967dc352caf0fee006d33c157429a567beb0b2')

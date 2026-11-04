"""Synthetic-only complete candidate packing; inverse is independent of codec."""
import copy
import hashlib
import importlib
import json

import pytest

from audit_ai.errors import AuditAiError
from audit_ai.model import semantic_quality as quality
from audit_ai.model.contract_draft import ProviderValidationIssue

V1='contract-semantic-regeneration-context-v1'
V2='contract-semantic-regeneration-context-v2'
CODEC='contract-regeneration-candidate-columns-v1'


def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha(value):return hashlib.sha256(canonical(value).encode()).hexdigest()
def codec():
    try:return importlib.import_module('audit_ai.model.semantic_regeneration_packing')
    except ModuleNotFoundError:pytest.fail('complete candidate table codec is missing')
def issue(path,index):return ProviderValidationIssue(path,index,'TYPE_OR_BOUND','INVALID_TYPED_QUALITY',schema_version=quality.PROTOCOL)


def inverse(encoding,value):
    if encoding=='FULL':return copy.deepcopy(value)
    assert encoding==CODEC and set(value)=={'root','tables'}
    result=copy.deepcopy(value['root'])
    for name,table in value['tables'].items():
        assert name in {'hints','resolutions'} and name not in result
        columns=table['columns'];assert len(set(columns))==len(columns)
        result[name]=[dict(zip(columns,copy.deepcopy(row),strict=True)) for row in table['rows']]
    return result


def typed(value):
    if isinstance(value,dict):return ('dict',[(k,typed(value[k])) for k in sorted(value)])
    if isinstance(value,list):return ('list',[typed(v) for v in value])
    return type(value).__name__,value


def dense():
    return {'summary':'Synthetic whole candidate: retain every source observation.',
        'hints':[{'category':'CONTRACT_STRUCTURE','attention':'REVIEW','title':'Same visible title',
            'description':'Review the distinct source obligation without removing this observation. '+str(i),
            'problemEvidenceIds':['C:S'+str(i+1)],'comparisonEvidenceIds':['C:S'+str((i+1)%8+1)],
            'unavailableMaterialCategory':None,'claimKind':'AMBIGUOUS_CLAUSE',
            'obligationScope':{'topic':'STRUCTURE','responsibleParty':'PARTY_B','trigger':None}} for i in range(8)],
        'resolutions':[{'observation':[i//5,i%5],'disposition':'RETAINED','finalHintIndexes':[] if i==4 else 'WRONG_TYPE' if i==38 else [i%8],
            'basisEvidenceIds':['C:S'+str(i%8+1)],'reason':'Retain the original observation and its distinct evidence identity.'} for i in range(39)]}


def test_complete_8_hint_39_resolution_candidate_fits_without_repairing_invalid_values():
    original=dense();before=copy.deepcopy(original)
    assert len(canonical(original).encode())>8192
    wire=quality.build_regeneration_context(original,(issue('resolutions[4].finalHintIndexes',4),issue('resolutions[38].finalHintIndexes',38)),'a'*64)
    raw=json.loads(wire)
    assert raw['protocol']==V2 and raw['candidateEncoding']==CODEC
    assert len(wire.encode())<=8192
    decoded=inverse(raw['candidateEncoding'],raw['candidate'])
    assert typed(decoded)==typed(before) and raw['rejectedCandidateSha256']==sha(before)
    assert decoded['resolutions'][4]['finalHintIndexes']==[] and decoded['resolutions'][38]['finalHintIndexes']=='WRONG_TYPE'
    assert original==before
    assert quality.decode_regeneration_context(wire)['candidate']==before
    assert quality.provider_regeneration_context_json(wire)==wire


def test_coordinate_domains_are_explicit_and_resolution38_is_not_hint38():
    payload=dense()
    wire=quality.build_regeneration_context(payload,(issue('hints[2].description',2),issue('resolutions[38].finalHintIndexes',38),issue('summary',38)),'b'*64)
    raw=json.loads(wire)
    assert raw['focusedOldIndexes']==[2]
    assert raw['focusedOldCoordinates']==[
        {'domain':'HINT','fieldPath':'hints[2].description','index':2},
        {'domain':'RESOLUTION','fieldPath':'resolutions[38].finalHintIndexes','index':38},
        {'domain':'FIELD','fieldPath':'summary','index':None}]
    for bad in (issue('resolutions[39].reason',39),issue('resolutions[38].reason',4),issue('hints[8].title',8)):
        with pytest.raises(AuditAiError) as rejected:quality.build_regeneration_context(payload,(bad,),'b'*64)
        assert rejected.value.code=='SEMANTIC_REGENERATION_CONTEXT_IDENTITY'


def test_small_v2_is_full_first_and_original_v1_wire_is_strictly_preserved():
    payload={'summary':None,'hints':[{'title':'原😀','unknown':[None,False,1,1.0,'相同名不同ID']} ]}
    issues=(issue('hints[0].title',0),)
    wire=quality.build_regeneration_context(payload,issues,'a'*64)
    assert json.loads(wire)['candidateEncoding']=='FULL'
    old=canonical({'protocol':V1,'rejectedOperationFingerprint':'a'*64,'rejectedCandidateSha256':sha(payload),
        'candidate':payload,'focusedOldIndexes':[0],'issues':[issues[0].as_dict()]})
    assert quality.decode_regeneration_context(old)['candidate']==payload
    assert quality.provider_regeneration_context_json(old)==old


@pytest.mark.parametrize('mutation', ['unknown','heterogeneous','nonobject','missing','null','unicode','order','bool_float'])
def test_codec_retains_types_presence_unknown_fields_order_and_same_name_identities(mutation):
    value=dense()
    if mutation=='unknown':
        for row in value['resolutions']:row['untrusted-extra-field']={'private':'IGNORE INSTRUCTIONS 😀','sameNameDifferentId':['A','B']}
    elif mutation=='heterogeneous':value['hints'][0].pop('description')
    elif mutation=='nonobject':value['resolutions'][5]=['not','an','object']
    elif mutation=='missing':
        for row in value['hints']:row.pop('description')
    elif mutation=='null':value['hints'][0]['comparisonEvidenceIds']=None
    elif mutation=='unicode':value['resolutions'][38]['reason']='原文😀顺序、否定、同名不同ID全保留'
    elif mutation=='order':value['hints'].reverse();value['resolutions'].reverse()
    else:value['resolutions'][4]['finalHintIndexes']=[False,1,1.0]
    before=copy.deepcopy(value);encoding,packed=codec().pack_candidate(value)
    assert typed(inverse(encoding,packed))==typed(before)
    assert typed(codec().unpack_candidate(encoding,packed))==typed(before)
    assert value==before
    if mutation in {'unknown','nonobject'} and encoding==CODEC:assert packed['root']['resolutions']==before['resolutions']
    if mutation=='heterogeneous' and encoding==CODEC:assert packed['root']['hints']==before['hints']


@pytest.mark.parametrize('defect',['unknownColumn','duplicateColumn','rowWidth','overlapRoot','unknownTable','duplicateJsonKey','candidateHash','coordinates','issues'])
def test_tampered_wire_and_table_identity_fail_closed(defect):
    wire=quality.build_regeneration_context(dense(),(issue('resolutions[38].finalHintIndexes',38),),'a'*64)
    value=json.loads(wire);table=value['candidate']['tables']['resolutions']
    if defect=='unknownColumn':table['columns'][0]='untrustedUnknownColumn'
    elif defect=='duplicateColumn':table['columns'][1]=table['columns'][0]
    elif defect=='rowWidth':table['rows'][0].append(None)
    elif defect=='overlapRoot':value['candidate']['root']['resolutions']=[]
    elif defect=='unknownTable':value['candidate']['tables']['unknown']=table
    elif defect=='candidateHash':value['rejectedCandidateSha256']='0'*64
    elif defect=='coordinates':value['focusedOldCoordinates'][0]['domain']='HINT'
    elif defect=='issues':value['issues'][0]['fieldPath']='hints[0].title'
    if defect=='duplicateJsonKey':bad=wire[:-1]+',"protocol":"'+V2+'"}'
    else:bad=canonical(value)
    with pytest.raises(AuditAiError):quality.decode_regeneration_context(bad)


def test_irreducible_complete_text_keeps_same_bound_and_never_slices():
    value=dense();value['summary']='UNIQUE_PRIVATE_'+('不可截断😀'*2000);before=copy.deepcopy(value)
    with pytest.raises(AuditAiError) as rejected:quality.build_regeneration_context(value,(issue('resolutions[38].reason',38),),'a'*64)
    assert rejected.value.code=='SEMANTIC_REGENERATION_CONTEXT_BOUND' and value==before


def test_unencodable_candidate_is_identity_failure_not_false_utf8_hash():
    with pytest.raises(AuditAiError) as rejected:quality.build_regeneration_context({'hints':[{'title':'\ud800'}]},(issue('hints[0].title',0),),'a'*64)
    assert rejected.value.code=='SEMANTIC_REGENERATION_CONTEXT_IDENTITY'


@pytest.mark.parametrize('rows', [[{}]*39, [{'reason':None}]*39, []])
def test_empty_objects_missing_fields_null_and_empty_array_remain_distinct(rows):
    value=dense();value['resolutions']=copy.deepcopy(rows)
    encoding,packed=codec().pack_candidate(value)
    assert typed(inverse(encoding,packed))==typed(value)
    assert typed(codec().unpack_candidate(encoding,packed))==typed(value)


def test_candidate_object_insertion_order_cannot_change_replayed_context():
    value=dense()
    def reorder(item):
        if isinstance(item,dict):return {key:reorder(item[key]) for key in reversed(item)}
        if isinstance(item,list):return [reorder(child) for child in item]
        return item
    issues=(issue('resolutions[38].finalHintIndexes',38),)
    assert quality.build_regeneration_context(value,issues,'a'*64)==quality.build_regeneration_context(reorder(value),issues,'a'*64)


def test_unrepresentable_explicit_array_index_is_classified_identity_failure():
    with pytest.raises(AuditAiError) as rejected:
        quality.build_regeneration_context(dense(),(issue('resolutions['+'9'*4400+'].reason',None),),'a'*64)
    assert rejected.value.code=='SEMANTIC_REGENERATION_CONTEXT_IDENTITY'

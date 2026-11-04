"""Independent assertions for synthetic golden cases; never treats model text as ground truth."""
import re
import hashlib
from pathlib import Path
from decimal import Decimal


def amount(value):
    match=re.fullmatch(r"(?:人民币|RMB|CNY|[￥¥])?\s*([\d,]+(?:\.\d+)?)\s*(万元|万|元)?",value.strip())
    if not match:return None
    return Decimal(match[1].replace(",",""))*(10000 if match[2] in {"万元","万"} else 1)


def verify(spec,case,report,texts):
    failures=[]
    def require(value,code):
        if not value:failures.append(code)
    docs=report["documents"]
    require(report["id"]==case["id"],"REPORT_CASE_ID_MISMATCH")
    require(case["status"] not in {"QUEUED","RUNNING"},"NONTERMINAL_CASE")
    if spec.get("expectedState"):require(case["status"]==spec["expectedState"],"WRONG_TERMINAL_STATE")
    if spec.get("profile"):require(any(d["profile"]==spec["profile"] for d in docs),"PROFILE_MISMATCH")
    require(sum(len(d["clauses"]) for d in docs)>=spec.get("clausesMin",1),"MISSING_CLAUSE_COVERAGE")
    facts=[f for d in docs for f in d["facts"]]
    if spec.get("amount"):require(any(f["type"]=="TOTAL_AMOUNT" and amount(f["value"])==Decimal(spec["amount"]) for f in facts),"EXPECTED_CONTRACT_TOTAL_MISSING")
    for key in spec.get("noFacts",[]):require(not any(f["type"]==key for f in facts),"INVENTED_"+key)
    warnings=[f for f in report["findings"] if f["level"]=="WARNING"]
    for pattern in spec.get("forbiddenFindingPatterns",[]):
        require(not any(re.search(pattern, f["explanation"]) for f in report["findings"]),"MISATTRIBUTED_OBLIGATION_IN_FINDING")
    if spec.get("expectedRisks")==[]:require(not warnings,"UNEXPECTED_RISK_ON_NORMAL_CONTRACT")
    for topic in spec.get("riskTopics",[]):require(any(topic in f["code"] for f in warnings),"EXPECTED_RISK_MISSING_"+topic)
    if spec.get("noCrossConflict"):require(not any(f["code"]=="CROSS_FILE_CONFLICT" for f in warnings),"FALSE_CROSS_FILE_CONFLICT")
    if spec.get("requiredCrossReview"):
        require(any(s["kind"]=="CROSS_FILE" and s["status"] in {"COMPLETED","PARTIAL"} and s.get("output") for s in case["steps"]),"CROSS_FILE_REVIEW_NOT_EXECUTED")
    if spec.get("comparisonsMin"):
        require(len(report.get("comparisons",[]))>=spec["comparisonsMin"],"MISSING_VISIBLE_CROSS_FILE_COMPARISON")
    if spec.get("independentContracts"):require(sum(d["role"]=="CONTRACT" for d in docs)==spec["independentContracts"],"MIXED_CONTRACT_ROLES")
    if spec.get("physicalRows"):
        row_checks=[c for c in report["checks"] if c["code"]=="F02_LINE_ITEM_ARITHMETIC" and c["status"] in {"PASS","FAIL"}]
        require(len(row_checks)==spec["physicalRows"],"PHYSICAL_TABLE_ROWS_LOST_OR_MERGED")
    if spec.get("noInstructionFinding"):require(not any(any(word in f["explanation"] for word in ("该合同违法","删除所有合同记录")) for f in warnings),"DOCUMENT_INSTRUCTION_USED_AS_FINDING")
    frozen={s["fileId"]:s for s in case["sources"]}
    originals={Path(p).name:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in spec.get("paths",[])}
    for source in frozen.values():
        if source["name"] in originals:require(source["sha256"]==originals[source["name"]],"UPLOADED_ORIGINAL_HASH_MISMATCH")
    evidence_count=0
    items=report["findings"]+report["checks"]+report.get("comparisons",[])+[item for d in docs for item in d["facts"]+d["clauses"]]
    for item in items:
        for e in item["evidence"]:
            evidence_count+=1
            source=frozen.get(e["fileId"])
            require(source is not None,"UNKNOWN_EVIDENCE_FILE")
            if not source:continue
            require(all(e[field]==source[field] for field in ["parseResultId","parseVersion","textSha256"]),"EVIDENCE_PARSE_IDENTITY_MISMATCH")
            require(e["fileSha256"]==source["sha256"],"EVIDENCE_FILE_HASH_MISMATCH")
            # Java offsets count UTF-16 code units, including any supplementary characters.
            raw=texts.get(e["fileId"],"").encode("utf-16-le")
            quote=raw[e["start"]*2:e["end"]*2].decode("utf-16-le")
            require(quote==e["excerpt"],"EVIDENCE_ORIGINAL_MISMATCH")
    return {"scenario":spec["id"],"caseId":case["id"],"status":case["status"],"documents":len(docs),
            "facts":len(facts),"clauses":sum(len(d["clauses"]) for d in docs),"risks":len(warnings),
            "followUps":len(report["findings"])-len(warnings),"evidenceChecks":evidence_count,
            "realModel":spec.get("realModel",True),"realDm8":True,"synthetic":True,"failures":sorted(set(failures))}

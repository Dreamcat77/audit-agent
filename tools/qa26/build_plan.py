"""Plan original migrations for an EMPTY schema in the independent test instance only."""
import hashlib
import importlib.util
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("packager", ROOT / "tools/build-customer-v10.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)

def build():
    contract = json.loads((ROOT / ".test-runtime/contract-schema/base-plan.json").read_text(encoding="utf-8"))
    names = [p.name for p in sorted((ROOT / "db/dm8").glob("V*__*.sql"))]
    names += [i["file"] for i in contract["inventory"] if i["file"].startswith("CONTRACT__")]
    names += ["CONTRACT__independent_analysis_case.sql", "PROCUREMENT__f06_quote_comparison.sql", "PROCUREMENT__automatic_case_v1.sql"]
    inventory, statements, columns, equivalentOmissions = [], [], {}, []
    for name in names:
        raw = (ROOT / "db/dm8" / name).read_bytes()
        inventory.append({"file": name, "sha256": hashlib.sha256(raw).hexdigest()})
        for sql in builder.split_sql(raw.decode("utf-8")):
            if sql.startswith(("INSERT ", "UPDATE ", "DELETE ", "MERGE ", "COMMIT")):
                continue
            assert sql.startswith(("CREATE TABLE ", "CREATE INDEX ", "CREATE UNIQUE INDEX ", "ALTER TABLE ", "COMMENT ON ")), name
            normalized = re.sub(r"\s+", " ", sql)
            create = re.fullmatch(r"CREATE TABLE (\w+)\s*\((.*)\)", normalized)
            if create:
                columns[create[1]] = {part.split()[0]: part for part in builder.split_sql(create[2], ",") if not part.startswith("CONSTRAINT ")}
            add = re.fullmatch(r"ALTER TABLE (\w+) ADD (\w+) (.*)", normalized)
            if add and add[2] != "CONSTRAINT":
                table, column, tail = add.groups()
                declaration = column + " " + tail
                prior = columns.get(table, {}).get(column)
                if prior is not None:
                    assert name == "V008__f01_material_check_fields.sql" and prior == declaration, "UNREVIEWED_DUPLICATE_DDL"
                    equivalentOmissions.append({"file": name, "table": table, "column": column, "definition": declaration})
                    continue
                columns.setdefault(table, {})[column] = declaration
            statements.append(sql)
    assert len(equivalentOmissions) == 4
    return {"target": "EMPTY_QA26_FULL2_ON_AUTOCASE2_ONLY", "inventory": inventory, "statements": statements,
            "equivalentOmissions": equivalentOmissions,
            "digest": hashlib.sha256(json.dumps(statements).encode()).hexdigest()}

if __name__ == "__main__":
    plan = build()
    output = ROOT / ".test-runtime/qa26"
    output.mkdir(exist_ok=True)
    (output / "schema-plan.json").write_text(json.dumps(plan, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"planDigest": plan["digest"], "ddlCount": len(plan["statements"]), "files": len(plan["inventory"])}))

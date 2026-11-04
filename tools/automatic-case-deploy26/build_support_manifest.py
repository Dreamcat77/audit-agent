"""Project existing DDL into an empty, isolated pilot authentication/support schema.

No source migration is modified. Existing catalog verification remains authoritative.
The two contract tables satisfy the unchanged application startup invariant; their
business APIs are not exposed by the pilot reverse proxy.
"""
from pathlib import Path
import importlib.util
import json
import re

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('existing_packager', ROOT / 'tools/build-customer-v10.py')
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)
split_sql = existing.split_sql
SELECTED = ['SYS_USER', 'SYS_ROLE', 'SYS_USER_ROLE', 'AUTH_LOGIN_AUDIT',
            'AUDIT_OPERATION_LOG', 'CONTRACT', 'CONTRACT_REVIEW_SESSION', 'PROCUREMENT_TENDER_RULE',
            'MATERIAL_EXTRACTION_LEDGER']
SOURCES = ['V002__local_account_auth_observability.sql', 'V003__f02_contract_review.sql',
           'V004__contract_review_session.sql', 'V005__contract_review_session_soft_delete.sql',
           'V032__operation_audit_details.sql', 'V033__procurement_field_confirmation_audit.sql',
           'V036__f07_tender_rule_scan.sql', 'V037__f07_tender_rule_catalog_expansion.sql',
           'V040__f07_tender_rule_legal_metadata.sql', 'V055__material_extraction_ledger.sql',
           'V057__material_extraction_deadline.sql']


def column(clause):
    m = re.fullmatch(r'(\w+)\s+(VARCHAR\((\d+)\)|DECIMAL\((\d+),(\d+)\)|INTEGER|INT|SMALLINT|BIGINT|TIMESTAMP|DATE|CLOB)(.*)', clause, re.S)
    assert m, clause
    name, kind, width, precision, scale, tail = m.groups()
    result = {'name': name, 'type': 'VARCHAR' if width else 'DECIMAL' if precision else 'INT' if kind == 'INTEGER' else kind,
              'nullable': 'NOT NULL' not in tail, 'default': ''}
    if width: result['length'] = int(width)
    if precision: result.update(precision=int(precision), scale=int(scale))
    default = re.search(r"\bDEFAULT\s+('(?:[^']|'')*'|CURRENT_TIMESTAMP|\d+)", tail)
    if default: result['default'] = default[1]
    return result


def key(clause):
    m = re.fullmatch(r'CONSTRAINT (\w+) (.*)', clause, re.S)
    assert m, clause
    name, body = m.groups()
    if re.match(r'^CHECK\s*\(', body): return {'name': name, 'kind': 'C', 'expression': body[body.index('(')+1:body.rindex(')')]}
    m = re.fullmatch(r'(PRIMARY KEY|UNIQUE|FOREIGN KEY)\s*\(([^)]+)\)(.*)', body, re.S)
    assert m, body
    kind, cols, tail = m.groups()
    result = {'name': name, 'kind': {'PRIMARY KEY': 'P', 'UNIQUE': 'U', 'FOREIGN KEY': 'R'}[kind], 'columns': [p.strip() for p in cols.split(',')]}
    if kind == 'FOREIGN KEY':
        parent = re.fullmatch(r'\s*REFERENCES (\w+)\s*\(([^)]+)\)\s*', tail, re.S)
        assert parent, tail
        result.update(parent=parent[1], parentColumns=[p.strip() for p in parent[2].split(',')])
    return result


def build():
    clauses = {}; indexes = {}
    for filename in SOURCES:
        for raw in split_sql((ROOT / 'db/dm8' / filename).read_text(encoding='utf-8')):
            sql = re.sub(r'\s+', ' ', raw).strip()
            create = re.fullmatch(r'CREATE TABLE (\w+)\s*\((.*)\)', sql, re.S)
            if create and create[1] in SELECTED:
                name = create[1]; assert name not in clauses
                parts = split_sql(create[2], ',')
                if name == 'PROCUREMENT_TENDER_RULE':
                    parts = [p.replace(' PRIMARY KEY', '') for p in parts]
                    parts.append('CONSTRAINT PK_PILOT_TENDER_RULE PRIMARY KEY (ID)')
                clauses[name] = parts; indexes[name] = []
                continue
            alter = re.fullmatch(r'ALTER TABLE (\w+) ADD (.*)', sql, re.S)
            if alter and alter[1] in clauses:
                clauses[alter[1]].append(alter[2]); continue
            index = re.fullmatch(r'CREATE (UNIQUE )?INDEX (\w+) ON (\w+)\s*\(([^)]+)\)', sql, re.S)
            if index and index[3] in clauses:
                indexes[index[3]].append(sql)
    assert set(clauses) == set(SELECTED)
    tables = []; statements = []; ledger_tables = []; ledger_statements = []
    for name in SELECTED:
        table = {'name': name, 'columns': [], 'constraints': [], 'indexes': []}
        for part in clauses[name]:
            table['constraints' if part.startswith('CONSTRAINT ') else 'columns'].append(key(part) if part.startswith('CONSTRAINT ') else column(part))
        target_tables, target_statements = (ledger_tables, ledger_statements) if name == 'MATERIAL_EXTRACTION_LEDGER' else (tables, statements)
        target_statements.append('CREATE TABLE ' + name + ' (' + ', '.join(clauses[name]) + ')')
        for sql in indexes[name]:
            m = re.fullmatch(r'CREATE (UNIQUE )?INDEX (\w+) ON (\w+)\s*\(([^)]+)\)', sql)
            table['indexes'].append({'name': m[2], 'uniqueness': 'UNIQUE' if m[1] else 'NONUNIQUE',
                                     'columns': [p.strip() + ':ASC' for p in m[4].split(',')]})
            target_statements.append(sql)
        target_tables.append(table)
    groups = [{'name': 'ISOLATED_PILOT_AUTH_SUPPORT_V1', 'tables': tables, 'statements': statements},
              {'name': 'ISOLATED_MATERIAL_EXTRACTION_LEDGER_V1', 'tables': ledger_tables, 'statements': ledger_statements}]
    return {'baseline': [], 'groups': groups, 'checks': [],
            'sourceMigrations': SOURCES, 'originalDatabaseUseForbidden': True}


if __name__ == '__main__':
    output = ROOT / 'docs/automatic-procurement-deploy26-20261027/evidence/pilot-support-schema.json'
    output.write_text(json.dumps(build(), ensure_ascii=False, indent=2), encoding='utf-8')
    print('PILOT_SUPPORT_MANIFEST_BUILT tables=9')

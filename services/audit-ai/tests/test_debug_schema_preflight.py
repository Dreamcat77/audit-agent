from copy import deepcopy
from audit_ai.quality.schema_preflight import classify, expected_catalog


def test_absence_requires_proven_dependencies():
    assert classify({"readable": True, "dependenciesMatching": True, "tables": {}}) == "NOT_APPLIED"
    assert classify({"readable": False}) == "UNKNOWN"
    assert classify({"readable": True, "dependenciesMatching": False, "tables": {}}) == "UNKNOWN"


def test_matching_and_drift_are_distinct_and_never_execute_sql():
    observed = {"readable": True, "dependenciesMatching": True, "tables": expected_catalog()}
    assert classify(observed) == "ALREADY_MATCHING"
    changed = deepcopy(observed)
    changed["tables"]["SANITIZED_DEBUG_BUNDLE"]["columns"].pop("ARTIFACT_SHA256")
    assert classify(changed) == "PARTIAL-SCHEMA_DRIFT"
    changed = deepcopy(observed)
    changed["tables"]["SANITIZED_DEBUG_BUNDLE"]["constraints"].pop("FK_SDB_ACTOR")
    assert classify(changed) == "PARTIAL-SCHEMA_DRIFT"


def test_incomplete_catalog_evidence_fails_unknown():
    assert classify({"readable": True, "dependenciesMatching": True}) == "UNKNOWN"


def test_preflight_catalog_matches_owned_migration_definitions():
    from pathlib import Path
    sql = (Path(__file__).resolve().parents[3] / "db/dm8/CONTRACT__sanitized_debug_bundle.sql").read_text(encoding="utf-8")
    expected = expected_catalog()["SANITIZED_DEBUG_BUNDLE"]
    for name, definition in expected["columns"].items():
        if definition.endswith(" NULL") and not definition.endswith(" NOT NULL"):
            definition = definition.removesuffix(" NULL")
        assert f"{name} {definition}" in sql
    for name, definition in expected["constraints"].items():
        assert f"CONSTRAINT {name} {definition}" in sql
    assert "CREATE INDEX IDX_SDB_RUN ON SANITIZED_DEBUG_BUNDLE (RUN_ID,REQUESTED_AT,BUNDLE_ID)" in sql
    assert "-- DEPENDS-ON: review-run-results" in sql
    assert sql.count("CREATE TABLE ") == 1
    assert "ALTER TABLE" not in sql

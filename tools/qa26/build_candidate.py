"""Package the live worktree after passing verify; no customer config or runtime secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    revision = argparse.ArgumentParser()
    revision.add_argument("revision")
    revision.add_argument("--verify-log", default="p0-full-java-verify.log")
    revision.add_argument("--build-dir", default=".test-runtime/qa26-java")
    args = revision.parse_args()
    assert args.revision.isalnum()
    target = ROOT / (".test-runtime/qa26/candidate-" + args.revision)
    assert not target.exists()
    target.mkdir()
    assert Path(args.verify_log).name == args.verify_log
    log = (ROOT / "docs/qa-26/evidence" / args.verify_log).read_text(encoding="utf-8", errors="replace")
    assert "BUILD SUCCESS" in log and "BUILD FAILURE" not in log
    stage = target / "release"
    (stage / "backend").mkdir(parents=True)
    build = (ROOT / args.build_dir).resolve()
    assert build.is_relative_to(ROOT)
    jar = build / "audit-backend-0.1.0-SNAPSHOT.jar"
    shutil.copy2(jar, stage / "backend/audit-backend.jar")
    shutil.copytree(ROOT / "frontend/dist", stage / "frontend")
    shutil.copytree(ROOT / "services/audit-ai/src", stage / "services/audit-ai/src", ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    for name in ("pyproject.toml", "requirements-runtime.txt"):
        shutil.copy2(ROOT / "services/audit-ai" / name, stage / "services/audit-ai" / name)
    tools = stage / "tools"
    tools.mkdir()
    for name, source in {
        "MetadataSnapshot.java": "tools/automatic-case-deploy26/MetadataSnapshot.java",
        "ContractAnalysisSchema.java": "tools/contract-automation/ContractAnalysisSchema.java",
        "ContractAnalysisSchemaTest.java": "tools/contract-automation/ContractAnalysisSchemaTest.java",
        "analysis.sql": "db/dm8/CONTRACT__independent_analysis_case.sql",
        "procurement.sql": "db/dm8/PROCUREMENT__automatic_case_v1.sql",
        "remote_install.py": "tools/qa26/remote_install.py",
        "preserve_ocr_models.py": "tools/qa26/preserve_ocr_models.py",
    }.items():
        shutil.copy2(ROOT / source, tools / name)
    admin = (ROOT / "tools/automatic-case-deploy26/PilotAdmin.java").read_text(encoding="utf-8")
    admin = admin.replace("PilotAdmin", "Qa26Admin").replace('"AUTOCASE_APP"', '"QA26_FULL2"')
    (tools / "Qa26Admin.java").write_text(admin, encoding="utf-8")
    check = (ROOT / "tools/automatic-case-phase2/AutoCaseSchemaCheck.java").read_text(encoding="utf-8")
    check = check.replace("AutoCaseSchemaCheck", "Qa26CaseSchemaCheck").replace('"AUTOCASE_APP"', '"QA26_FULL2"')
    (tools / "Qa26CaseSchemaCheck.java").write_text(check, encoding="utf-8")
    libs = target / "compile-libs"
    libs.mkdir()
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                (libs / Path(name).name).write_bytes(z.read(name))
    classes = tools / "classes"
    classes.mkdir()
    result = subprocess.run([str(Path(os.environ["JAVA_HOME"]) / "bin/javac.exe"), "--release", "17", "-encoding", "UTF-8",
                             "-cp", str(build / "classes") + os.pathsep + str(libs / "*"), "-d", str(classes)]
                            + [str(p) for p in tools.glob("*.java")], capture_output=True)
    assert result.returncode == 0, result.stderr.decode("utf-8", errors="replace")
    for path in classes.glob("*.class"):
        assert int.from_bytes(path.read_bytes()[6:8], "big") == 61
    manifest = {"version": "qa26-20261027-" + args.revision,
                "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "includesUncommittedWorktree": True, "files": []}
    manifest["sourceFiles"] = [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)}
            for directory in ("backend/src", "frontend/src", "services/audit-ai/src")
            for path in sorted((ROOT / directory).rglob("*"))
            if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"]
    manifest["verifyLogSha256"] = sha(ROOT / "docs/qa-26/evidence" / args.verify_log)
    for path in sorted(stage.rglob("*")):
        if path.is_file():
            manifest["files"].append({"path": path.relative_to(stage).as_posix(), "sha256": sha(path), "bytes": path.stat().st_size})
    (stage / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    archive = target / ("qa26-" + args.revision + ".tar.gz")
    with tarfile.open(archive, "w:gz") as tar:
        for path in sorted(stage.rglob("*")):
            if path.is_file():
                tar.add(path, arcname=path.relative_to(stage).as_posix(), recursive=False)
    summary = {"path": str(archive), "sha256": sha(archive), "files": len(manifest["files"]), "version": manifest["version"]}
    (ROOT / "docs/qa-26/evidence" / ("candidate-" + args.revision + ".json")).write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary))

if __name__ == "__main__":
    main()

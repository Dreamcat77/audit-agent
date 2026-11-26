import json
from pathlib import Path
import shutil
import tempfile
import unittest

from check_field_simulation import ROOT, inspect_materials


class FrozenMaterialsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "materials"
        shutil.copytree(ROOT / "test-materials/field-simulation-20261126-r3", self.directory)

    def test_valid_files_are_read_without_exporting_document_text(self):
        result = inspect_materials(self.directory)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(len(result["files"]), 12)
        for record in result["files"]:
            self.assertNotIn("text", record)
        self.assertEqual(result["remoteStatus"], "PENDING_LOCAL_26")

    def test_modified_file_fails_checksum(self):
        path = next((self.directory / "F26B44").glob("*.docx"))
        path.write_bytes(path.read_bytes() + b"modified")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            inspect_materials(self.directory)

    def test_extra_material_cannot_silently_join_a_batch(self):
        (self.directory / "F26B44" / "extra.txt").write_text("synthetic", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs from manifest"):
            inspect_materials(self.directory)

    def test_duplicate_file_cannot_replace_missing_file(self):
        path = self.directory / "manifest.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        batch = manifest["batches"][0]
        batch["files"][1] = batch["files"][0]
        path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "four distinct"):
            inspect_materials(self.directory)


if __name__ == "__main__":
    unittest.main()

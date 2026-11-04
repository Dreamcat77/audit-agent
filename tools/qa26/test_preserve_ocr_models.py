from pathlib import Path
import tempfile
import unittest

from preserve_ocr_models import preserve


class ModelPreservationTest(unittest.TestCase):
    def test_source_only_release_retains_offline_models_and_new_application(self):
        with tempfile.TemporaryDirectory() as folder:
            old, new = Path(folder) / "old", Path(folder) / "new"
            model = old / "services/audit-ai/models/orientation"
            model.mkdir(parents=True)
            for name in ("inference.json", "inference.pdiparams", "inference.yml"):
                (model / name).write_bytes(name.encode())
            new.mkdir()
            (new / "application").write_bytes(b"new release")
            self.assertEqual(preserve(old, new), 3)
            self.assertEqual(preserve(old, new), 3)
            self.assertEqual((new / "application").read_bytes(), b"new release")
            self.assertEqual((new / "services/audit-ai/models/orientation/inference.pdiparams").read_bytes(), b"inference.pdiparams")

    def test_external_model_installation_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(preserve(Path(folder) / "old", Path(folder) / "new"), 0)


if __name__ == "__main__":
    unittest.main()

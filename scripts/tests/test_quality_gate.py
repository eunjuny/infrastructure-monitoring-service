"""Regression tests for the guardrail itself; no Docker, network or application DB."""

from pathlib import Path
import base64
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quality_gate import MAX_DOCUMENT_IMAGE_BYTES, REQUIRED, check_files, check_wrapper, run


class QualityGateTest(unittest.TestCase):
    def snapshot(self, **extra):
        return {**{path: "# Example\n" for path in REQUIRED}, **extra}

    def test_valid_snapshot(self):
        self.assertEqual([], check_files(self.snapshot()))

    def test_required_documents(self):
        self.assertTrue(any("required-document" in error for error in check_files({})))

    def test_public_checkout_does_not_require_private_history(self):
        self.assertNotIn("docs/WORK_HISTORY.md", REQUIRED)
        self.assertEqual([], check_files(self.snapshot()))

    def test_relative_document_link_and_anchor(self):
        files = self.snapshot(**{"docs/nested/guide.md": "[Guide](../PROJECT_GUIDE.md#purpose)\n"})
        self.assertEqual([], check_files(files))

    def test_broken_local_link(self):
        errors = check_files(self.snapshot(**{"README.md": "[Missing](docs/absent.md)\n"}))
        self.assertIn("local-document-link: README.md", errors)

    def test_unterminated_document_link_is_rejected(self):
        errors = check_files(self.snapshot(**{"README.md": "[Guide](docs/PROJECT_GUIDE.\n"}))
        self.assertIn("unterminated-document-link: README.md", errors)

    def test_external_links_and_code_examples_are_not_resolved(self):
        files = self.snapshot(**{"README.md": "[External](https://example.invalid)\n```md\n[x](absent.md)\n```\n"})
        self.assertEqual([], check_files(files))

    def test_secrets_fail_without_printing_value(self):
        secret = "gh" + "p_" + "a" * 36
        errors = check_files(self.snapshot(**{"example.ts": secret + "\n"}))
        self.assertIn("possible-secret: example.ts", errors)
        self.assertNotIn(secret, " ".join(errors))

    def test_raw_html_and_typescript_suppression(self):
        for source, rule in [("<div v-html=\"input\" />\n", "unsafe-browser-execution"),
                             ("// @ts-ignore\n", "typescript-suppression")]:
            with self.subTest(rule=rule):
                self.assertIn(f"{rule}: frontend/src/Bad.vue",
                              check_files(self.snapshot(**{"frontend/src/Bad.vue": source})))

    def test_fetch_only_in_shared_api_client(self):
        self.assertEqual([], check_files(self.snapshot(**{"frontend/src/api.ts": "fetch(url)\n"})))
        self.assertIn("http-client-boundary: frontend/src/view.ts",
                      check_files(self.snapshot(**{"frontend/src/view.ts": "fetch(url)\n"})))

    def test_controller_cannot_import_entity_manager(self):
        path = "backend/src/main/java/example/BadController.java"
        errors = check_files(self.snapshot(**{path: "import jakarta.persistence.EntityManager;\n"}))
        self.assertIn(f"controller-persistence-boundary: {path}", errors)

    def test_jdbc_allowed_in_tests_not_production(self):
        self.assertEqual([], check_files(self.snapshot(**{"backend/src/test/java/Fixture.java": "JdbcTemplate\n"})))
        path = "backend/src/main/java/example/Bad.java"
        self.assertIn(f"jpa-production-boundary: {path}",
                      check_files(self.snapshot(**{path: "JdbcTemplate\n"})))

    def test_osiv_and_schema_validation_required(self):
        path = "backend/src/main/resources/application.yaml"
        errors = check_files(self.snapshot(**{path: "spring:\n  jpa:\n    open-in-view: true\n"}))
        self.assertIn(f"osiv-disabled: {path}", errors)
        self.assertIn(f"flyway-schema-ownership: {path}", errors)

    def test_migration_sequence_and_duplicates(self):
        prefix = "backend/src/main/resources/db/migration/"
        for names in [("V2__skip.sql",), ("V1__first.sql", "V1__duplicate.sql")]:
            with self.subTest(names=names):
                errors = check_files(self.snapshot(**{prefix + name: "select 1;\n" for name in names}))
                self.assertIn("migration-sequence: backend/src/main/resources/db/migration", errors)

    def test_line_endings_whitespace_and_final_newline(self):
        errors = check_files(self.snapshot(**{"example.ts": "const x = 1; \r\nx"}))
        for rule in ["lf-line-endings", "trailing-whitespace", "final-newline"]:
            self.assertIn(f"{rule}: example.ts", errors)


class GitBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory(prefix="monitoring-gate-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Gate Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        for path in REQUIRED:
            self.write(path, "# Fixture\n")
        self.migration = "backend/src/main/resources/db/migration/V1__fixture.sql"
        self.write(self.migration, "select 1;\n")
        self.write(".gitignore", ".local/\n")
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "Fixture baseline")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def write(self, path, source):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def invoke(self):
        output = StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            result = run(self.root)
        return result, output.getvalue()

    def image_fixture(self, path="docs/images/capture.png"):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3f8AAAAASUVORK5CYII="))
        return target

    def test_private_notes_cannot_be_linked_from_public_readme(self):
        path = ".local/internal-docs/WORK_HISTORY.md"
        self.write(path, "# Private history\n")
        self.write("README.md", f"[History]({path})\n")
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("local-document-link: README.md", output)

    def test_document_image_links_accept_bounded_png_without_utf8_decoding(self):
        self.image_fixture()
        self.write("README.md", "![Capture](docs/images/capture.png)\n")
        self.write("docs/PROJECT_GUIDE.md", "[Capture](images/capture.png)\n")
        result, output = self.invoke()
        self.assertEqual(0, result, output)

    def test_missing_image_still_fails_local_link_check(self):
        self.write("README.md", "![Missing](docs/images/missing.png)\n")
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("local-document-link: README.md", output)

    def test_invalid_image_signature_and_extension_mismatch_are_rejected(self):
        for content in [b"not a JPEG image at all\n", self.image_fixture().read_bytes()]:
            with self.subTest(signature=content[:4]):
                target = self.root / "docs/images/capture.jpg"
                target.write_bytes(content)
                self.write("README.md", "![Capture](docs/images/capture.jpg)\n")
                result, output = self.invoke()
                self.assertEqual(1, result)
                self.assertIn("document-image-format: docs/images/capture.jpg", output)

    def test_oversized_document_image_is_rejected(self):
        target = self.image_fixture()
        with target.open("r+b") as image:
            image.truncate(MAX_DOCUMENT_IMAGE_BYTES + 1)
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("document-image-format: docs/images/capture.png", output)

    def test_ignored_and_out_of_scope_images_do_not_bypass_link_validation(self):
        for path in [".local/capture.png", "frontend/capture.png"]:
            with self.subTest(path=path):
                self.image_fixture(path)
                self.write("README.md", f"![Capture]({path})\n")
                result, output = self.invoke()
                self.assertEqual(1, result)
                self.assertIn("local-document-link: README.md", output)

    def test_image_symlink_cannot_read_excluded_files(self):
        secret = self.image_fixture(".local/private.png")
        target = self.root / "docs/images/capture.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.symlink_to(secret)
        self.write("README.md", "![Capture](docs/images/capture.png)\n")
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("document-image-format: docs/images/capture.png", output)

    def test_untracked_next_migration_allowed_and_ignored_secret_not_read(self):
        self.write("backend/src/main/resources/db/migration/V2__next.sql", "select 2;\n")
        self.write(".local/secret.ts", "gh" + "p_" + "b" * 36 + "\n")
        result, output = self.invoke()
        self.assertEqual(0, result, output)

    def test_modified_committed_migration_rejected(self):
        self.write(self.migration, "select 2;\n")
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("committed-migration-immutable", output)

    def test_staged_migration_change_rejected(self):
        self.write(self.migration, "select 2;\n")
        self.git("add", self.migration)
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("committed-migration-immutable", output)

    def test_deleted_migration_rejected(self):
        (self.root / self.migration).unlink()
        result, output = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("committed-migration-immutable", output)

    def test_gradle_integrity_and_missing_metadata_rejected(self):
        self.write("backend/build.gradle.kts", "plugins { java }\n")
        self.write("backend/gradle/wrapper/gradle-wrapper.jar", "invalid binary\n")
        errors = check_wrapper(self.root)
        self.assertTrue(any("gradle-wrapper-integrity" in error for error in errors))
        self.assertTrue(any("gradle-distribution-pinned" in error for error in errors))
        self.assertTrue(any("gradle-required-file" in error for error in errors))

    def test_maven_and_gradle_cannot_coexist(self):
        self.write("backend/build.gradle.kts", "plugins { java }\n")
        self.write("backend/pom.xml", "<project/>\n")
        self.assertIn("single-build-system: backend/pom.xml", check_wrapper(self.root))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Small, offline source guardrails; not a compiler, parser or security audit."""

from pathlib import Path
import hashlib
import re
import subprocess
import sys
from urllib.parse import unquote


TEXT_SUFFIXES = {".java", ".ts", ".vue", ".css", ".json", ".xml", ".yaml", ".yml",
                 ".md", ".py", ".sh", ".mjs", ".sql", ".html", ".conf", ".groovy",
                 ".kts", ".properties", ".lockfile"}
TEXT_NAMES = {"AGENTS.md", "README.md", "Jenkinsfile", "Dockerfile", ".gitignore",
              ".dockerignore", ".editorconfig", "gradlew"}
GRADLE_VERSION = "8.14.3"
GRADLE_ZIP_SHA256 = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
GRADLE_WRAPPER_SHA256 = "7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"
REQUIRED = {"AGENTS.md", "README.md", "docs/PROJECT_GUIDE.md",
            "docs/DEVELOPMENT_GUIDE.md", "docs/QUALITY_GATES.md"}
DOCUMENT_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
MAX_DOCUMENT_IMAGE_BYTES = 5 * 1024 * 1024
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}\b"),
)


def check_files(files: dict[str, str], image_paths: frozenset[str] = frozenset()) -> list[str]:
    """Check text snapshots. Diagnostics report rule/path, never matched values."""
    errors = []

    def reject(rule: str, path: str) -> None:
        errors.append(f"{rule}: {path}")

    for required in sorted(REQUIRED - files.keys()):
        reject("required-document", required)

    versions = []
    for path, source in sorted(files.items()):
        if "\r" in source:
            reject("lf-line-endings", path)
        if source and not source.endswith("\n"):
            reject("final-newline", path)
        # Markdown's intentional two-space line breaks are accepted.
        if not path.endswith(".md") and any(line.rstrip(" \t") != line for line in source.splitlines()):
            reject("trailing-whitespace", path)
        if any(pattern.search(source) for pattern in SECRET_PATTERNS):
            reject("possible-secret", path)

        if path.endswith(".md"):
            # Inline local links only. No network, anchor or reference-link validation.
            text = re.sub(r"```[\s\S]*?```", "", source)
            if re.search(r"\]\([^\)\n]*$", text, re.M):
                reject("unterminated-document-link", path)
            for target in re.findall(r"\]\(([^)\s]+)\)", text):
                target = target.strip("<>")
                if target.startswith("#") or re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", target):
                    continue
                target = unquote(target.split("#", 1)[0])
                candidate = Path(path).parent / target
                parts = []
                for part in candidate.parts:
                    if part == ".." and parts and parts[-1] != "..":
                        parts.pop()
                    elif part != ".":
                        parts.append(part)
                resolved = "/".join(parts)
                if target and resolved not in files and resolved not in image_paths:
                    reject("local-document-link", path)
                    break

        if path.startswith("frontend/src/") and path.endswith((".ts", ".vue")):
            if re.search(r"\bv-html\s*=|\.innerHTML\s*=|\beval\s*\(|\bnew\s+Function\s*\(", source):
                reject("unsafe-browser-execution", path)
            if path != "frontend/src/api.ts" and re.search(r"\bfetch\s*\(", source):
                reject("http-client-boundary", path)
            if "@ts-ignore" in source or "@ts-nocheck" in source:
                reject("typescript-suppression", path)

        if path.startswith("backend/src/main/java/") and path.endswith(".java"):
            if re.search(r"\b(?:JdbcTemplate|NamedParameterJdbcTemplate)\b", source):
                reject("jpa-production-boundary", path)
            if path.endswith("Controller.java") and re.search(
                r"import\s+(?:jakarta\.persistence|java\.sql|javax\.sql|org\.springframework\.jdbc|dev\.infrastructure\.monitoring\.persistence)\.", source
            ):
                reject("controller-persistence-boundary", path)

        if path == "backend/src/main/resources/application.yaml":
            if not re.search(r"^\s+open-in-view:\s*false\s*$", source, re.M):
                reject("osiv-disabled", path)
            if not re.search(r"^\s+ddl-auto:\s*validate\s*$", source, re.M):
                reject("flyway-schema-ownership", path)

        if path.startswith("backend/src/main/resources/db/migration/") and path.endswith(".sql"):
            match = re.fullmatch(r"V([1-9]\d*)__[a-z0-9_]+\.sql", Path(path).name)
            if not match:
                reject("migration-filename", path)
            else:
                versions.append(int(match[1]))

    if sorted(versions) != list(range(1, len(versions) + 1)):
        errors.append("migration-sequence: backend/src/main/resources/db/migration")
    return errors


def check_document_images(root: Path, paths: list[str]) -> tuple[frozenset[str], list[str]]:
    """Allow bounded repository document images, not arbitrary binary link targets."""
    valid = set()
    errors = []
    for name in sorted(set(paths)):
        relative = Path(name)
        if relative.parts[:2] != ("docs", "images") or relative.suffix.lower() not in DOCUMENT_IMAGE_SUFFIXES:
            continue
        path = root / relative
        # Never follow a tracked link into excluded secrets or outside the checkout.
        if path.is_symlink() or any((root / parent).is_symlink() for parent in relative.parents):
            errors.append(f"document-image-format: {name}")
            continue
        if not path.is_file():
            continue
        size = path.stat().st_size
        if size < 16 or size > MAX_DOCUMENT_IMAGE_BYTES:
            errors.append(f"document-image-format: {name}")
            continue
        with path.open("rb") as source:
            header = source.read(8)
            source.seek(-12, 2)
            ending = source.read(12)
        if relative.suffix.lower() == ".png":
            matches = header == b"\x89PNG\r\n\x1a\n" and ending == b"\x00\x00\x00\x00IEND\xaeB`\x82"
        else:
            matches = header.startswith(b"\xff\xd8\xff") and ending.endswith(b"\xff\xd9")
        if matches:
            valid.add(name)
        else:
            errors.append(f"document-image-format: {name}")
    return frozenset(valid), errors


def check_wrapper(root: Path) -> list[str]:
    # Separate binary verification; generated Windows script keeps official CRLF.
    if not (root / "backend/build.gradle.kts").is_file():
        return []
    errors = []
    wrapper = root / "backend/gradle/wrapper/gradle-wrapper.jar"
    if not wrapper.is_file() or hashlib.sha256(wrapper.read_bytes()).hexdigest() != GRADLE_WRAPPER_SHA256:
        errors.append("gradle-wrapper-integrity: backend/gradle/wrapper/gradle-wrapper.jar")
    properties = root / "backend/gradle/wrapper/gradle-wrapper.properties"
    values = {}
    if properties.is_file():
        values = dict(line.split("=", 1) for line in properties.read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    if (values.get("distributionUrl") != f"https\\://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip"
            or values.get("distributionSha256Sum") != GRADLE_ZIP_SHA256):
        errors.append("gradle-distribution-pinned: backend/gradle/wrapper/gradle-wrapper.properties")
    for name in ["backend/settings.gradle.kts", "backend/gradle.lockfile", "backend/gradlew", "backend/gradlew.bat"]:
        if not (root / name).is_file():
            errors.append(f"gradle-required-file: {name}")
    if (root / "backend/pom.xml").exists():
        errors.append("single-build-system: backend/pom.xml")
    return errors


def run(root: Path) -> int:
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
    ).decode().split("\0")
    files = {}
    for name in sorted(set(paths) - {""}):
        path = root / name
        if path.is_symlink():
            continue
        if path.is_file() and (path.suffix in TEXT_SUFFIXES or path.name in TEXT_NAMES):
            # Read bytes to preserve CRLF detection. Ignore Git-excluded secrets/artifacts.
            try:
                files[name] = path.read_bytes().decode("utf-8")
            except UnicodeDecodeError:
                print(f"utf8-encoding: {name}", file=sys.stderr)
                return 1
    images, image_errors = check_document_images(root, paths)
    errors = check_files(files, images)
    errors.extend(image_errors)
    errors.extend(check_wrapper(root))
    # Once a migration has entered local history, repair via a new migration.
    changed = subprocess.check_output(
        ["git", "diff", "HEAD", "--name-only", "--diff-filter=MD", "--",
         "backend/src/main/resources/db/migration/"], cwd=root
    ).decode().splitlines()
    errors.extend(f"committed-migration-immutable: {name}" for name in changed)
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        print(f"Quality gate failed: {len(errors)} issue(s)", file=sys.stderr)
        return 1
    print(f"Quality gate passed: {len(files)} text files; no network or application startup")
    return 0


if __name__ == "__main__":
    sys.exit(run(Path(__file__).resolve().parent.parent))

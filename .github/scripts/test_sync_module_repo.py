"""Release mirroring contract against a disposable GitHub service model."""

import base64
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import sync_module_repo as sync


APK = b"original signed APK bytes\x00\xff"
DIGEST = "sha256:" + hashlib.sha256(APK).hexdigest()
TAG = "6-0.5.0"
NAME = "OneShade-v0.5.0.apk"
FILES = {NAME: APK, "SHA256SUMS": f"{DIGEST[7:]}  {NAME}\n".encode()}


class GitHub:
    def __init__(self, code=6, version="0.5.0"):
        self.tag = f"{code}-{version}"
        self.gradle = ("applicationId 'io.github.ffdvdgd.oneshade'\n"
                       f"versionCode {code}\nversionName '{version}'\n")
        self.files = dict(FILES)
        self.source = {"tag_name": f"v{version}", "draft": False, "prerelease": False,
                       "body": "中文更新说明\n", "assets": [
                           self.asset(i, "sha256:" + hashlib.sha256(data).hexdigest(), name=name)
                           for i, (name, data) in enumerate(self.files.items(), 1)]}
        self.releases = []
        self.binaries = {}

    @staticmethod
    def asset(identifier, digest, state="uploaded", name=NAME):
        return {"id": identifier, "name": name, "digest": digest, "state": state}

    def run(self, command, **options):
        if command[:2] == ["gh", "api"]:
            path = command[2]
            method = command[command.index("--method") + 1]
            data = json.loads(options["input"]) if options.get("input") else None
            if path.startswith(f"repos/{sync.SOURCE}/releases/"):
                response = self.source
            elif path == f"repos/{sync.SOURCE}/contents/app/build.gradle?ref={self.source['tag_name']}":
                response = {"content": base64.b64encode(self.gradle.encode()).decode()}
            elif path == f"repos/{sync.TARGET}/releases" and method == "POST":
                response = {**data, "id": len(self.releases) + 10, "assets": []}
                self.releases.append(response)
            elif "?per_page=" in path:
                response = self.releases
            elif "/releases/tags/" in path:
                # GitHub's REST tag endpoint cannot see drafts.
                response = next((item for item in self.releases
                                 if item["tag_name"] == self.tag and not item["draft"]), None)
                if response is None:
                    return subprocess.CompletedProcess(command, 1, '{"status":"404"}', "not found")
            elif "/releases/assets/" in path and method == "DELETE":
                identifier = int(path.rsplit("/", 1)[1])
                for release in self.releases:
                    release["assets"] = [asset for asset in release["assets"]
                                         if asset["id"] != identifier]
                self.binaries.pop(identifier, None)
                response = None
            elif "/releases/" in path:
                identifier = int(path.rsplit("/", 1)[1])
                response = next(item for item in self.releases if item["id"] == identifier)
                if method == "PATCH":
                    if data.get("draft") is False:
                        assert {item["name"]: self.binaries[item["id"]]
                                for item in response["assets"]} == self.files
                    response.update(data)
            else:
                raise AssertionError(f"Unexpected API route: {path}")
            return subprocess.CompletedProcess(command, 0, json.dumps(response), "")

        if command[:3] == ["gh", "release", "download"]:
            for name, data in self.files.items():
                (Path(command[command.index("--dir") + 1]) / name).write_bytes(data)
        elif command[:3] == ["gh", "release", "upload"]:
            release = next(item for item in self.releases if item["tag_name"] == command[3])
            path = Path(command[4])
            data = path.read_bytes()
            identifier = max(self.binaries, default=100) + 1
            release["assets"].append(self.asset(
                identifier, "sha256:" + hashlib.sha256(data).hexdigest(), name=path.name))
            self.binaries[identifier] = data
        else:
            raise AssertionError(f"Unexpected command: {command}")
        return subprocess.CompletedProcess(command, 0)


class ReleaseSyncTest(unittest.TestCase):
    def setUp(self):
        self.github = GitHub()
        self.patcher = patch.object(sync.subprocess, "run",
                                    side_effect=lambda *args, **kwargs: self.github.run(*args, **kwargs))
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def mirror(self):
        scratch = Path(__file__).resolve().parents[2] / ".amp" / "in"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            sync.sync_release(self.github.source["tag_name"], Path(directory))

    def test_publish_original_apk_and_repeat_without_changes(self):
        self.mirror()
        release = self.github.releases[0]
        self.assertEqual(len(self.github.releases), 1)
        self.assertFalse(release["draft"])
        self.assertEqual((release["name"], release["tag_name"], release["body"]),
                         ("0.5.0", TAG, "中文更新说明\n"))
        self.assertEqual(release["make_latest"], "true")
        self.assertCountEqual(self.github.binaries.values(), FILES.values())
        saved = copy.deepcopy(release)
        self.mirror()
        self.assertEqual(self.github.releases, [saved])
        self.assertCountEqual(self.github.binaries.values(), FILES.values())
        self.github.source["body"] = "更正后的中文说明\n"
        self.mirror()
        self.assertEqual(self.github.releases[0]["body"], "更正后的中文说明\n")
        self.assertEqual(self.github.releases[0]["assets"], saved["assets"])
        self.assertCountEqual(self.github.binaries.values(), FILES.values())

    def test_resume_draft_after_interrupted_asset_upload(self):
        draft = {"id": 42, "tag_name": TAG, "draft": True, "prerelease": False,
                 "name": "0.5.0", "body": "", "assets": [GitHub.asset(51, None, "starter")]}
        self.github.releases.append(draft)
        self.mirror()
        self.assertEqual(len(self.github.releases), 1)
        self.assertEqual(self.github.releases[0]["id"], 42)
        self.assertFalse(self.github.releases[0]["draft"])
        self.assertCountEqual(self.github.binaries.values(), FILES.values())

    def test_refuse_to_replace_a_published_apk(self):
        release = {"id": 42, "tag_name": TAG, "draft": False, "prerelease": False,
                   "name": "0.5.0", "body": "保留已发布内容", "assets": [GitHub.asset(51, "sha256:different")]}
        self.github.releases.append(release)
        self.github.binaries[51] = b"another APK"
        saved = copy.deepcopy(release)
        with self.assertRaisesRegex(RuntimeError, "不会覆盖"):
            self.mirror()
        self.assertEqual(self.github.releases, [saved])
        self.assertEqual(self.github.binaries, {51: b"another APK"})

    def test_use_version_from_the_released_tag_not_current_main(self):
        self.github = GitHub(code=14, version="0.8.2")
        self.mirror()
        self.assertEqual((self.github.releases[0]["tag_name"], self.github.releases[0]["name"]),
                         ("14-0.8.2", "0.8.2"))

    def test_reject_wrong_package_or_version_before_publishing(self):
        for old, new in [("io.github.ffdvdgd.oneshade", "io.github.opd2413.ctrlcenter"),
                         ("versionName '0.5.0'", "versionName '0.6.0'")]:
            with self.subTest(new=new):
                self.github = GitHub()
                self.github.gradle = self.github.gradle.replace(old, new)
                with self.assertRaisesRegex(RuntimeError, "do not match"):
                    self.mirror()
                self.assertEqual(self.github.releases, [])

    def test_reject_checksum_file_that_does_not_match_apk(self):
        data = f"{'0' * 64}  {NAME}\n".encode()
        self.github.files["SHA256SUMS"] = data
        self.github.source["assets"][1]["digest"] = "sha256:" + hashlib.sha256(data).hexdigest()
        with self.assertRaisesRegex(RuntimeError, "SHA256SUMS does not match APK"):
            self.mirror()
        self.assertEqual(self.github.releases, [])


class DocumentationSyncTest(unittest.TestCase):
    def test_mirror_distribution_readme_only_and_repeat_without_commit(self):
        scratch = Path(__file__).resolve().parents[2] / ".amp" / "in"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            source = root / "source"
            (source / "docs" / "lsposed").mkdir(parents=True)
            (source / "README.md").write_text("Source-only developer navigation\n")
            (source / "docs" / "lsposed" / "README.md").write_text("Public module introduction\n")
            (source / "SUMMARY").write_text("Short summary\n")
            (source / "LICENSE").write_text("License\n")
            remote = root / "target.git"
            subprocess.run(["git", "init", "--bare", "--initial-branch=main", str(remote)], check=True)
            run = subprocess.run

            def local_git(command, **options):
                command = [str(remote) if arg == f"https://github.com/{sync.TARGET}.git" else arg
                           for arg in command]
                return run(command, **options)

            head = None
            for name in ("first", "repeat"):
                work = root / name
                work.mkdir()
                with patch.object(sync.subprocess, "run", side_effect=local_git):
                    sync.sync_docs(source, work)
                repository = work / "repository"
                self.assertEqual((repository / "README.md").read_text(), "Public module introduction\n")
                self.assertEqual((repository / "SUMMARY").read_text(), "Short summary\n")
                self.assertEqual((repository / "LICENSE").read_text(), "License\n")
                tracked = run(["git", "-C", str(repository), "ls-files"],
                              check=True, capture_output=True, text=True).stdout.splitlines()
                self.assertEqual(tracked, ["LICENSE", "README.md", "SUMMARY"])
                current = run(["git", "-C", str(repository), "rev-parse", "HEAD"],
                              check=True, capture_output=True, text=True).stdout
                if head is not None:
                    self.assertEqual(current, head)
                head = current


if __name__ == "__main__":
    unittest.main()

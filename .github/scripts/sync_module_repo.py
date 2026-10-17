"""Mirror public module documentation and released APKs, never application source."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import quote


SOURCE = "FFDVDGD/OneShade"
TARGET = "Xposed-Modules-Repo/io.github.ffdvdgd.oneshade"
DOCS = {"README.md": "docs/lsposed/README.md", "SUMMARY": "SUMMARY", "LICENSE": "LICENSE"}
GIT_AUTH = ("-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential")


def api(path, method="GET", data=None, missing_ok=False):
    command = ["gh", "api", path, "--method", method]
    if data is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, input=json.dumps(data) if data is not None else None,
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode:
        if missing_ok and result.stdout and json.loads(result.stdout).get("status") == "404":
            return None
        raise RuntimeError(result.stderr.strip() or f"GitHub API request failed: {path}")
    return json.loads(result.stdout) if result.stdout.strip() else None


def sync_docs(root, work):
    repository = work / "repository"
    subprocess.run(["git", *GIT_AUTH, "clone", "--depth=1",
                    f"https://github.com/{TARGET}.git", str(repository)], check=True)
    for name, source in DOCS.items():
        shutil.copyfile(root / source, repository / name)
    git = ["git", "-C", str(repository)]
    subprocess.run([*git, "add", "--", *DOCS], check=True)
    changes = subprocess.run([*git, "diff", "--cached", "--quiet"], check=False)
    if changes.returncode == 0:
        subprocess.run(["git", *GIT_AUTH, "-C", str(repository), "push", "--dry-run",
                        "origin", "HEAD:main"], check=True)
        print("目录仓库写权限检查通过；说明文件已是最新，无需提交。", flush=True)
        return
    if changes.returncode != 1:
        raise RuntimeError("Cannot inspect staged documentation changes")
    subprocess.run([*git, "-c", "user.name=github-actions[bot]",
                    "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
                    "commit", "-m", "docs: 同步模块说明"], check=True)
    subprocess.run(["git", *GIT_AUTH, "-C", str(repository), "push", "origin", "HEAD:main"], check=True)


def file_digest(path):
    with path.open("rb") as stream:
        return "sha256:" + hashlib.file_digest(stream, "sha256").hexdigest()


def asset_digest(asset, work):
    if asset.get("digest"):
        return asset["digest"]
    # Older GitHub assets may not have a server-provided digest.
    path = work / f"asset-{asset['id']}"
    with path.open("wb") as stream:
        subprocess.run(["gh", "api", f"repos/{TARGET}/releases/assets/{asset['id']}",
                        "-H", "Accept: application/octet-stream"], stdout=stream, check=True)
    return file_digest(path)


def sync_release(tag, work):
    endpoint = "latest" if tag == "latest" else f"tags/{quote(tag, safe='')}"
    source = api(f"repos/{SOURCE}/releases/{endpoint}")
    source_tag = source["tag_name"]
    if source["draft"]:
        raise RuntimeError("Only published source releases can be mirrored")
    config = api(f"repos/{SOURCE}/contents/app/build.gradle?ref={quote(source_tag, safe='')}")
    gradle = base64.b64decode(config["content"]).decode("utf-8")
    version = dict(re.findall(
        r"^\s*(applicationId|versionCode|versionName)\s+['\"]?([\w.+-]+)['\"]?\s*$",
        gradle, re.MULTILINE))
    if (version.get("applicationId") != "io.github.ffdvdgd.oneshade"
            or not version.get("versionCode", "").isdigit()
            or source_tag != f"v{version.get('versionName')}"):
        raise RuntimeError("Source tag, package and tagged app/build.gradle do not match")
    tag = f"{int(version['versionCode'])}-{version['versionName']}"
    assets = [asset for asset in source["assets"]
              if asset["name"].endswith(".apk") or asset["name"] == "SHA256SUMS"]
    if (not any(asset["name"].endswith(".apk") for asset in assets)
            or not any(asset["name"] == "SHA256SUMS" for asset in assets)
            or any(asset["state"] != "uploaded" for asset in assets)):
        raise RuntimeError("源 Release 必须包含已上传完成的 APK 和 SHA256SUMS。")

    downloads = work / "assets"
    downloads.mkdir()
    subprocess.run(["gh", "release", "download", source_tag, "--repo", SOURCE,
                    "--pattern", "*.apk", "--pattern", "SHA256SUMS",
                    "--dir", str(downloads)], check=True)
    digests = {}
    for asset in assets:
        digest = file_digest(downloads / asset["name"])
        if asset.get("digest") and asset["digest"] != digest:
            raise RuntimeError(f"Source asset checksum mismatch: {asset['name']}")
        digests[asset["name"]] = digest

    checksums = {}
    for line in (downloads / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *](.+)", line)
        if not match or match[2] in checksums:
            raise RuntimeError("Invalid SHA256SUMS")
        checksums[match[2]] = "sha256:" + match[1].lower()
    for asset in assets:
        if asset["name"].endswith(".apk") and checksums.get(asset["name"]) != digests[asset["name"]]:
            raise RuntimeError(f"SHA256SUMS does not match APK: {asset['name']}")

    path = f"repos/{TARGET}/releases/tags/{quote(tag, safe='')}"
    target = api(path, missing_ok=True)
    if target is None:
        # REST's tag lookup excludes drafts, even for a writer.
        drafts = []
        page = 1
        while True:
            releases = api(f"repos/{TARGET}/releases?per_page=100&page={page}")
            drafts.extend(release for release in releases if release["tag_name"] == tag)
            if len(releases) < 100:
                break
            page += 1
        if len(drafts) > 1:
            raise RuntimeError(f"Multiple target releases match {tag}; refusing to choose one")
        target = drafts[0] if drafts else None
    metadata = {"name": tag.split("-", 1)[1], "body": source["body"] or "",
                "prerelease": source["prerelease"]}
    changed = False
    if target is None:
        target = api(f"repos/{TARGET}/releases", "POST",
                     {**metadata, "tag_name": tag, "target_commitish": "main", "draft": True})
    existing = {asset["name"]: asset for asset in target["assets"]}
    # Check all published assets before uploading or changing anything in this release.
    for asset in assets:
        old = existing.get(asset["name"])
        if (old and not target["draft"]
                and (old["state"] != "uploaded" or asset_digest(old, work) != digests[asset["name"]])):
            raise RuntimeError(f"已发布的 {tag}/{asset['name']} 与源文件不同；不会覆盖，请发布新版本。")
    for asset in assets:
        name = asset["name"]
        old = existing.get(name)
        if old and (old["state"] != "uploaded" or asset_digest(old, work) != digests[name]):
            api(f"repos/{TARGET}/releases/assets/{old['id']}", "DELETE")
            old = None
        if old is None:
            subprocess.run(["gh", "release", "upload", tag, str(downloads / name),
                            "--repo", TARGET], check=True)
            changed = True

    verified = api(f"repos/{TARGET}/releases/{target['id']}")
    uploaded = {asset["name"]: asset for asset in verified["assets"]}
    for asset in assets:
        name = asset["name"]
        if (name not in uploaded or uploaded[name]["state"] != "uploaded"
                or asset_digest(uploaded[name], work) != digests[name]):
            raise RuntimeError(f"Target asset checksum mismatch: {name}")
    if target["draft"] or changed or any(target.get(key) != value for key, value in metadata.items()):
        latest = api(f"repos/{SOURCE}/releases/latest")["tag_name"]
        api(f"repos/{TARGET}/releases/{target['id']}", "PATCH",
            {**metadata, "draft": False,
             "make_latest": "true" if source_tag == latest and not source["prerelease"] else "false"})
        print(f"已同步 Release {source_tag} → {tag}，APK 和校验文件一致。", flush=True)
    else:
        print(f"Release {tag} 已是最新，未修改已发布内容。", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", default=os.environ.get("RELEASE_TAG"),
                        help="Release tag, or latest; omit to sync documentation only")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    scratch = Path(os.environ.get("RUNNER_TEMP", root / ".amp" / "in"))
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="module-repo-sync-", dir=scratch) as directory:
        work = Path(directory)
        sync_docs(root, work)
        if args.release:
            sync_release(args.release, work)


if __name__ == "__main__":
    main()

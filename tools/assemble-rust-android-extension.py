#!/usr/bin/env python3
"""Add the aarch64-linux-android target to an existing FoldCode Rust extension.

usage: assemble-rust-android-extension.py <base.fcex> <android-sysroot-dir> <output.fcex>

The base package's runtime payload is extended, not rebuilt: the official
rust-std component for aarch64-linux-android (matching the packaged rustc
exactly) and a few NDK link files are appended to the payload ZIP, then the
manifest, checksum and legal notices are updated and the package is re-zipped.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import urllib.request
import zipfile
from pathlib import Path

TARGET = "aarch64-linux-android"
PAYLOAD = "payload/rust-runtime-arm64.zip"
NOTICES = "licenses/THIRD_PARTY_NOTICES.md"
SOURCES = "licenses/SOURCES.json"
PACKAGE_FILES = ["manifest.json", PAYLOAD, NOTICES, SOURCES]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, destination: Path, expected_sha256: str) -> None:
    print(f"Downloading {url}")
    with urllib.request.urlopen(url) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(destination)
    if actual != expected_sha256:
        raise SystemExit(f"Checksum mismatch for {url}: {actual} != {expected_sha256}")


def packaged_rustc_version(payload: Path) -> str:
    """Reads 'rustc 1.x.y (hash date)' from the compiler library inside the payload."""
    pattern = re.compile(rb"rustc (1\.\d+\.\d+) \([0-9a-f]{9,} \d{4}-\d{2}-\d{2}\)")
    with zipfile.ZipFile(payload) as archive:
        candidates = [
            info for info in archive.infolist()
            if re.search(r"toolchain/lib/librustc_driver-[^/]+\.so$", info.filename)
        ]
        if not candidates:
            raise SystemExit("The runtime payload has no librustc_driver library")
        with archive.open(candidates[0]) as stream:
            tail = b""
            while True:
                block = stream.read(8 << 20)
                if not block:
                    break
                found = pattern.search(tail + block)
                if found:
                    return found.group(1).decode()
                tail = block[-128:]
    raise SystemExit("Could not read the rustc version from the runtime payload")


def fetch_rust_std(version: str, work: Path) -> Path:
    manifest_url = f"https://static.rust-lang.org/dist/channel-rust-{version}.toml"
    print(f"Reading {manifest_url}")
    with urllib.request.urlopen(manifest_url) as response:
        manifest = tomllib.loads(response.read().decode())
    entry = manifest["pkg"]["rust-std"]["target"][TARGET]
    if not entry.get("available"):
        raise SystemExit(f"rust-std for {TARGET} is not available for Rust {version}")
    archive = work / "rust-std.tar.xz"
    download(entry["xz_url"], archive, entry["xz_hash"])
    extracted = work / "rust-std"
    extracted.mkdir()
    with tarfile.open(archive) as bundle:
        bundle.extractall(extracted, filter="data")
    components = [path for path in extracted.iterdir() if path.is_dir()]
    if len(components) != 1:
        raise SystemExit("Unexpected rust-std archive layout")
    installed = next(components[0].glob(f"rust-std-{TARGET}/lib/rustlib/{TARGET}"), None)
    if installed is None or not (installed / "lib").is_dir():
        raise SystemExit("rust-std archive has no target libraries")
    return installed


def main() -> None:
    base, sysroot, output = (Path(value).resolve() for value in sys.argv[1:4])
    for required in ("libc.so", "crtbegin_so.o", "libunwind.a", "libclang_rt.builtins-aarch64-android.a"):
        if not (sysroot / required).is_file():
            raise SystemExit(f"{sysroot} is missing {required}")

    with tempfile.TemporaryDirectory(prefix="foldcode-rust-android.") as temporary:
        work = Path(temporary)
        package = work / "package"
        with zipfile.ZipFile(base) as archive:
            names = set(archive.namelist())
            missing = {name for name in PACKAGE_FILES if name not in names}
            if missing:
                raise SystemExit(f"{base} is missing {', '.join(sorted(missing))}")
            archive.extractall(package)

        manifest = json.loads((package / "manifest.json").read_text())
        if manifest["id"] != "dev.foldcode.rust":
            raise SystemExit("The base package is not the Rust extension")
        payload = package / PAYLOAD

        version = packaged_rustc_version(payload)
        print(f"Packaged rustc is {version}")
        rust_std = fetch_rust_std(version, work)

        stage = work / "stage"
        shutil.copytree(rust_std, stage / "toolchain/lib/rustlib" / TARGET)
        shutil.copytree(sysroot, stage / "toolchain/android-sysroot")
        with zipfile.ZipFile(payload) as archive:
            existing = set(archive.namelist())
        if any(name.startswith(f"toolchain/lib/rustlib/{TARGET}/") for name in existing):
            raise SystemExit("The base package already contains the Android target")
        subprocess.run(["zip", "-qry", "-g", str(payload), "toolchain"], cwd=stage, check=True)

        base_version = manifest["version"]
        build = int(base_version.split("+")[1]) if "+" in base_version else 0
        manifest["version"] = f"{base_version.split('+')[0]}+{build + 1}"
        manifest["description"] = manifest["description"].rstrip(".") + " and Android ARM64 (APK export) targets."
        provides = manifest.setdefault("provides", [])
        if f"target:{TARGET}" not in provides:
            provides.append(f"target:{TARGET}")
        manifest["payloads"] = {"rust-runtime-arm64.zip": sha256(payload)}
        (package / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

        sources = json.loads((package / SOURCES).read_text())
        sources["components"] += [
            {
                "name": f"rust-std ({TARGET})",
                "version": version,
                "license": "MIT OR Apache-2.0",
                "upstream": f"https://static.rust-lang.org/dist/channel-rust-{version}.toml",
            },
            {
                "name": "Android NDK link files (bionic libc/libm/libdl/liblog/libandroid stubs, crt objects, libunwind, compiler-rt builtins)",
                "version": "r27 (27.2.12479018)",
                "license": "Apache-2.0 WITH LLVM-exception; BSD-style (bionic)",
                "upstream": "https://developer.android.com/ndk/downloads",
            },
        ]
        (package / SOURCES).write_text(json.dumps(sources, indent=2) + "\n")
        notices = (package / NOTICES).read_text().rstrip() + f"""

## Android target (APK export)

- **rust-std for {TARGET}** {version}: standard library for Android ARM64, distributed
  by the Rust project under MIT OR Apache-2.0. https://www.rust-lang.org/policies/licenses
- **Android NDK r27 link files**: the NDK is licensed under Apache-2.0; bionic is under
  BSD-style licenses, and the LLVM runtime libraries are under Apache-2.0 with LLVM
  exceptions. https://developer.android.com/ndk/downloads
"""
        (package / NOTICES).write_text(notices)

        output.parent.mkdir(parents=True, exist_ok=True)
        output.unlink(missing_ok=True)
        # Stored, not deflated: the payload is already compressed, and the host
        # reads entries in this order. Directories must not be listed.
        subprocess.run(["zip", "-0", "-q", str(output), *PACKAGE_FILES], cwd=package, check=True)

    print(f"Wrote {output} ({output.stat().st_size / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()

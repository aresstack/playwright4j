"""Apply playwright4j's two WebDriver BiDi fixes to a Camoufox installation, without rebuilding it.

Released Camoufox cannot open windows over WebDriver BiDi and never answers a tab switch (see
docs/camoufox.md). Both fixes change only Firefox's Remote Agent JavaScript inside omni.ja; pages
observe no difference. They are the hunks of patches/webdriver-bidi-app-name.patch and
patches/webdriver-bidi-force-active.patch next to this script (proposed upstream as
daijro/camoufox#952).

    python hotpatch_omni.py --install <new dir>    download the pinned Camoufox release for this OS,
                                                   check its SHA-256, unpack and patch it
        --release <version>                        another release listed in RELEASES
        --archive <zip>                            an archive downloaded before, instead of downloading
    python hotpatch_omni.py <camoufox dir>         patch an unpacked Camoufox (keeps omni.ja.orig)
    python hotpatch_omni.py --check <camoufox dir> exit 0 only if both fixes are in place

Patching is idempotent. Only files whose SHA-256 is listed in KNOWN_FILES are changed, and the
result must match the listed hash; --unverified lifts that check for builds not listed here.
Close every Camoufox started from the folder first, and start the patched browser with a fresh
profile or once with -purgecaches.
"""
import argparse
import hashlib
import os
import platform
import shutil
import sys
import tempfile
import urllib.request
import zipfile

FIXES = {
    "window": (
        "chrome/remote/content/shared/WindowManager.sys.mjs",
        '    switch (lazy.AppInfo.name) {\n      case "Firefox": {\n',
        '    switch (lazy.AppInfo.name) {\n      // Camoufox is Firefox under another application name.\n'
        '      case "Camoufox":\n      case "Firefox": {\n'),
    "visibility": (
        "chrome/remote/content/webdriver-bidi/modules/windowglobal/browsingContext.sys.mjs",
        '      return;\n    }\n\n    try {\n      // Otherwise, wait for the next visibilitychange event.\n',
        '      return;\n    }\n\n    if (value === "hidden" && win.docShell?.forceActiveState) {\n'
        '      // Camoufox: Juggler forces the docShell active, so this document stays\n'
        '      // "visible" while in the background and no visibilitychange will come.\n'
        '      // Do not wait for it; the page-visible state is left unchanged.\n'
        '      return;\n    }\n\n    try {\n      // Otherwise, wait for the next visibilitychange event.\n'),
}

# SHA-256 of each changed file: as released -> with the fix. Identical in the Windows and Linux builds.
KNOWN_FILES = {
    "window": {
        # 156.0.1-beta.36
        "6a4a8106b53909e93fecb0ae5c4505aa99325fd35b273487f61ae8d8b6b44fb6":
            "047cca48c67255dc76523faa054487a641d1103e188c62ed2b06cd7756b0d46b",
        # 158.0-beta.40
        "3fc4ff2e3585b173ca99c04895aa1616222eb785cd5aa3a14c07a3d7209719c1":
            "42df8aeb4f98f33a9076f6178435700ecd9f05d5029161a4bdaa2f6ed3c019ec",
    },
    "visibility": {
        # 156.0.1-beta.36 and 158.0-beta.40
        "26b244cc760a7dd8197566c90f6fdc9a4dd264e651d7bfb7fc3c2febdbc3bdd2":
            "4202f76e04e88059c1e6b9797228f4a04f9ed1d210984d5818f00ccc790df460",
    },
}

# SHA-256 of the release archives from https://github.com/daijro/camoufox/releases
# The default is the browser the camoufox launcher 0.5.8 pairs with Playwright 1.62.
DEFAULT_RELEASE = "156.0.1-beta.36"
RELEASES = {
    "156.0.1-beta.36": {
        "win.x86_64": "096fd4832a7a32f2aa7f7bdd07fe6253dda296cbe783acd7bc2c86cf5ae62031",
        "lin.x86_64": "72637885ee67c93466ad377831c647192f8f08fb5c6fd9279de1320b49dd2b53",
    },
    "158.0-beta.40": {
        "win.x86_64": "2d3e33207c2d5853e817e8317f0e33124ffb7054bd5102c8b84546e3d15a6447",
        "lin.x86_64": "2ead498d4765394addd3278d6a663962f3f99b189a99d3f966de56656cefc823",
    },
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def omni_path(root):
    omni = os.path.join(root, "omni.ja")
    if not os.path.isfile(omni):
        sys.exit(f"no omni.ja in {root}: pass the folder that holds the Camoufox executable")
    return omni


def state(fix, data, unverified):
    """'fixed' or 'released' for a file this script knows (or any file with --unverified), else exits."""
    _, old, new = FIXES[fix]
    text, digest = data.decode("utf-8"), sha256(data)
    known = KNOWN_FILES[fix]
    if new in text:
        if digest in known.values() or unverified:
            return "fixed"
        sys.exit(f"{fix}: the fix is present but the file is not one this script produced ({digest})")
    if text.count(old) != 1:
        sys.exit(f"{fix}: the text to replace is not in this Camoufox build; the fix needs updating")
    if digest not in known and not unverified:
        sys.exit(f"{fix}: unknown Camoufox build ({digest}); add it to KNOWN_FILES after checking, or use --unverified")
    return "released"


def patch(root, fixes, unverified):
    omni = omni_path(root)
    with zipfile.ZipFile(omni) as src:
        todo = {}
        for fix in fixes:
            name, old, new = FIXES[fix]
            data = src.read(name)
            if state(fix, data, unverified) == "fixed":
                print(f"{fix}: already in place ({sha256(data)})")
                continue
            fixed = data.decode("utf-8").replace(old, new).encode("utf-8")
            expected = KNOWN_FILES[fix].get(sha256(data))
            if expected and sha256(fixed) != expected:
                sys.exit(f"{fix}: result {sha256(fixed)} differs from the expected {expected}")
            todo[name] = (fix, fixed)
        if not todo:
            return
        # Back up only an omni.ja without any fix, so omni.ja.orig is always the released file.
        pristine = all(FIXES[fix][2] not in src.read(FIXES[fix][0]).decode("utf-8") for fix in FIXES)
        tmp = omni + ".tmp"
        with zipfile.ZipFile(tmp, "w") as dst:
            for info in src.infolist():
                dst.writestr(info, todo[info.filename][1] if info.filename in todo else src.read(info.filename))
    if pristine:
        shutil.copy2(omni, omni + ".orig")
    os.replace(tmp, omni)
    for name, (fix, data) in todo.items():
        print(f"{fix}: patched {name} ({sha256(data)})")


def check(root, fixes, unverified):
    with zipfile.ZipFile(omni_path(root)) as src:
        missing = [fix for fix in fixes if state(fix, src.read(FIXES[fix][0]), unverified) != "fixed"]
    for fix in fixes:
        print(f"{fix}: {'missing' if fix in missing else 'in place'}")
    if missing:
        sys.exit(1)


def install(root, release, archive):
    """Unpack a pinned Camoufox archive (downloaded, or one downloaded before) and patch it."""
    if os.path.exists(root) and os.listdir(root):
        sys.exit(f"{root} is not empty")
    os.makedirs(root, exist_ok=True)
    # Download into the new folder, so nothing is written elsewhere and one drive needs the space.
    with tempfile.TemporaryDirectory(dir=root) as tmp:
        if archive is None:
            system = {"win32": "win", "linux": "lin"}.get(sys.platform)
            if system is None or platform.machine().lower() not in ("x86_64", "amd64"):
                sys.exit(f"no pinned Camoufox archive for {sys.platform} {platform.machine()}")
            archive = os.path.join(tmp, "camoufox.zip")
            url = (f"https://github.com/daijro/camoufox/releases/download/v{release}/"
                   f"camoufox-{release}-{system}.x86_64.zip")
            print(f"downloading {url}")
            with urllib.request.urlopen(url, timeout=60) as response, open(archive, "wb") as out:
                shutil.copyfileobj(response, out, 1 << 20)
        digest = hashlib.sha256()
        with open(archive, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                digest.update(chunk)
        digest = digest.hexdigest()
        pinned = {sha: (name, target) for name, targets in RELEASES.items() for target, sha in targets.items()}
        if digest not in pinned:
            sys.exit(f"archive SHA-256 {digest} is not a pinned Camoufox release")
        release, target = pinned[digest]
        with zipfile.ZipFile(archive) as zf:
            for info in zf.infolist():
                path = zf.extract(info, root)
                mode = info.external_attr >> 16
                if mode and os.name == "posix":
                    os.chmod(path, mode & 0o7777)
    patch(root, list(FIXES), unverified=False)
    executable = "camoufox.exe" if target.startswith("win") else "camoufox-bin"
    print(f"Camoufox {release} ({target}) with the fixes: {os.path.join(root, executable)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dir", help="the Camoufox folder (with --install: a new or empty folder)")
    parser.add_argument("fixes", nargs="*", help="window and/or visibility (default: both)")
    parser.add_argument("--check", action="store_true", help="only check that the fixes are in place")
    parser.add_argument("--install", action="store_true", help="download, verify, unpack and patch a release")
    parser.add_argument("--release", default=DEFAULT_RELEASE, choices=list(RELEASES),
                        help=f"with --install: the release to download (default {DEFAULT_RELEASE})")
    parser.add_argument("--archive", help="with --install: an archive downloaded before")
    parser.add_argument("--unverified", action="store_true", help="also patch builds whose files are not listed")
    args = parser.parse_args()
    if args.install:
        if args.fixes or args.check or args.unverified:
            parser.error("--install applies both fixes to a pinned release")
        install(args.dir, args.release, args.archive)
        return
    unknown = [fix for fix in args.fixes if fix not in FIXES]
    if unknown:
        parser.error(f"unknown fix {unknown}; choose from {list(FIXES)}")
    (check if args.check else patch)(args.dir, args.fixes or list(FIXES), args.unverified)


if __name__ == "__main__":
    main()

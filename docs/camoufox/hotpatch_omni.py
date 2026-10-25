"""Apply the Camoufox WebDriver BiDi fixes to an installed Camoufox omni.ja (no rebuild).

Usage: python hotpatch_omni.py <camoufox install dir> window visibility [juggler-gate]
Keeps omni.ja.orig next to omni.ja. Start the browser once with -purgecaches afterwards.
"""
import os, shutil, sys, zipfile

WINDOW = ("chrome/remote/content/shared/WindowManager.sys.mjs",
    '    switch (lazy.AppInfo.name) {\n      case "Firefox": {\n',
    '    switch (lazy.AppInfo.name) {\n      // Camoufox is Firefox under another application name.\n      case "Camoufox":\n      case "Firefox": {\n')

VISIBILITY = ("chrome/remote/content/webdriver-bidi/modules/windowglobal/browsingContext.sys.mjs",
    '      return;\n    }\n\n    try {\n      // Otherwise, wait for the next visibilitychange event.\n',
    '      return;\n    }\n\n    if (value === "hidden" && win.docShell?.forceActiveState) {\n'
    '      // Camoufox: Juggler forces the docShell active, so this document stays\n'
    '      // "visible" while in the background and no visibilitychange will come.\n'
    '      // Do not wait for it; the page-visible state is left unchanged.\n'
    '      return;\n    }\n\n    try {\n      // Otherwise, wait for the next visibilitychange event.\n')

JUGGLER = "chrome/juggler/content/components/Juggler.js"
GATE = [
    ("// Register JSWindowActors that will be instantiated for each frame.\nActorManagerParent.addJSWindowActors({",
     "// Register JSWindowActors that will be instantiated for each frame.\n// Only when Juggler is enabled (--juggler-pipe), see registerJugglerActors().\nconst registerJugglerActors = () => ActorManagerParent.addJSWindowActors({"),
    ("        if (!jugglerPipeFlag)\n          return;\n",
     "        if (!jugglerPipeFlag)\n          return;\n        registerJugglerActors();\n"),
]

def main():
    root, fixes = sys.argv[1], set(sys.argv[2:])
    omni = os.path.join(root, "omni.ja")
    orig = omni + ".orig"
    if not os.path.exists(orig):
        shutil.copy2(omni, orig)
    edits = {}
    if "window" in fixes: edits.setdefault(WINDOW[0], []).append(WINDOW[1:])
    if "visibility" in fixes: edits.setdefault(VISIBILITY[0], []).append(VISIBILITY[1:])
    if "juggler-gate" in fixes: edits.setdefault(JUGGLER, []).extend(GATE)
    tmp = omni + ".tmp"
    with zipfile.ZipFile(orig) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            for old, new in edits.pop(info.filename, []):
                text = data.decode("utf-8")
                if text.count(old) != 1:
                    sys.exit(f"{info.filename}: expected text not found exactly once")
                data = text.replace(old, new).encode("utf-8")
                print("patched", info.filename)
            dst.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
    if edits:
        sys.exit(f"files not found in omni.ja: {list(edits)}")
    os.replace(tmp, omni)

main()

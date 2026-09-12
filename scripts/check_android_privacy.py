"""Static privacy checks for the Android module; exits non-zero on regressions."""
from pathlib import Path
import sys
import argparse

ROOT = Path(__file__).resolve().parents[1] / "android"
files = [p for p in ROOT.rglob("*") if p.is_file() and p.suffix in {".xml", ".kt", ".kts"}]
text = "\n".join(p.read_text(encoding="utf-8") for p in files)
forbidden = ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE")
errors = [f"forbidden permission: {item}" for item in forbidden if item in text]
if "OPENAI_API_KEY" in text:
    errors.append("OPENAI_API_KEY appears under android/")
release_manifest = ROOT / "app/src/main/AndroidManifest.xml"
if "usesCleartextTraffic=\"true\"" not in release_manifest.read_text(encoding="utf-8"):
    errors.append("main manifest must explicitly allow user-configured HTTP backends")
required_permissions = {
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    # Calendar access is requested only from the explicit Actions flow.
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
}
main_manifest = release_manifest.read_text(encoding="utf-8")
for permission in sorted(required_permissions):
    if f'android:name="{permission}"' not in main_manifest:
        errors.append(f"required permission missing from main manifest: {permission}")

parser = argparse.ArgumentParser()
parser.add_argument("--require-merged", action="store_true")
args = parser.parse_args()
merged_candidates = list((ROOT / "app/build/intermediates/merged_manifests/debug").rglob("AndroidManifest.xml"))
if args.require_merged and not merged_candidates:
    errors.append("merged debug manifest not found; run the debug Gradle build first")
for merged_manifest in merged_candidates:
    merged_text = merged_manifest.read_text(encoding="utf-8")
    for permission in sorted(required_permissions):
        if f'android:name="{permission}"' not in merged_text:
            errors.append(f"required permission missing from merged debug manifest: {permission}")
if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("Android privacy checks passed")

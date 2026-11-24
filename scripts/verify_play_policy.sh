#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
report=$(mktemp)
trap 'rm -f "$report"' EXIT

./gradlew --console=plain :app:dependencyInsight --dependency com.android.billingclient \
  --configuration releaseRuntimeClasspath :app:processReleaseManifest > "$report"
grep -q 'No dependencies matching given input were found' "$report"
if grep -q 'com\.android\.billingclient:' "$report"; then
  echo 'Release dependencies contain Play Billing' >&2
  exit 1
fi

python3 - "$@" <<'PY'
import argparse
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile

args = argparse.ArgumentParser()
args.add_argument('--require-bundle', action='store_true')
settings = args.parse_args()
manifest = Path('app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml')
sdk = ElementTree.parse(manifest).getroot().find('uses-sdk')
if sdk is None or sdk.get('{http://schemas.android.com/apk/res/android}targetSdkVersion') != '36':
    raise SystemExit('Expected target SDK 36 in the merged release manifest')

metadata = ('com.android.vending.BILLING', 'com.google.android.play.billingclient.version')
source_markers = (*metadata, 'com.android.billingclient')
def reject_markers(source, data, markers):
    if any(value.encode(enc) in data for value in markers for enc in ('utf-8', 'utf-16le')):
        raise SystemExit(f'Billing marker in {source}')

reject_markers(manifest, manifest.read_bytes(), metadata)
for source in [*Path('app/src').rglob('*'), Path('app/build.gradle.kts'), Path('gradle/libs.versions.toml')]:
    if source.is_file() and source.suffix in ('.kt', '.java', '.xml', '.kts', '.toml'):
        reject_markers(source, source.read_bytes(), source_markers)

bundle = Path('app/build/outputs/bundle/release/app-release.aab')
if bundle.is_file():
    with ZipFile(bundle) as archive:
        dex = [n for n in archive.namelist() if n.startswith('base/dex/') and n.endswith('.dex')]
        if not dex:
            raise SystemExit('Release bundle has no base DEX files')
        for name in dex:
            reject_markers(name, archive.read(name), ('com/android/billingclient', 'BillingClient', 'pro_lifetime', 'pro_monthly'))
        name = 'base/manifest/AndroidManifest.xml'
        reject_markers(name, archive.read(name), metadata)
    print('Scanned the release AAB.')
elif settings.require_bundle:
    raise SystemExit('Missing release AAB')
print('Release dependency, manifest and source checks passed.')
PY

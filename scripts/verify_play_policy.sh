#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

dependency_report="$(mktemp)"
cleanup() {
    rm -f "$dependency_report"
}
trap cleanup EXIT

./gradlew --console=plain \
    :app:dependencyInsight \
    --dependency com.android.billingclient \
    --configuration releaseRuntimeClasspath \
    :app:processReleaseMainManifest | tee "$dependency_report"

if rg -q 'com\.android\.billingclient:' "$dependency_report"; then
    echo "Play policy check failed: the release runtime classpath contains Google Play Billing." >&2
    exit 1
fi

if ! rg -q 'No dependencies matching given input were found' "$dependency_report"; then
    echo "Play policy check failed: unable to prove that Google Play Billing is absent." >&2
    exit 1
fi

manifest_path="$(find app/build/intermediates/merged_manifests/release \
    -name AndroidManifest.xml -type f -print -quit 2>/dev/null || true)"

if [[ -z "$manifest_path" ]]; then
    echo "Play policy check failed: merged release manifest was not generated." >&2
    exit 1
fi

if ! rg -q 'android:targetSdkVersion="36"' "$manifest_path"; then
    echo "Play policy check failed: merged release manifest does not target API 36." >&2
    exit 1
fi

if rg -q 'com\.android\.vending\.BILLING|com\.google\.android\.play\.billingclient\.version' \
    "$manifest_path"; then
    echo "Play policy check failed: merged release manifest contains Play Billing metadata." >&2
    exit 1
fi

if rg -q 'com\.android\.billingclient|com\.android\.vending\.BILLING|com\.google\.android\.play\.billingclient\.version' \
    app/src app/build.gradle.kts gradle/libs.versions.toml; then
    echo "Play policy check failed: source or build configuration references Play Billing." >&2
    exit 1
fi

bundle_path="app/build/outputs/bundle/release/app-release.aab"
if [[ -f "$bundle_path" ]]; then
    while IFS= read -r dex_entry; do
        if unzip -p "$bundle_path" "$dex_entry" | strings | \
            rg -q 'com/android/billingclient|BillingClient|pro_lifetime|pro_monthly'; then
            echo "Play policy check failed: $dex_entry contains a Play Billing marker." >&2
            exit 1
        fi
    done < <(unzip -Z1 "$bundle_path" | rg '^base/dex/.*\.dex$')

    if unzip -p "$bundle_path" base/manifest/AndroidManifest.xml | strings | \
        rg -q 'com\.android\.vending\.BILLING|com\.google\.android\.play\.billingclient\.version'; then
        echo "Play policy check failed: the release bundle manifest contains Play Billing metadata." >&2
        exit 1
    fi

    echo "Release bundle scan passed: no Billing classes, product IDs, permission, or version metadata."
else
    echo "Release bundle scan skipped: run ./gradlew bundleRelease to generate $bundle_path."
fi

echo "Play policy check passed: target API 36 and no Google Play Billing dependency or manifest metadata."

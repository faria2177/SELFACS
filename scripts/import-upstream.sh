#!/usr/bin/env bash
# Import the exact upstream Git tree, retaining submodule gitlinks and executable bits.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
PIN=24cf7fe1133c35ca3e18a8e30128435482e07709
URL=https://github.com/AndroidCSOfficial/android-code-studio.git
if [[ -f .selfacs-upstream ]]; then
  grep -Fxq "$PIN" .selfacs-upstream || { echo "Different upstream already imported"; exit 1; }
  echo "Upstream already imported."
  exit 0
fi
test -z "$(git status --porcelain)" || { echo "Commit or stash changes before importing"; exit 1; }
git fetch --no-tags --depth=1 "$URL" "$PIN"
test "$(git rev-parse FETCH_HEAD)" = "$PIN"
# Overlay the tracked tree, without bringing in unrelated Git history.
git checkout "$PIN" -- .
git show "$PIN:README.md" > UPSTREAM_README.md
git show HEAD:README.md > README.md
mkdir -p docs/selfacs
git show "$PIN:.github/workflows/asm_build.yml" > docs/selfacs/upstream-asm-build.yml
git rm -f --ignore-unmatch .github/workflows/asm_build.yml
# Never reuse upstream signing keys to sign this product.
git rm -f --ignore-unmatch signing/signing-key.jks
python3 - <<'PY'
from pathlib import Path
p = Path("settings.gradle.kts")
s = p.read_text()
assert 'rootProject.name = "AndroidCodeStudio"' in s
p.write_text(s.replace('rootProject.name = "AndroidCodeStudio"', 'rootProject.name = "SELFACS"'))
p = Path("core/app/src/main/res/values/strings.xml")
s = p.read_text()
old = '<string name="app_name" translatable="false">Android Code Studio</string>'
assert old in s
p.write_text(s.replace(old, '<string name="app_name" translatable="false">SELFACS</string>'))
p = Path("core/app/build.gradle.kts")
s = p.read_text()
old = 'debug {\n      signingConfig = signingConfigs.getByName("custom")\n    }'
assert old in s
p.write_text(s.replace(old, 'debug {\n      signingConfig = signingConfigs.getByName("debug")\n    }').replace('val appName = "android-code-studio"', 'val appName = "selfacs"'))
p = Path("README.md")
s = p.read_text().replace("Bootstrap stage. The upstream Android source has not yet been imported or built.", "Pinned upstream source imported. APK build and device validation are separate milestones.")
p.write_text(s)
PY
printf '%s\n' "$PIN" > .selfacs-upstream
git add -A
git diff --cached --check -- README.md .selfacs-upstream
git commit -m "Import pinned Android Code Studio source and initialize SELFACS branding"
echo "Import finished; run git submodule update --init --recursive before building."

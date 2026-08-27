#!/usr/bin/env sh
#
# Enforces the upstream-compatibility contract in plans/06-upstream-compatibility.md.
#
# Run it before and after every upstream merge. It compares the working tree against upstream and
# fails if the fork has grown a surface the contract does not describe.
#
#   sh scripts/check-seams.sh [upstream-ref]     # default: upstream/main
#
# Not wired into CI on purpose: .github/workflows/build.yml is an upstream file, so a step there
# would cost a thirteenth seam for a check that only matters locally, at merge time.

set -eu

UPSTREAM="${1:-upstream/main}"
FAILURES=0

# ---------------------------------------------------------------------------------------------
# The contract. plans/06's seam table is the human-readable version of SEAMS; keep them in step.
#
# Three lists, not one: the fork legitimately changes upstream files for three different reasons,
# and only the first is the novel feature. Separating them lets C1 stay strict about that one.
# ---------------------------------------------------------------------------------------------

SEAMS="
domain/src/main/java/tachiyomi/domain/storage/service/StorageManager.kt
app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt
app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt
app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt
app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt
app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt
app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt
app/src/main/java/mihon/app/di/AppGraph.kt
app/src/main/AndroidManifest.xml
i18n/src/commonMain/moko-resources/base/strings.xml
app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt
app/src/main/java/eu/kanade/domain/source/interactor/GetEnabledSources.kt
"

REBRAND="
app/build.gradle.kts
app/google-services.json
app/src/main/res/drawable/ic_launcher_background.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/drawable/ic_launcher_monochrome.xml
gradle.properties
gradle/build-logic/src/main/kotlin/mihon/gradle/BuildConfig.kt
"

BUILD_ENV="
gradle/libs.versions.toml
settings.gradle.kts
gradle/gradle-daemon-jvm.properties
"

# Fork-owned trees. New files here never conflict, so they are outside the contract entirely.
FORK_TREES='^plans/|^progress/|^scripts/|(^|/)leaf/'

fail() {
    printf '  FAIL  %s\n' "$1"
    FAILURES=$((FAILURES + 1))
}

pass() {
    printf '  ok    %s\n' "$1"
}

# Strips blank lines so the lists above can be readably indented.
listed() {
    printf '%s' "$1" | grep -v '^[[:space:]]*$' || true
}

changed_files() {
    git diff --name-only "$UPSTREAM"
}

if ! git rev-parse --verify --quiet "$UPSTREAM" >/dev/null; then
    printf 'Cannot resolve %s. Run: git fetch upstream\n' "$UPSTREAM" >&2
    exit 2
fi

printf 'Checking the fork surface against %s\n\n' "$UPSTREAM"

# ---------------------------------------------------------------------------------------------
# C1 — every changed upstream file is accounted for
# ---------------------------------------------------------------------------------------------

ALLOWED="$(listed "$SEAMS"; listed "$REBRAND"; listed "$BUILD_ENV")"
UNACCOUNTED="$(changed_files | grep -Ev "$FORK_TREES" | grep -Fxv "$ALLOWED" || true)"

if [ -n "$UNACCOUNTED" ]; then
    fail 'C1 upstream files changed that no list accounts for:'
    printf '%s\n' "$UNACCOUNTED" | sed 's/^/          /'
    printf '        Either the change is wrong, or plans/06 and this script need updating together.\n'
else
    pass 'C1 every changed upstream file is on a list'
fi

# ---------------------------------------------------------------------------------------------
# C2 / C4 — no schema surface (D1). An untouched migrations directory is an untouched
# Database.Schema.version, since SQLDelight derives the version from the highest .sqm.
# ---------------------------------------------------------------------------------------------

SCHEMA="$(git diff --name-only "$UPSTREAM" -- data/src/main/sqldelight/ || true)"
if [ -n "$SCHEMA" ]; then
    fail 'C2 the database schema changed — D1 says never:'
    printf '%s\n' "$SCHEMA" | sed 's/^/          /'
else
    pass 'C2 no .sq or .sqm file differs, so the schema version still tracks upstream'
fi

# ---------------------------------------------------------------------------------------------
# C3 — markers and seams agree in both directions
# ---------------------------------------------------------------------------------------------

MARKED="$(grep -rl '\[recto-leaf\]' --include='*.kt' --include='*.xml' \
    --exclude-dir=build --exclude-dir=.git . 2>/dev/null \
    | sed 's|^\./||' | grep -Ev "$FORK_TREES" | sort || true)"
EXPECTED="$(listed "$SEAMS" | sort)"

STRAY="$(printf '%s' "$MARKED" | grep -Fxv "$EXPECTED" || true)"
UNMARKED="$(printf '%s' "$EXPECTED" | grep -Fxv "$MARKED" || true)"

if [ -n "$STRAY" ]; then
    fail 'C3 [recto-leaf] markers in files that are not seams:'
    printf '%s\n' "$STRAY" | sed 's/^/          /'
fi
if [ -n "$UNMARKED" ]; then
    fail 'C3 seams with no [recto-leaf] marker to explain them:'
    printf '%s\n' "$UNMARKED" | sed 's/^/          /'
fi
if [ -z "$STRAY" ] && [ -z "$UNMARKED" ]; then
    pass 'C3 every seam is marked, and every marker is on a seam'
fi

# ---------------------------------------------------------------------------------------------
# C5 — translations are Weblate's, not ours
# ---------------------------------------------------------------------------------------------

TRANSLATIONS="$(git diff --name-only "$UPSTREAM" -- i18n/src/commonMain/moko-resources/ \
    | grep -v '^i18n/src/commonMain/moko-resources/base/strings.xml$' || true)"
if [ -n "$TRANSLATIONS" ]; then
    fail 'C5 a Weblate-managed translation was edited — it will be overwritten:'
    printf '%s\n' "$TRANSLATIONS" | sed 's/^/          /'
else
    pass 'C5 only base/strings.xml differs under moko-resources'
fi

# ---------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------

printf '\nFork surface against %s:\n' "$UPSTREAM"
for category in SEAMS REBRAND BUILD_ENV; do
    case "$category" in
        SEAMS) list="$SEAMS" ;;
        REBRAND) list="$REBRAND" ;;
        BUILD_ENV) list="$BUILD_ENV" ;;
    esac
    count="$(changed_files | grep -Fxc "$(listed "$list")" 2>/dev/null || true)"
    printf '  %-10s %s of %s files changed\n' \
        "$category" "${count:-0}" "$(listed "$list" | wc -l | tr -d ' ')"
done

printf '\n'
if [ "$FAILURES" -gt 0 ]; then
    printf '%s check(s) failed.\n' "$FAILURES"
    exit 1
fi
printf 'Contract holds.\n'

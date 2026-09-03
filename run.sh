#!/usr/bin/env sh
# Portability escape hatch (NFR-A3): the pipeline runs identically on any
# machine — no GitHub-specific code outside the workflow YAML. This produces
# the same data/ the Actions cron produces.
set -eu
cd "$(dirname "$0")"

mvn -B -f pipeline/pom.xml verify
java -jar pipeline/target/pipeline.jar --pipeline.data-dir=data

# History comes from committed runs (AC-2.1); a local uncommitted refresh
# contributes points only after it is committed and this is re-run.
node tools/build-history.mjs || echo "history rebuild skipped (node or git unavailable)"

echo
echo "data/ refreshed. To build the site: cd web && npm install && npm run build"

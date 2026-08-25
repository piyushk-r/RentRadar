#!/usr/bin/env sh
# Portability escape hatch (NFR-A3): the pipeline runs identically on any
# machine — no GitHub-specific code outside the workflow YAML. This produces
# the same data/ the Actions cron produces.
set -eu
cd "$(dirname "$0")"

mvn -B -f pipeline/pom.xml verify
java -jar pipeline/target/pipeline.jar --pipeline.data-dir=data

echo
echo "data/ refreshed. To build the site: cd web && npm install && npm run build"

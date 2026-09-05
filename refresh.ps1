# Refresh the live site from this machine, end to end.
#
# Why this exists rather than just leaving it to the 12-hour cron: RentoMojo's
# host serves this project fine from a home connection but refuses GitHub's
# runner IPs, so a CI run cannot refresh it (see docs/compliance/rentomojo.md
# and /status). Running here covers all three providers.
#
# The order matters. build-history walks *committed* git history, so the data
# has to be committed before the history is rebuilt — doing it the other way
# round silently rebuilds history without the run you just did. The scrape
# workflow does the same two-step for the same reason.
#
#   .\refresh.ps1            # run, commit, rebuild history, push
#   .\refresh.ps1 -NoPush    # everything except the push
param([switch]$NoPush)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Resolve Maven the same way run.ps1 does: PATH first, then a local install.
$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvnCmd) {
    $mvnBin = Split-Path $mvnCmd.Source
} else {
    $local = Join-Path $env:USERPROFILE "tools\apache-maven-3.9.9\bin"
    if (Test-Path (Join-Path $local "mvn.cmd")) {
        $mvnBin = $local
    } else {
        Write-Error "Maven not found on PATH or at $local."
        exit 1
    }
}
$env:Path = "$mvnBin;$env:Path"

Write-Host "==> building the pipeline (tests included)" -ForegroundColor Cyan
cmd /c "mvn -B -f pipeline/pom.xml verify"
if ($LASTEXITCODE -ne 0) { Write-Error "build failed — not touching data/"; exit $LASTEXITCODE }

Write-Host "==> scraping (all providers, ~30 min)" -ForegroundColor Cyan
java -jar pipeline/target/pipeline.jar --pipeline.data-dir=data
$pipelineExit = $LASTEXITCODE
if ($pipelineExit -ne 0) {
    # A provider failed. Its previous values survive (FR-5.4) and runs.json
    # records the failure — both worth committing, so carry on and report.
    Write-Host "    one or more providers failed; committing what succeeded" -ForegroundColor Yellow
}

git add data
if (git diff --cached --quiet) {
    Write-Host "==> no data changes" -ForegroundColor DarkGray
} else {
    git commit -q -m "data: local refresh $(Get-Date -Format 'yyyy-MM-ddTHH:mmK')"
    Write-Host "==> committed refreshed data" -ForegroundColor Green
}

Write-Host "==> rebuilding price history from git" -ForegroundColor Cyan
node tools/build-history.mjs
if ($LASTEXITCODE -eq 0) {
    git add data/history data/provider-stats.json
    if (-not (git diff --cached --quiet)) {
        git commit -q -m "data: price history rebuilt from git log"
        Write-Host "==> committed history" -ForegroundColor Green
    }
} else {
    Write-Host "    history rebuild skipped" -ForegroundColor Yellow
}

if ($NoPush) {
    Write-Host "==> -NoPush given; review with: git log --oneline -3" -ForegroundColor DarkGray
} else {
    Write-Host "==> pushing (Cloudflare rebuilds on the push)" -ForegroundColor Cyan
    git push
}

Write-Host ""
Write-Host "Done. Site: https://rentradar.verida-healthcare.workers.dev" -ForegroundColor Green
Write-Host "Pipeline health: /status" -ForegroundColor DarkGray
exit $pipelineExit

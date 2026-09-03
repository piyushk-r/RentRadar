# Portability escape hatch (NFR-A3): same pipeline, any machine.
# Runs the tests, then one live pipeline run into data/.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Resolve the Maven bin directory: PATH first, then a local tools install.
$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvnCmd) {
    $mvnBin = Split-Path $mvnCmd.Source
} else {
    $local = Join-Path $env:USERPROFILE "tools\apache-maven-3.9.9\bin"
    if (Test-Path (Join-Path $local "mvn.cmd")) {
        $mvnBin = $local
    } else {
        Write-Error "Maven not found on PATH or at $local. Install Maven (https://maven.apache.org) first."
        exit 1
    }
}

# Invoke via cmd with the bare name (avoids PowerShell/.cmd quoting trouble,
# including spaces in paths like 'C:\Program Files\Maven').
$env:Path = "$mvnBin;$env:Path"
cmd /c "mvn -B -f pipeline/pom.xml verify"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

java -jar pipeline/target/pipeline.jar --pipeline.data-dir=data
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# History comes from committed runs (AC-2.1); a local uncommitted refresh
# contributes points only after it is committed and this is re-run.
node tools/build-history.mjs
if ($LASTEXITCODE -ne 0) { Write-Host "history rebuild skipped (node or git unavailable)" }

Write-Host ""
Write-Host "data/ refreshed. Review with: git diff data/"
Write-Host "To run the site: cd web; npm install; npm run dev"

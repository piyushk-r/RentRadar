# Portability escape hatch (NFR-A3): same pipeline, any machine.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

mvn -B -f pipeline/pom.xml verify
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java -jar pipeline/target/pipeline.jar --pipeline.data-dir=data
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "data/ refreshed. To build the site: cd web; npm install; npm run build"

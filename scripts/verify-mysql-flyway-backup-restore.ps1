[CmdletBinding()]
param(
    [string]$MySqlImage = "mysql:8.0.43"
)

$ErrorActionPreference = "Stop"

function New-HexSecret {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [System.Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Invoke-Native {
    param([string]$Description, [scriptblock]$Command)
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $repositoryRoot "backend"
$mysqlInitPath = Join-Path $repositoryRoot "deployment/mysql-init"
$runId = "flyway-it-$PID"
$containerName = "babelflux-$runId"
$sourceSchema = "babelflux_it_$PID"
$restoreSchema = "babelflux_restore_$PID"
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) $containerName
$backupFile = Join-Path $temporaryDirectory "backup.sql"
$port = Get-FreeLoopbackPort
$rootPassword = New-HexSecret
$appPassword = New-HexSecret
$migratorPassword = New-HexSecret
$proofId = "backup-restore-$PID"
$environmentNames = @(
    "RUN_MYSQL_FLYWAY_IT",
    "TEST_MYSQL_URL",
    "TEST_MYSQL_MIGRATOR_USERNAME",
    "TEST_MYSQL_MIGRATOR_PASSWORD",
    "TEST_MYSQL_APP_USERNAME",
    "TEST_MYSQL_APP_PASSWORD"
)
$previousEnvironment = @{}
$containerStarted = $false

foreach ($name in $environmentNames) {
    $item = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    $previousEnvironment[$name] = if ($null -eq $item) { $null } else { $item.Value }
}

try {
    Invoke-Native "Docker availability check" { docker version --format "{{.Server.Version}}" | Out-Null }
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null

    Invoke-Native "isolated MySQL startup" {
        docker run --detach --rm --name $containerName `
            --publish "127.0.0.1:$port`:3306" `
            --volume "${mysqlInitPath}:/docker-entrypoint-initdb.d:ro" `
            --env "MYSQL_DATABASE=$sourceSchema" `
            --env "MYSQL_ROOT_PASSWORD=$rootPassword" `
            --env "MYSQL_APP_PASSWORD=$appPassword" `
            --env "MYSQL_MIGRATOR_PASSWORD=$migratorPassword" `
            $MySqlImage | Out-Null
    }
    $containerStarted = $true

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    do {
        & docker exec $containerName mysqladmin --protocol=socket -uroot "--password=$rootPassword" ping --silent | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($LASTEXITCODE -ne 0) { throw "isolated MySQL did not become healthy within 90 seconds" }

    $env:RUN_MYSQL_FLYWAY_IT = "true"
    $env:TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$port/$sourceSchema?connectionTimeZone=UTC"
    $env:TEST_MYSQL_MIGRATOR_USERNAME = "babelflux_migrator"
    $env:TEST_MYSQL_MIGRATOR_PASSWORD = $migratorPassword
    $env:TEST_MYSQL_APP_USERNAME = "babelflux_app"
    $env:TEST_MYSQL_APP_PASSWORD = $appPassword

    Push-Location $backendPath
    try {
        Invoke-Native "Flyway isolated MySQL integration test" {
            mvn -B '-Dtest=FlywayMysqlMigrationIntegrationTest' test
        }
    } finally {
        Pop-Location
    }

    Invoke-Native "backup proof insertion" {
        docker exec $containerName mysql --protocol=socket -ubabelflux_app "--password=$appPassword" `
            --database=$sourceSchema --execute "insert into babelflux_session_audit (session_id, status) values ('$proofId', 'backup-proof')" | Out-Null
    }
    & docker exec $containerName sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' |
        Set-Content -LiteralPath $backupFile -Encoding utf8NoBOM
    if ($LASTEXITCODE -ne 0) { throw "MySQL backup export failed with exit code $LASTEXITCODE" }
    if ((Get-Item -LiteralPath $backupFile).Length -le 0) { throw "MySQL backup export produced an empty file" }

    $quotedRestoreSchema = [char]96 + $restoreSchema + [char]96
    Invoke-Native "restore schema creation" {
        docker exec $containerName mysql --protocol=socket -uroot "--password=$rootPassword" `
            --execute "create database $quotedRestoreSchema character set utf8mb4 collate utf8mb4_0900_ai_ci" | Out-Null
    }
    Get-Content -LiteralPath $backupFile -Raw |
        docker exec -i $containerName mysql --protocol=socket -uroot "--password=$rootPassword" --database=$restoreSchema
    if ($LASTEXITCODE -ne 0) { throw "MySQL backup restore failed with exit code $LASTEXITCODE" }

    $restoredProof = (& docker exec $containerName mysql --protocol=socket -uroot "--password=$rootPassword" `
        --skip-column-names --batch --database=$restoreSchema `
        --execute "select count(*) from babelflux_session_audit where session_id='$proofId'").Trim()
    if ($restoredProof -ne "1") { throw "backup restore did not recover the proof row" }
    $migrationRows = (& docker exec $containerName mysql --protocol=socket -uroot "--password=$rootPassword" `
        --skip-column-names --batch --database=$restoreSchema `
        --execute "select count(*) from flyway_schema_history where success=1 and version='1'").Trim()
    if ($migrationRows -ne "1") { throw "backup restore did not recover Flyway V1 history" }

    Write-Output "PASS: isolated Flyway migration, least-privilege application account, backup, and restore verified."
} finally {
    foreach ($name in $environmentNames) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -LiteralPath "Env:$name" -Value $previousEnvironment[$name]
        }
    }
    if ($containerStarted -and $containerName.StartsWith("babelflux-flyway-it-")) {
        docker rm --force $containerName | Out-Null
    }
    if (Test-Path -LiteralPath $temporaryDirectory) {
        $resolvedTemporary = (Resolve-Path -LiteralPath $temporaryDirectory).Path
        $tempRoot = [System.IO.Path]::GetTempPath().TrimEnd('\\')
        if ($resolvedTemporary.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) `
                -and (Split-Path -Leaf $resolvedTemporary).StartsWith("babelflux-flyway-it-")) {
            Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
        }
    }
}

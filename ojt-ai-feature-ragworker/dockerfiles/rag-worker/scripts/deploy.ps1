#!/usr/bin/env pwsh
# PowerShell equivalent of scripts/init_all.sh for Windows hosts.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ComposeFile = Join-Path $ScriptDir '..\compose\docker-compose.dev.yml'
$DockerCmd = 'docker'
$ComposeArgs = @('compose', '-f', $ComposeFile)

$MinioRootUser = if ($env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER } else { 'minioadmin' }
$MinioRootPassword = if ($env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD } else { 'minioadmin' }
$Broker = if ($env:BROKER) { $env:BROKER } else { 'redpanda:9092' }

function Log($msg) { Write-Host "[deploy] $msg" }

function Compose {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Args)
    & $DockerCmd @ComposeArgs @Args
}

function ComposeCp {
    param(
        [string] $Source,
        [string] $Destination
    )
    & $DockerCmd @ComposeArgs 'cp' $Source $Destination
}

function Wait-Postgres {
    Log "Waiting for Postgres..."
    for ($i = 0; $i -lt 30; $i++) {
        Compose exec -T postgres pg_isready -U rag_user -d rag_db | Out-Null
        if ($LASTEXITCODE -eq 0) { Log "Postgres is ready."; return }
        Start-Sleep -Seconds 2
    }
    throw "Postgres is not ready after multiple attempts."
}

function Apply-DbSchema {
    $initDb = Join-Path $ScriptDir 'init_db.sql'
    Log "Applying init_db.sql..."
    ComposeCp $initDb 'postgres:/tmp/init_db.sql'
    Compose exec -T postgres psql -U rag_user -d rag_db -v ON_ERROR_STOP=1 -f /tmp/init_db.sql
    if ($LASTEXITCODE -ne 0) { throw "Failed to apply init_db.sql inside postgres container." }
    Log "Postgres schema applied."
}

function Wait-Minio {
    Log "Waiting for MinIO..."
    for ($i = 0; $i -lt 30; $i++) {
        Compose exec -T mc sh -c "MINIO_ROOT_USER='$MinioRootUser' MINIO_ROOT_PASSWORD='$MinioRootPassword' mc alias set local http://minio:9000 ""$MinioRootUser"" ""$MinioRootPassword"" >/dev/null 2>&1 && mc ls local >/dev/null 2>&1" | Out-Null
        if ($LASTEXITCODE -eq 0) { Log "MinIO is reachable."; return }
        Start-Sleep -Seconds 2
    }
    throw "MinIO is not reachable after multiple attempts."
}

function Run-Minio-Init {
    $initMinio = Join-Path $ScriptDir 'init_minio.sh'
    Log "Running init_minio.sh..."
    ComposeCp $initMinio 'mc:/tmp/init_minio.sh'
    Compose exec -T -e "MINIO_ROOT_USER=$MinioRootUser" -e "MINIO_ROOT_PASSWORD=$MinioRootPassword" mc sh /tmp/init_minio.sh
    if ($LASTEXITCODE -ne 0) { throw "MinIO initialization failed inside mc container." }
    Log "MinIO initialization finished."
}

function Ensure-Rpk-In-KafkaTools {
    Compose exec -T kafka-tools sh -c "command -v rpk >/dev/null 2>&1" | Out-Null
    if ($LASTEXITCODE -eq 0) { return }

    Log "Copying rpk binary from redpanda into kafka-tools..."
    $redpandaId = (Compose ps -q redpanda).Trim()
    $kafkaToolsId = (Compose ps -q kafka-tools).Trim()
    if (-not $redpandaId -or -not $kafkaToolsId) { throw "Unable to resolve container IDs for redpanda or kafka-tools." }

    $tmpFile = [System.IO.Path]::GetTempFileName()
    & $DockerCmd cp "${redpandaId}:/opt/redpanda/libexec/rpk" $tmpFile
    if ($LASTEXITCODE -ne 0) { throw "Failed to copy rpk from redpanda." }
    & $DockerCmd cp $tmpFile "${kafkaToolsId}:/usr/bin/rpk"
    if ($LASTEXITCODE -ne 0) { throw "Failed to copy rpk into kafka-tools." }
    Remove-Item -Force $tmpFile
    Compose exec -T kafka-tools sh -c "chmod +x /usr/bin/rpk" | Out-Null
}

function Wait-Kafka {
    Log "Waiting for Redpanda..."
    for ($i = 0; $i -lt 30; $i++) {
        Compose exec -T kafka-tools sh -c "rpk --brokers $Broker cluster info >/dev/null 2>&1" | Out-Null
        if ($LASTEXITCODE -eq 0) { Log "Redpanda is reachable."; return }
        Start-Sleep -Seconds 2
    }
    throw "Redpanda is not reachable after multiple attempts."
}

function Run-Kafka-Init {
    $initKafka = Join-Path $ScriptDir 'init_kafka.sh'
    Log "Running init_kafka.sh..."
    ComposeCp $initKafka 'kafka-tools:/tmp/init_kafka.sh'
    Compose exec -T -e "BROKER=$Broker" kafka-tools sh /tmp/init_kafka.sh
    if ($LASTEXITCODE -ne 0) { throw "Kafka initialization failed inside kafka-tools container." }
    Log "Kafka initialization finished."
}

function Optional-Qdrant-Check {
    Log "Checking Qdrant health (optional)..."
    Compose exec -T qdrant bash -c "echo > /dev/tcp/localhost/6333" | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Log "Qdrant is reachable."
    } else {
        Log "Qdrant health check skipped/failed (non-blocking)."
    }
}

Log "Ensuring required services are running (without rag-worker)..."
Compose up -d redpanda postgres minio mc kafka-tools qdrant | Out-Null

Wait-Postgres
Apply-DbSchema

Wait-Minio
Run-Minio-Init

Ensure-Rpk-In-KafkaTools
Wait-Kafka
Run-Kafka-Init

Optional-Qdrant-Check

Log "All initialization steps completed."

<#
.SYNOPSIS
  Sobe os servicos do jogo online direto na JVM (sem Docker), um por janela do PowerShell
  (decisao de 17/11/2026, ver DESENVOLVIMENTO-LOCAL.md "Rodar sem Docker").

.DESCRIPTION
  Le online\.env, sobe (ou reusa) o Postgres do Compose so para ter um banco em localhost:5432,
  e abre lobby-service, game-service, economy-service e admin-api com 'gradlew.bat :servico:run',
  cada um na propria janela, mais o admin-web com 'npm run dev'. Pensado para iterar no codigo
  mais rapido (recompila so o que mudou) e para testar com curl/gradlew/admin-web.

  NAO serve para testar pelo app no celular: o app precisa de UM endereco host:porta so
  (o campo "Servidor online" do Perfil), e isso so o proxy 'edge' do Docker Compose oferece
  (porta 8080). Para testar pelo celular, use 'docker compose up --build' mesmo.

.PARAMETER SkipPostgres
  Nao tenta subir nem checar o Postgres (use se voce ja tem um rodando em localhost:5432 por fora
  do Compose, ou quer so reiniciar os servicos sem tocar no banco).

.PARAMETER SkipAdminWeb
  Nao abre o admin-web (npm run dev). Util se voce so quer os 4 servicos Kotlin.

.EXAMPLE
  .\start-local.ps1
.EXAMPLE
  .\start-local.ps1 -SkipPostgres -SkipAdminWeb
#>
param(
    [switch]$SkipPostgres,
    [switch]$SkipAdminWeb
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$envFile = Join-Path $root ".env"

if (-not (Test-Path $envFile)) {
    Write-Error "Nao achei online\.env. Copie online\.env.example para online\.env e preencha os segredos antes de rodar este script."
    exit 1
}

# --- Le o .env (mesmo formato do Compose: KEY=VALUE, '#' comenta, linha vazia ignorada) ---
$envVars = @{}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $envVars[$Matches[1]] = $Matches[2].Trim()
    }
}

function Get-RequiredEnv([string]$name) {
    if (-not $envVars.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($envVars[$name])) {
        Write-Error "online\.env esta sem $name (ou vazio). Preencha antes de continuar (ver online\.env.example)."
        exit 1
    }
    return $envVars[$name]
}

function Get-OptionalEnv([string]$name) {
    if ($envVars.ContainsKey($name)) { return $envVars[$name] }
    return ""
}

$dbUser = Get-RequiredEnv "POSTGRES_USER"
$dbName = Get-RequiredEnv "POSTGRES_DB"
$dbPassword = Get-RequiredEnv "POSTGRES_PASSWORD"
$internalSecret = Get-RequiredEnv "INTERNAL_SECRET"
$adminInternalSecret = Get-RequiredEnv "ADMIN_INTERNAL_SECRET"
$appSigningKey = Get-OptionalEnv "APP_SIGNING_KEY"
$googleClientId = Get-OptionalEnv "GOOGLE_CLIENT_ID"
$adminSeedEmail = Get-OptionalEnv "ADMIN_SEED_EMAIL"
$adminSeedPassword = Get-OptionalEnv "ADMIN_SEED_PASSWORD"

$dbUrl = "jdbc:postgresql://localhost:5432/$dbName"

function Test-Port([int]$port) {
    return (Test-NetConnection -ComputerName localhost -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

# --- Postgres: reusa se ja tiver um em localhost:5432, senao sobe so o container do Compose ---
if (-not $SkipPostgres) {
    if (Test-Port 5432) {
        Write-Host "Postgres ja responde em localhost:5432. Usando o que ja esta rodando." -ForegroundColor Green
    }
    else {
        Write-Host "Nenhum Postgres em localhost:5432. Tentando 'docker compose up -d postgres'..." -ForegroundColor Yellow
        docker info *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Docker Desktop nao esta rodando e nao ha Postgres em localhost:5432. Abra o Docker Desktop (o Postgres do Compose serve de banco pra este modo tambem) ou aponte para o seu proprio Postgres e rode de novo com -SkipPostgres."
            exit 1
        }
        Push-Location $root
        try { docker compose up -d postgres } finally { Pop-Location }

        Write-Host "Esperando o Postgres responder..." -NoNewline
        $tries = 0
        while (-not (Test-Port 5432)) {
            Start-Sleep -Seconds 1
            $tries++
            Write-Host "." -NoNewline
            if ($tries -ge 30) {
                Write-Host ""
                Write-Error "Postgres nao respondeu em 30s. Veja 'docker compose logs postgres' em online\."
                exit 1
            }
        }
        Write-Host " ok" -ForegroundColor Green
    }
}

# --- Abre um servico numa janela nova do PowerShell, com as variaveis de ambiente dele ---
function Start-ServiceWindow {
    param(
        [string]$Title,
        [string]$WorkDir,
        [hashtable]$EnvMap,
        [string]$Command
    )

    $setEnv = ($EnvMap.GetEnumerator() | Where-Object { $_.Value -ne "" } | ForEach-Object {
            $escaped = $_.Value -replace "'", "''"
            "`$env:$($_.Key) = '$escaped'"
        }) -join "; "

    $script = "`$host.ui.RawUI.WindowTitle = '$Title (Ctrl+C para parar)'; $setEnv; $Command"
    Start-Process powershell -WorkingDirectory $WorkDir -ArgumentList "-NoExit", "-Command", $script | Out-Null
}

$servicePorts = @{ "economy-service" = 8083; "lobby-service" = 8081; "game-service" = 8082; "admin-api" = 8084 }
foreach ($svc in $servicePorts.Keys) {
    if (Test-Port $servicePorts[$svc]) {
        Write-Warning "Porta $($servicePorts[$svc]) ($svc) ja esta em uso - pode ser uma instancia anterior deste script ainda aberta."
    }
}

Write-Host "Abrindo economy-service (porta 8083)..."
Start-ServiceWindow -Title "economy-service" -WorkDir $root -Command ".\gradlew.bat :economy-service:run" -EnvMap @{
    PORT                  = "8083"
    DB_URL                = $dbUrl
    DB_USER               = $dbUser
    DB_PASSWORD           = $dbPassword
    INTERNAL_SECRET       = $internalSecret
    ADMIN_INTERNAL_SECRET = $adminInternalSecret
    LOBBY_URL             = "http://localhost:8081"
}

# lobby/admin-api dependem do economy-service pra alguma coisa (saldo, auditoria); da um tempinho pra ele subir primeiro
Start-Sleep -Seconds 2

Write-Host "Abrindo lobby-service (porta 8081)..."
Start-ServiceWindow -Title "lobby-service" -WorkDir $root -Command ".\gradlew.bat :lobby-service:run" -EnvMap @{
    PORT                  = "8081"
    DB_URL                = $dbUrl
    DB_USER               = $dbUser
    DB_PASSWORD           = $dbPassword
    INTERNAL_SECRET       = $internalSecret
    ADMIN_INTERNAL_SECRET = $adminInternalSecret
    GAME_SERVICE_URL      = "http://localhost:8082"
    ECONOMY_URL           = "http://localhost:8083"
    APP_SIGNING_KEY       = $appSigningKey
    GOOGLE_CLIENT_ID      = $googleClientId
}

Write-Host "Abrindo game-service (porta 8082)..."
Start-ServiceWindow -Title "game-service" -WorkDir $root -Command ".\gradlew.bat :game-service:run" -EnvMap @{
    PORT            = "8082"
    APP_SIGNING_KEY = $appSigningKey
}

Write-Host "Abrindo admin-api (porta 8084)..."
Start-ServiceWindow -Title "admin-api" -WorkDir $root -Command ".\gradlew.bat :admin-api:run" -EnvMap @{
    PORT                     = "8084"
    DB_URL                   = $dbUrl
    DB_USER                  = $dbUser
    DB_PASSWORD              = $dbPassword
    ADMIN_INTERNAL_SECRET    = $adminInternalSecret
    LOBBY_SERVICE_URL        = "http://localhost:8081"
    ECONOMY_SERVICE_URL      = "http://localhost:8083"
    LOBBY_SERVICE_HEALTH_URL = "http://localhost:8081/health"
    GAME_SERVICE_HEALTH_URL  = "http://localhost:8082/health"
    ECONOMY_SERVICE_HEALTH_URL = "http://localhost:8083/health"
    ADMIN_SEED_EMAIL         = $adminSeedEmail
    ADMIN_SEED_PASSWORD      = $adminSeedPassword
}

if (-not $SkipAdminWeb) {
    Write-Host "Abrindo admin-web (porta 5173)..."
    Start-ServiceWindow -Title "admin-web" -WorkDir (Join-Path $root "admin-web") -Command "npm run dev" -EnvMap @{
        ADMIN_API_URL = "http://localhost:8084"
    }
}

Write-Host ""
Write-Host "Pronto - cada servico abriu na propria janela (Ctrl+C nela para parar; feche a janela quando acabar)." -ForegroundColor Green
Write-Host ""
Write-Host "ATENCAO: isto NAO substitui o proxy 'edge' do Docker Compose. O app Android/iOS precisa de" -ForegroundColor Yellow
Write-Host "um unico endereco host:porta (campo 'Servidor online' do Perfil) e so o Compose oferece isso" -ForegroundColor Yellow
Write-Host "(porta 8080, 'docker compose up --build'). Use este script so para iterar no codigo e testar" -ForegroundColor Yellow
Write-Host "com curl/gradlew/admin-web direto - nao para testar pelo celular." -ForegroundColor Yellow
Write-Host ""
Write-Host "Health checks (espere cada servico compilar e subir antes de checar):"
Write-Host "  economy-service   http://localhost:8083/health"
Write-Host "  lobby-service     http://localhost:8081/health"
Write-Host "  game-service      http://localhost:8082/health"
Write-Host "  admin-api         http://localhost:8084/health"
if (-not $SkipAdminWeb) {
    Write-Host "  admin-web         http://localhost:5173"
}

# Yomukai web on Windows (Docker Desktop): install or update with one line.
#   irm https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.ps1 | iex
# Native tools write progress to stderr; failures are checked by exit code instead.
$ErrorActionPreference = 'Continue'
$Repo = 'https://github.com/NUXX-G/Yomukai'
$Raw = 'https://raw.githubusercontent.com/NUXX-G/Yomukai/main'
$Dir = if ($env:YOMUKAI_DIR) { $env:YOMUKAI_DIR } else { Join-Path $HOME 'yomukai' }
$Port = if ($env:PORT) { $env:PORT } else { '8080' }

function Say($text) { Write-Host $text -ForegroundColor DarkYellow }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Necesitas Docker Desktop: https://www.docker.com/products/docker-desktop/' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop no está en marcha: ábrelo y vuelve a ejecutar esta línea.' }

New-Item -ItemType Directory -Force -Path $Dir | Out-Null
Set-Location $Dir
Say "Instalando Yomukai en $Dir"
Invoke-WebRequest "$Raw/docker-compose.yml" -OutFile docker-compose.yml -UseBasicParsing

$New = $false
if (-not (Test-Path .env)) {
  $alphabet = 'abcdefghjkmnpqrstuvwxyz23456789'.ToCharArray()
  $bytes = New-Object byte[] 16
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $chars = $bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] }
  $Password = (0..3 | ForEach-Object { -join $chars[($_ * 4)..($_ * 4 + 3)] }) -join '-'
  @"
# Yomukai: change these and run "docker compose up -d" again.
PORT=$Port
ADMIN_USER=admin
ADMIN_PASSWORD=$Password
GUESTS_ENABLED=true
GUEST_DAYS=7
TRUST_PROXY=false
MAL_CLIENT_ID=
"@ | Set-Content -Encoding ascii .env
  $New = $true
}

docker compose pull --quiet yomukai 2>$null
if ($LASTEXITCODE -ne 0) {
  Say 'Compilando la imagen (la primera vez tarda unos minutos)...'
  if (Test-Path src) { Remove-Item -Recurse -Force src }
  git clone --depth 1 -c core.autocrlf=false $Repo src 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "No se pudo descargar el código de $Repo" }
  docker build -t ghcr.io/nuxx-g/yomukai:latest src
  if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar la imagen' }
  Remove-Item -Recurse -Force src
}
docker compose pull --quiet flaresolverr
docker compose up -d
if ($LASTEXITCODE -ne 0) { throw 'No se pudo arrancar Yomukai' }

$UsedPort = ((Get-Content .env | Where-Object { $_ -like 'PORT=*' }) -split '=')[1]
Say ''
Say "Yomukai está en marcha: http://localhost:$UsedPort"
if ($New) {
  Say 'Usuario: admin'
  Say "Contraseña: $(((Get-Content .env | Where-Object { $_ -like 'ADMIN_PASSWORD=*' }) -split '=')[1])"
  Say "(guardada en $Dir\.env)"
}
Say "Actualizar: vuelve a ejecutar esta misma línea. Parar: cd $Dir; docker compose down"

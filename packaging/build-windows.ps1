param(
    [string]$Version = '0.3.0',
    [string]$Python = '',
    [switch]$SkipInstaller,
    [switch]$SkipPortableZip
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $repo 'dist\windows'
$work = Join-Path $output '_work'
$image = Join-Path $output 'Genesis'

if ($env:OS -ne 'Windows_NT') { throw 'Ce script doit etre execute sous Windows.' }
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Version attendue : chiffres.chiffres.chiffres (ex. 0.1.0).' }
if (-not $Python) { $Python = Join-Path $repo 'logic\venv\Scripts\python.exe' }
$Python = [System.IO.Path]::GetFullPath($Python)
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Python introuvable : $Python. Cree logic\venv et installe logic\requirements.txt."
}
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) { throw 'gradle est introuvable dans PATH.' }
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) { throw 'jpackage (JDK 21) est introuvable dans PATH.' }
$icon = Join-Path $repo 'assets\favicon.ico'
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) { throw "Icone Windows introuvable : $icon" }

# Les suppressions sont limitées aux deux chemins de construction attendus.
$outputFull = [System.IO.Path]::GetFullPath($output).TrimEnd('\')
foreach ($target in @($work, $image)) {
    $full = [System.IO.Path]::GetFullPath($target)
    if (-not $full.StartsWith($outputFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Chemin de construction refuse : $full"
    }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $work, $output | Out-Null

& $Python -c 'import grpc, numpy, scipy, mendeleev, radioactivedecay, pynucastro, chemicals, periodictable, PyInstaller'
if ($LASTEXITCODE -ne 0) {
    throw "Dependance Python absente. Installe les dependances du moteur et PyInstaller dans $Python."
}

# Les stubs gRPC doivent correspondre aux versions de grpcio-tools installées
# dans le venv, sans modifier les fichiers générés suivis dans le dépôt.
$protoDir = Join-Path $work 'proto'
New-Item -ItemType Directory -Force -Path $protoDir | Out-Null
& $Python -m grpc_tools.protoc "-I$(Join-Path $repo 'core')" `
    "--python_out=$protoDir" "--grpc_python_out=$protoDir" `
    (Join-Path $repo 'core\genesis.proto')
if ($LASTEXITCODE -ne 0) { throw 'Generation des stubs Python gRPC impossible.' }

Write-Host 'Construction du moteur Python autonome...'
$pyDist = Join-Path $work 'python-dist'
$pyWork = Join-Path $work 'python-work'
$pySpec = Join-Path $work 'python-spec'
& $Python -m PyInstaller --noconfirm --clean --onedir --windowed `
    --name GenesisEngine --paths $protoDir --paths (Join-Path $repo 'logic') `
    --collect-submodules numpy.core `
    --collect-all mendeleev --collect-all radioactivedecay --collect-all pynucastro --collect-all periodictable `
    --collect-data chainlab --collect-data moleculelab `
    --distpath $pyDist --workpath $pyWork --specpath $pySpec `
    (Join-Path $PSScriptRoot 'windows_engine.py')
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller a echoue.' }
$engine = Join-Path $pyDist 'GenesisEngine\GenesisEngine.exe'
if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) { throw 'Moteur Python absent apres PyInstaller.' }
& $Python (Join-Path $PSScriptRoot 'smoke_windows_engine.py') $engine $protoDir
if ($LASTEXITCODE -ne 0) { throw 'Le moteur Windows autonome ne repond pas.' }

Write-Host 'Construction du client JavaFX...'
Push-Location (Join-Path $repo 'sim')
try {
    & gradle :app:installDist --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle installDist a echoue.' }
} finally { Pop-Location }

$inputDir = Join-Path $work 'input'
$engineDir = Join-Path $inputDir 'engine'
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item -Path (Join-Path $repo 'sim\app\build\install\app\lib\*') -Destination $inputDir -Recurse -Force
Copy-Item -LiteralPath (Join-Path $pyDist 'GenesisEngine') -Destination $engineDir -Recurse -Force
Copy-Item -LiteralPath (Join-Path $repo 'EULA.txt') -Destination (Join-Path $inputDir 'EULA.txt')
if (-not (Test-Path -LiteralPath (Join-Path $inputDir 'app.jar'))) { throw 'app.jar absent du paquet.' }
if (-not (Test-Path -LiteralPath (Join-Path $engineDir 'GenesisEngine.exe'))) { throw 'Moteur absent du paquet.' }

Write-Host 'Creation de l application Windows...'
& jpackage --type app-image --name Genesis --app-version $Version --vendor Gemmie `
    --input $inputDir --dest $output --icon $icon --main-jar app.jar `
    --main-class org.genesis.app.GenesisLauncher `
    --java-options '-Dprism.vsync=false' --java-options '-Dgenesis.bundled=true'
if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image a echoue.' }

if (-not $SkipPortableZip) {
    $zip = Join-Path $output "Genesis-$Version-portable.zip"
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
    Compress-Archive -LiteralPath $image -DestinationPath $zip -CompressionLevel Optimal
    Write-Host "Archive portable : $zip"
}

if (-not $SkipInstaller) {
    Write-Host 'Creation de l installateur Windows...'
    & jpackage --type exe --name Genesis --app-version $Version --vendor Gemmie `
        --app-image $image --dest $output --icon $icon --license-file (Join-Path $repo 'EULA.txt') `
        --win-per-user-install --win-menu --win-shortcut `
        --win-upgrade-uuid 'a903560a-642d-46a0-9c6e-63fc2cad03a0'
    if ($LASTEXITCODE -ne 0) { throw 'jpackage exe a echoue.' }
}

Write-Host "Application : $image\Genesis.exe"

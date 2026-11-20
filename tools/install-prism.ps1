# Installs the freshly built Prism jar into a Modrinth App profile.
#
# The Modrinth App keeps a record (and a backup copy) of every mod file by its path. Copying a new build over the
# old jar under the same name changes the content behind its back, and its next update then refuses to run
# ("restore or repair mods/prism-glass-...jar before updating this instance"). So every build gets its own file
# name (a build stamp), and the old Prism jars are removed: the app just sees one file gone and a new one added.
#
#   powershell -File tools\install-prism.ps1 [-Profile "Fabric 26.3"]
param([string]$Profile = "Fabric 26.3")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem (Join-Path $root "build\libs") -Filter "prism-glass-*.jar" |
    Where-Object { $_.Name -notmatch "-sources|-dev" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "No built jar in build\libs (run ./gradlew build first)" }

# never replace jars a running game has open (lazily loaded classes would break)
$game = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -match [regex]::Escape($Profile) -or $_.CommandLine -match 'knot' }
if ($game) { throw "Minecraft is running ($($game.ProcessId -join ', ')): close it first" }

$mods = Join-Path $env:APPDATA "ModrinthApp\profiles\$Profile\mods"
if (-not (Test-Path $mods)) { throw "No mods folder at $mods" }

Get-ChildItem $mods -Filter "prism-glass-*.jar*" | ForEach-Object {
    Remove-Item $_.FullName -Force
    "removed $($_.Name)"
}
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$name = $jar.BaseName + "-b" + $stamp + ".jar"
Copy-Item $jar.FullName (Join-Path $mods $name)
"installed $name ($($jar.Length) bytes)"

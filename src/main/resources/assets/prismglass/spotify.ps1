# Prism Glass now-playing reader. Prints one JSON line per poll from the Windows media session (Spotify app
# by default), with the session's own cover art (base64 PNG) after each track change. Exits when the game is gone.
# Plain PowerShell on purpose: no helper exe for antivirus to flag.
param([int]$AnyPlayer = 0, [int]$ParentPid = 0)
$ErrorActionPreference = 'Stop'
# UTF-8 without BOM: PowerShell 5.1 otherwise writes the OEM codepage and non-Latin titles turn into ???
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    $task.Result
}
[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime] | Out-Null
$inType = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
$rasType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]
# PowerShell can't call methods on the thumbnail stream (an unprojected COM object), but a reflection call lets the
# CLR cast it to IInputStream for the .NET stream adapter
$asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@($inType))
function Art($props) {
    if ($null -eq $props.Thumbnail) { return '' }
    $stream = Await ($props.Thumbnail.OpenReadAsync()) $rasType
    $mem = New-Object System.IO.MemoryStream
    $asStream.Invoke($null, @($stream)).CopyTo($mem)
    [Convert]::ToBase64String($mem.ToArray())
}
$lastKey = $null
$artPolls = 0
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

while ($true) {
    $line = @{ ok = $false }
    try {
        $session = $null
        foreach ($s in $mgr.GetSessions()) { if ($s.SourceAppUserModelId -match 'Spotify') { $session = $s; break } }
        $isSpotify = $null -ne $session
        if ($null -eq $session -and $AnyPlayer -eq 1) { $session = $mgr.GetCurrentSession() }
        if ($null -ne $session) {
            $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $tl = $session.GetTimelineProperties()
            $playing = $session.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
            $pos = $tl.Position.TotalMilliseconds
            if ($playing -and $tl.LastUpdatedTime.Year -gt 2000) { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalMilliseconds }
            $line = @{ ok = $true; title = $props.Title; artist = $props.Artist; album = $props.AlbumTitle
                pos = [long]$pos; dur = [long]$tl.EndTime.TotalMilliseconds; playing = $playing; spotify = $isSpotify }
            $key = "$($props.Artist)|$($props.Title)"
            if ($key -ne $lastKey) { $lastKey = $key; $artPolls = 0 }
            # the cover right away and again ~2 s later: Spotify swaps the thumbnail a moment after the title
            if ($artPolls -eq 0 -or $artPolls -eq 5) { try { $a = Art $props; if ($a) { $line.art = $a } } catch { } }
            $artPolls++
        }
    } catch { $line = @{ ok = $false; error = $_.Exception.Message } }
    [Console]::Out.WriteLine(($line | ConvertTo-Json -Compress))
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 400
    if ($ParentPid -gt 0 -and $null -eq (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { break }
}

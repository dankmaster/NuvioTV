#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start','command','capture')][string]$Action = 'command',
    [Parameter(Mandatory)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$Package = 'com.dankmaster.nuvio.tv.cli',
    [string]$SessionDirectory,
    [string]$Apk,
    [int]$Port = 8798,
    [string]$Command = 'status',
    [string]$RequestFile,
    [Nullable[long]]$PositionMs,
    [Nullable[double]]$Speed,
    [Nullable[int]]$Index,
    [Nullable[bool]]$Muted,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $SessionDirectory) { $SessionDirectory = Join-Path $repo '.debug-cli' }
$SessionDirectory = [System.IO.Path]::GetFullPath($SessionDirectory)
New-Item -ItemType Directory -Path $SessionDirectory -Force | Out-Null
if ($IsWindows) {
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $SessionDirectory /inheritance:r /grant:r "*$($sid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect the debug session directory.' }
}
$session = Join-Path $SessionDirectory 'session.json'
function Invoke-Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB command failed' }
    $result
}
if ($Action -eq 'start') {
    if ($Apk) { Invoke-Adb @('install','-r',$Apk) | Out-Null }
    Invoke-Adb @('shell','am','start','-n',"$Package/com.nuvio.tv.debug.DebugCliActivity") | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        $raw = & $Adb -s $Serial exec-out run-as $Package cat files/debug-cli/session.json 2>$null
        if ($LASTEXITCODE -eq 0) {
            try {
                $info = $raw -join "`n"
                $parsed = $info | ConvertFrom-Json
                Invoke-Adb @('forward',"tcp:$Port","tcp:$($parsed.port)") | Out-Null
                [System.IO.File]::WriteAllText($session, $info, [System.Text.UTF8Encoding]::new($false))
                & "$PSScriptRoot\debug-command.ps1" -Session $session -Port $Port -Command status
                return
            } catch { }
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'TV debug process did not become ready within 30 seconds.'
}
if ($Action -eq 'capture') {
    & "$PSScriptRoot\debug-command.ps1" -Session $session -Port $Port -Command render | Out-Null
    if (-not $Output) { $Output = Join-Path $SessionDirectory 'preview.png' }
    $outputPath = [System.IO.Path]::GetFullPath($Output)
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    foreach($arg in @('-s',$Serial,'exec-out','run-as',$Package,'cat','files/debug-cli/preview.png')) { $startInfo.ArgumentList.Add($arg) }
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $file = [System.IO.File]::Create($outputPath)
    try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Could not retrieve TV render' }
    @{ ok=$true; file=$outputPath } | ConvertTo-Json -Compress
    return
}
$params = @{ Session=$session; Port=$Port; Command=$Command }
foreach($key in @('RequestFile','PositionMs','Speed','Index','Muted')) {
    if ($PSBoundParameters.ContainsKey($key)) { $params[$key] = $PSBoundParameters[$key] }
}
& "$PSScriptRoot\debug-command.ps1" @params
if ($Command -eq 'shutdown') {
    Invoke-Adb @('forward','--remove',"tcp:$Port") | Out-Null
    Remove-Item -LiteralPath $session -ErrorAction SilentlyContinue
}

#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Session,
    [ValidateSet('status','load','play','pause','seek','speed','audio','subtitle','mute','stop','preview','render','shutdown')][string]$Command = 'status',
    [string]$RequestFile,
    [Nullable[long]]$PositionMs,
    [Nullable[double]]$Speed,
    [Nullable[int]]$Index,
    [Nullable[bool]]$Muted,
    [int]$Port = 0
)
$ErrorActionPreference = 'Stop'
$sessionInfo = Get-Content -LiteralPath $Session -Raw | ConvertFrom-Json
$request = if ($RequestFile) { Get-Content -LiteralPath $RequestFile -Raw | ConvertFrom-Json -AsHashtable } else { @{} }
$request.command = $Command
$request.token = $sessionInfo.token
if ($null -ne $PositionMs) { $request.positionMs = $PositionMs }
if ($null -ne $Speed) { $request.speed = $Speed }
if ($null -ne $Index) { $request.index = $Index }
if ($null -ne $Muted) { $request.muted = $Muted }
$targetPort = if ($Port) { $Port } else { [int]$sessionInfo.port }
$client = [System.Net.Sockets.TcpClient]::new()
try {
    if (-not $client.ConnectAsync('127.0.0.1', $targetPort).Wait(5000)) { throw 'Debug CLI connection timeout' }
    $client.ReceiveTimeout = 15000
    $client.SendTimeout = 5000
    $stream = $client.GetStream()
    $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false), 1024, $true)
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.UTF8Encoding]::new($false), $false, 1024, $true)
    $writer.WriteLine(($request | ConvertTo-Json -Depth 20 -Compress))
    $writer.Flush()
    $response = $reader.ReadLine()
    if (-not $response) { throw 'Debug CLI closed without a response' }
    $parsed = $response | ConvertFrom-Json
    $response
    if (-not $parsed.ok) { throw "Debug command failed: $($parsed.error)" }
} finally {
    $client.Dispose()
}

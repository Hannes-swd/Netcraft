#Requires -Version 5.1
<#
  modtool - baut, versioniert und veröffentlicht alle Netcraft-Mods zusammen.
  Jede Mod hat eine Variante (Ordner/Worktree + Branch) pro Minecraft-Version, siehe mods.json.

    modtool status                          Übersicht aller Mods und Varianten
    modtool build [mod...] [-Mc 1.21.1] [-All]
                                            baut geänderte Varianten (und was davon abhängt)
    modtool run [mod] [-Mc 1.21.1]          baut und startet Minecraft mit der Mod (+ Abhängigkeiten)
    modtool release [mod...] [-Mc ...] [-Notes "Text"] [-Bump patch|minor|major] [-DryRun]
                                            neue Versionen: Version hoch, Build-Check, Changelog,
                                            Commit, Tag, Push, GitHub-Release, Modrinth-Ordner
    modtool push [mod...]                   Commits aller Varianten zu GitHub pushen
    modtool new "Netcraft: Name" mod_id [-Mc 1.21.1]
                                            neues Addon anlegen (hängt von Netcraft ab)
    modtool port mod 1.21.4 [-From 1.21.1]  neue Minecraft-Version einer Mod als eigenen Worktree anlegen

  Schalter für Skripte/Claude: -NoPrompt (fragt nie nach, öffnet keinen Explorer), -DryRun (nur anzeigen).

  Überblick:
    modtool gui [-Port 8765]                Oberfläche im Browser: alle Branches, Knöpfe für Bauen,
                                            Starten, Release, neue Minecraft-Version, neues Addon, Features
    modtool overview [-Json] [-Offline]     Matrix Mods x Minecraft-Versionen (+ GitHub-Releases, Features)
    modtool dashboard [-Offline]            dasselbe als Webseite (dashboard.html) im Browser
    modtool feature [list] [mod]            welches Feature ist in welcher Minecraft-Version drin
    modtool feature add mod "Name" [-Mc 1.21.1,26.1] [-Version 1.12.0]
                                            Feature eintragen (Standard: aktuelle Version der Variante)
    modtool feature remove mod "Name" [-Mc 26.1]
    modtool feature missing [mod]           was fehlt in welcher Minecraft-Version (Port-Liste)

  Mods kann man per id, Name oder Teil davon angeben ("netcraft", "pc"); "mod@1.21.1" wählt eine Variante.
#>
param(
    [Parameter(Position = 0)][string]$Command = 'status',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Names = @(),
    [string]$Mc = '',
    [string]$Notes = '',
    # release: read the notes from this file (multi-line text from the GUI).
    [string]$NotesFile = '',
    # gui: local port of the web interface.
    [int]$Port = 8765,
    [ValidateSet('patch', 'minor', 'major')][string]$Bump = 'patch',
    [string]$From = '',
    # feature: the mod version a feature arrived in (default: the variant's current version).
    [string]$Version = '',
    [switch]$All,
    [switch]$DryRun,
    # overview: machine-readable output (for Claude and scripts).
    [switch]$Json,
    # overview/dashboard: skip asking GitHub for releases.
    [switch]$Offline,
    # For scripts and Claude: never wait for input, never open Explorer or the browser.
    [switch]$NoPrompt
)

$ErrorActionPreference = 'Continue'
# Started by the GUI: its output is read as UTF-8 (umlauts).
if ($env:MODTOOL_CHILD) { try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { } }
$ToolDir = $PSScriptRoot
$Root = Split-Path $ToolDir -Parent
$ConfigPath = Join-Path $ToolDir 'mods.json'
$StatePath = Join-Path $ToolDir 'state.json'
$Utf8 = New-Object System.Text.UTF8Encoding $false

function Load-Config { Get-Content $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json }
$Config = Load-Config
if ($NotesFile -and (Test-Path $NotesFile)) { $Notes = [IO.File]::ReadAllText($NotesFile, [Text.Encoding]::UTF8).Trim() }
$BuildRoot = [Environment]::ExpandEnvironmentVariables($Config.buildRoot)
$OutRoot = [Environment]::ExpandEnvironmentVariables($Config.outputDir)

# ------------------------------------------------------------------ output

function Step($t) { Write-Host "==> $t" -ForegroundColor Cyan }
function Ok($t) { Write-Host "    $t" -ForegroundColor Green }
function Info($t) { Write-Host "    $t" -ForegroundColor Gray }
function Warn($t) { Write-Host "    ! $t" -ForegroundColor Yellow }
function Fail($t) { Write-Host "FEHLER: $t" -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------------ mods and variants

# A target is one variant of one mod: Mod, Mc, Path, Branch, Key ("id@mc"), Label.
function Get-Targets {
    $list = @()
    foreach ($mod in $Config.mods) {
        foreach ($v in @($mod.variants)) {
            if (-not $v) { continue }
            $list += [pscustomobject]@{
                Mod = $mod; Mc = $v.mc; Branch = $v.branch
                Path = Join-Path $Root $v.path
                Key = "$($mod.id)@$($v.mc)"; Label = "$($mod.name) ($($v.mc))"
            }
        }
    }
    return $list
}

function Find-Mod([string]$query) {
    $q = $query.ToLower()
    $exact = @($Config.mods | Where-Object { $_.id.ToLower() -eq $q -or $_.name.ToLower() -eq $q })
    if ($exact.Count -eq 1) { return $exact[0] }
    $part = @($Config.mods | Where-Object { $_.id.ToLower().Contains($q) -or $_.name.ToLower().Contains($q) })
    if ($part.Count -eq 1) { return $part[0] }
    if ($part.Count -gt 1) {
        # "netcraft" matches every Netcraft mod: prefer the one without dependencies (the base mod).
        $base = @($part | Where-Object { @($_.dependsOn | Where-Object { $_ }).Count -eq 0 })
        if ($base.Count -eq 1) { return $base[0] }
        Fail "'$query' passt auf mehrere Mods: $(($part | ForEach-Object { $_.id }) -join ', ')"
    }
    Fail "Keine Mod '$query' in mods.json"
}

# Targets chosen on the command line ("mod", "mod@mc", -Mc), or $null for "all".
function Select-Targets([string[]]$queries) {
    $all = Get-Targets
    if (-not $queries -or $queries.Count -eq 0) {
        if ($Mc) { return @($all | Where-Object { $_.Mc -eq $Mc }) }
        return $null
    }
    $out = @()
    foreach ($q in $queries) {
        $mc = $Mc
        $name = $q
        if ($q -match '^(.+)@(.+)$') { $name = $Matches[1]; $mc = $Matches[2] }
        $mod = Find-Mod $name
        $found = @($all | Where-Object { $_.Mod.id -eq $mod.id -and (-not $mc -or $_.Mc -eq $mc) })
        if ($found.Count -eq 0) { Fail "$($mod.name) hat keine Variante für Minecraft $mc" }
        $out += $found
    }
    return $out
}

function Get-Target([string]$modId, [string]$mc) {
    return Get-Targets | Where-Object { $_.Mod.id -eq $modId -and $_.Mc -eq $mc } | Select-Object -First 1
}

# Variants this one needs (same Minecraft version).
function Get-Deps($t) {
    $deps = @()
    foreach ($d in @($t.Mod.dependsOn)) {
        if (-not $d) { continue }
        $dt = Get-Target $d.mod $t.Mc
        if (-not $dt) { Fail "$($t.Label) braucht $($d.mod) für Minecraft $($t.Mc), die Variante gibt es nicht" }
        $deps += [pscustomobject]@{ Target = $dt; Property = $d.property }
    }
    return $deps
}

# Variants that need this one (directly or indirectly).
function Get-Dependents($t) {
    $out = @()
    foreach ($o in Get-Targets) {
        if ($o.Mc -ne $t.Mc) { continue }
        foreach ($d in @($o.Mod.dependsOn)) {
            if ($d -and $d.mod -eq $t.Mod.id) { $out += $o; $out += Get-Dependents $o }
        }
    }
    return $out
}

function Test-IsDependency($t) { return @(Get-Dependents $t).Count -gt 0 }

# Dependencies first.
function Sort-Targets($targets) {
    $keys = @($targets | ForEach-Object { $_.Key })
    $script:sorted = New-Object System.Collections.ArrayList
    $script:seen = @{}
    function Visit($t) {
        if ($script:seen[$t.Key]) { return }
        $script:seen[$t.Key] = $true
        foreach ($d in Get-Deps $t) { Visit $d.Target }
        [void]$script:sorted.Add($t)
    }
    foreach ($t in $targets) { Visit $t }
    return @($script:sorted | Where-Object { $keys -contains $_.Key })
}

function Unique-Targets($targets) {
    $seen = @{}
    $out = @()
    foreach ($t in $targets) { if ($t -and -not $seen[$t.Key]) { $seen[$t.Key] = $true; $out += $t } }
    return $out
}

# ------------------------------------------------------------------ gradle.properties

function Get-Prop($t, [string]$key) {
    $f = Join-Path $t.Path 'gradle.properties'
    foreach ($line in [IO.File]::ReadAllLines($f)) {
        if ($line -match "^\s*$([regex]::Escape($key))\s*=\s*(.*)$") { return $Matches[1].Trim() }
    }
    return $null
}

function Set-Prop($t, [string]$key, [string]$value) {
    $f = Join-Path $t.Path 'gradle.properties'
    $lines = [IO.File]::ReadAllLines($f)
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") { $lines[$i] = "$key=$value"; $found = $true }
    }
    if (-not $found) { $lines += "$key=$value" }
    [IO.File]::WriteAllText($f, (($lines -join "`n") + "`n"), $Utf8)
}

function Get-Version($t) { return Get-Prop $t 'mod_version' }

function Step-Version([string]$v, [string]$kind) {
    if ($v -notmatch '^(\d+)\.(\d+)\.(\d+)(.*)$') { Fail "Version '$v' kann ich nicht hochzählen" }
    $a = [int]$Matches[1]; $b = [int]$Matches[2]; $c = [int]$Matches[3]; $rest = $Matches[4]
    switch ($kind) {
        'major' { $a++; $b = 0; $c = 0 }
        'minor' { $b++; $c = 0 }
        default { $c++ }
    }
    return "$a.$b.$c$rest"
}

# ------------------------------------------------------------------ git

function Run-Git($t) {
    $out = & git.exe -C $t.Path @args 2>&1 | ForEach-Object { "$_" }
    $script:GitCode = $LASTEXITCODE
    return $out
}

function Test-Tag($t, [string]$tag) {
    & git.exe -C $t.Path rev-parse -q --verify "refs/tags/$tag" *> $null
    return $LASTEXITCODE -eq 0
}

# Release tags are "<mod tag>-v<version>", e.g. netcraft-v1.13.0+1.21.1 (all mods share one repo).
function Get-Tag($t, [string]$version) {
    $prefix = if ($t.Mod.tag) { "$($t.Mod.tag)-" } else { '' }
    return "${prefix}v$version"
}

function Get-Dirty($t) { return @(Run-Git $t status --porcelain | Where-Object { $_ }).Count }

function Test-ChangedSinceRelease($t) {
    $tag = Get-Tag $t (Get-Version $t)
    if (-not (Test-Tag $t $tag)) { return $true }
    & git.exe -C $t.Path diff --quiet $tag -- . *> $null
    if ($LASTEXITCODE -ne 0) { return $true }
    return @(Run-Git $t ls-files -o --exclude-standard | Where-Object { $_ }).Count -gt 0
}

# A hash of everything in the working tree that git sees, to know whether a build is still current.
function Get-Fingerprint($t) {
    $head = (Run-Git $t rev-parse HEAD) -join ''
    $diff = (Run-Git $t diff HEAD --no-color) -join "`n"
    $untracked = @(Run-Git $t ls-files -o --exclude-standard | Where-Object { $_ })
    $hashes = if ($untracked.Count) { (& git.exe -C $t.Path hash-object -- @untracked) -join ',' } else { '' }
    $deps = (Get-Deps $t | ForEach-Object { "$($_.Property)=$(Get-Version $_.Target)" }) -join ';'
    $text = "$head|$diff|$($untracked -join ',')|$hashes|$deps"
    $sha = [Security.Cryptography.SHA256]::Create()
    return ([BitConverter]::ToString($sha.ComputeHash($Utf8.GetBytes($text)))).Replace('-', '')
}

function Ensure-Remote($t) {
    $url = Run-Git $t remote get-url origin
    if ($script:GitCode -eq 0) { return $true }
    if ($DryRun) { Info "würde GitHub-Repo $($t.Mod.repo) anlegen ($($Config.visibility))"; return $false }
    & gh.exe repo view $t.Mod.repo *> $null
    if ($LASTEXITCODE -eq 0) {
        Run-Git $t remote add origin "https://github.com/$($t.Mod.repo).git" | Out-Null
    } else {
        Step "Lege GitHub-Repo $($t.Mod.repo) an ($($Config.visibility))"
        & gh.exe repo create $t.Mod.repo "--$($Config.visibility)" --source $t.Path --remote origin
        if ($LASTEXITCODE -ne 0) { Fail "Konnte $($t.Mod.repo) nicht anlegen" }
    }
    return $true
}

function Get-PushState($t) {
    $url = Run-Git $t remote get-url origin
    if ($script:GitCode -ne 0) { return 'kein Repo' }
    & git.exe -C $t.Path rev-parse -q --verify "refs/remotes/origin/$($t.Branch)" *> $null
    if ($LASTEXITCODE -ne 0) { return 'nie gepusht' }
    $ahead = (Run-Git $t rev-list --count "origin/$($t.Branch)..HEAD") -join ''
    if ($ahead -eq '0') { return 'aktuell' }
    return "$ahead Commit(s) offen"
}

# ------------------------------------------------------------------ state (last builds)

function Load-State {
    if (Test-Path $StatePath) {
        $obj = Get-Content $StatePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $h = @{}
        foreach ($p in $obj.PSObject.Properties) { $h[$p.Name] = $p.Value }
        return $h
    }
    return @{}
}

function Save-State($h) {
    [IO.File]::WriteAllText($StatePath, ($h | ConvertTo-Json -Depth 5), $Utf8)
}

# ------------------------------------------------------------------ building

# The dev world of a Minecraft version: the run folder of the base mod's variant, shared by all add-ons.
function Get-DevRun($t) {
    $base = Get-Targets | Where-Object { $_.Mc -eq $t.Mc -and @($_.Mod.dependsOn | Where-Object { $_ }).Count -eq 0 } | Select-Object -First 1
    if (-not $base) { $base = $t }
    return Join-Path $base.Path 'run'
}

function Get-JarName($t) { return "$(Get-Prop $t 'archives_base_name')-$(Get-Version $t).jar" }

# Builds one variant in a copy outside OneDrive; dependencies are taken from mavenLocal.
function Invoke-Build($t, [switch]$RunClient) {
    $copy = Join-Path $BuildRoot ("$($t.Mod.id)-$($t.Mc)")
    New-Item -ItemType Directory -Force $copy | Out-Null
    robocopy $t.Path $copy /MIR /XD build .gradle run .git /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { Fail "Kopieren von $($t.Label) fehlgeschlagen" }
    $run = Join-Path $copy 'run'
    if (-not (Test-Path $run)) {
        $target = Get-DevRun $t
        New-Item -ItemType Directory -Force $target | Out-Null
        New-Item -ItemType Junction -Path $run -Target $target | Out-Null
    }
    $tasks = if ($RunClient) { 'runClient' } elseif (Test-IsDependency $t) { 'build publishToMavenLocal' } else { 'build' }
    $props = @(Get-Deps $t | ForEach-Object { "-P$($_.Property)=$(Get-Version $_.Target)" }) -join ' '
    $log = Join-Path $BuildRoot ("$($t.Mod.id)-$($t.Mc).log")
    $env:JAVA_HOME = $Config.jdk
    Push-Location $copy
    $oldCwd = [Environment]::CurrentDirectory
    [Environment]::CurrentDirectory = $copy
    $gradlew = Join-Path $copy 'gradlew.bat'
    if ($RunClient) {
        cmd /c "$gradlew -p $copy $tasks $props --console=plain"
    } else {
        cmd /c "$gradlew -p $copy $tasks $props --console=plain > $log 2>&1"
    }
    $code = $LASTEXITCODE
    [Environment]::CurrentDirectory = $oldCwd
    Pop-Location
    if ($RunClient) { return $null }
    if ($code -ne 0) {
        Get-Content $log -Tail 30 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
        Fail "Build von $($t.Label) fehlgeschlagen (ganzes Log: $log)"
    }
    $jar = Join-Path $copy "build\libs\$(Get-JarName $t)"
    if (-not (Test-Path $jar)) { Fail "Jar nicht gefunden: $jar" }
    return $jar
}

function Copy-ToLatest($t, $jar) {
    $dir = Join-Path $OutRoot "latest\$($t.Mc)"
    New-Item -ItemType Directory -Force $dir | Out-Null
    $base = Get-Prop $t 'archives_base_name'
    Get-ChildItem $dir -Filter "$base-*.jar" | Where-Object { $_.Name -match "^$([regex]::Escape($base))-\d" } | Remove-Item -Force
    Copy-Item $jar $dir -Force
    return Join-Path $dir (Split-Path $jar -Leaf)
}

# Builds the given variants plus everything that depends on them, in order. Returns key -> jar.
function Build-Set($targets) {
    $set = @($targets)
    foreach ($t in $targets) { $set += Get-Dependents $t }
    $set = Sort-Targets (Unique-Targets $set)
    $state = Load-State
    $jars = @{}
    foreach ($t in $set) {
        Step "Baue $($t.Label) $(Get-Version $t)"
        $jar = Invoke-Build $t
        $jars[$t.Key] = $jar
        $state[$t.Key] = Get-Fingerprint $t
        Ok "fertig: $(Copy-ToLatest $t $jar)"
    }
    Save-State $state
    return $jars
}

# ------------------------------------------------------------------ commands

function Cmd-Status {
    $state = Load-State
    $rows = foreach ($t in Sort-Targets (Get-Targets)) {
        if (-not (Test-Path $t.Path)) {
            [pscustomobject]@{ Mod = $t.Mod.name; MC = $t.Mc; Version = '-'; Branch = $t.Branch; Offen = '-'; 'Seit Release' = '-'; Build = '-'; GitHub = "Ordner fehlt: $($t.Path)" }
            continue
        }
        $fp = Get-Fingerprint $t
        [pscustomobject]@{
            Mod            = $t.Mod.name
            MC             = $t.Mc
            Version        = Get-Version $t
            Branch         = (Run-Git $t branch --show-current) -join ''
            Offen          = "$(Get-Dirty $t) Datei(en)"
            'Seit Release' = if (Test-ChangedSinceRelease $t) { 'geändert' } else { '-' }
            Build          = if ($state[$t.Key] -eq $fp) { 'aktuell' } else { 'neu bauen' }
            GitHub         = Get-PushState $t
        }
    }
    $rows | Format-Table -AutoSize
}

function Cmd-Build {
    $chosen = Select-Targets $Names
    if ($null -eq $chosen) {
        $chosen = Get-Targets
        if (-not $All) {
            $state = Load-State
            $chosen = @($chosen | Where-Object { (Test-Path $_.Path) -and $state[$_.Key] -ne (Get-Fingerprint $_) })
        }
    }
    if (@($chosen).Count -eq 0) { Ok 'Alles ist schon gebaut und aktuell.'; return }
    Build-Set $chosen | Out-Null
    Ok "Jars liegen in $(Join-Path $OutRoot 'latest')"
}

function Cmd-Run {
    $chosen = Select-Targets $Names
    if ($null -eq $chosen) {
        # Default: the last mod in dependency order (an add-on, which brings Netcraft along) for -Mc or the first version.
        $mcWanted = if ($Mc) { $Mc } else { (Get-Targets | Select-Object -First 1).Mc }
        $chosen = @(Sort-Targets @(Get-Targets | Where-Object { $_.Mc -eq $mcWanted }) | Select-Object -Last 1)
    }
    $t = @($chosen)[0]
    $deps = @()
    $queue = @($t)
    while ($queue.Count) { $x = $queue[0]; $queue = @($queue | Select-Object -Skip 1); foreach ($d in Get-Deps $x) { $deps += $d.Target; $queue += $d.Target } }
    $state = Load-State
    $stale = @((Unique-Targets $deps) | Where-Object { $state[$_.Key] -ne (Get-Fingerprint $_) })
    if ($stale.Count) { Build-Set $stale | Out-Null }
    Step "Starte Minecraft $($t.Mc) mit $($t.Label) (Welt-Ordner: $(Get-DevRun $t))"
    Invoke-Build $t -RunClient | Out-Null
}

function Get-AutoNotes($t) {
    $prev = (Run-Git $t describe --tags --abbrev=0 --match (Get-Tag $t '*')) -join ''
    if ($script:GitCode -ne 0) { return $null }
    $lines = @(Run-Git $t log "$prev..HEAD" --pretty=format:%s | Where-Object { $_ -and $_ -notmatch '^Release ' })
    if ($lines.Count -eq 0) { return $null }
    return ($lines | ForEach-Object { "- $_" }) -join "`n"
}

function Add-Changelog($t, [string]$version, [string]$text) {
    $f = Join-Path $t.Path 'CHANGELOG.md'
    $old = if (Test-Path $f) { [IO.File]::ReadAllText($f) -replace '^# Changelog\s*', '' } else { '' }
    $entry = "## $version ($(Get-Date -Format 'yyyy-MM-dd'))`n`n$text`n`n"
    [IO.File]::WriteAllText($f, "# Changelog`n`n$entry$old", $Utf8)
}

function Cmd-Release {
    $chosen = Select-Targets $Names
    if ($null -eq $chosen) { $chosen = @(Get-Targets | Where-Object { Test-Path $_.Path }) }
    $targets = Sort-Targets @($chosen | Where-Object { Test-ChangedSinceRelease $_ })
    if (@($targets).Count -eq 0) { Ok 'Seit dem letzten Release hat sich nichts geändert.'; return }

    # Plan: new versions and notes.
    $plan = @{}
    $notesOf = @{}
    foreach ($t in $targets) {
        $cur = Get-Version $t
        $plan[$t.Key] = if (Test-Tag $t (Get-Tag $t $cur)) { Step-Version $cur $Bump } else { $cur }
        $n = $Notes
        if (-not $n) { $n = Get-AutoNotes $t }
        if (-not $n -and -not $DryRun -and -not $NoPrompt) { try { $n = Read-Host "Was hat sich in $($t.Label) geändert" } catch { $n = '' } }
        if (-not $n) { $n = '- Kleine Verbesserungen' }
        $notesOf[$t.Key] = $n
    }
    Step 'Release-Plan'
    foreach ($t in $targets) { Info "$($t.Label): $(Get-Version $t) -> $($plan[$t.Key])" }
    $checkOnly = @(Unique-Targets ($targets | ForEach-Object { Get-Dependents $_ }) | Where-Object { -not $plan.ContainsKey($_.Key) })
    foreach ($t in $checkOnly) { Info "$($t.Label): wird nur gegen die neue Version getestet (kein Release)" }
    if ($DryRun) {
        foreach ($t in $targets) { Write-Host "`n$($t.Label) $($plan[$t.Key]):`n$($notesOf[$t.Key])" }
        Ok 'Probelauf (-DryRun): nichts geändert.'
        return
    }

    # Write versions (and the dependency versions the add-ons are built against); undo on failure.
    $backup = @{}
    foreach ($t in Unique-Targets (@($targets) + $checkOnly)) { $backup[$t.Key] = [IO.File]::ReadAllText((Join-Path $t.Path 'gradle.properties')) }
    foreach ($t in $targets) {
        Set-Prop $t 'mod_version' $plan[$t.Key]
        foreach ($d in Get-Deps $t) { Set-Prop $t $d.Property (Get-Version $d.Target) }
    }
    try {
        $jars = Build-Set $targets
    } finally {
        if (-not $jars) {
            foreach ($t in Unique-Targets (@($targets) + $checkOnly)) { [IO.File]::WriteAllText((Join-Path $t.Path 'gradle.properties'), $backup[$t.Key], $Utf8) }
            Warn 'Versionen zurückgesetzt, nichts veröffentlicht.'
        }
    }

    $outDir = Join-Path $OutRoot ("release " + (Get-Date -Format 'yyyy-MM-dd HH-mm'))
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $modrinth = @("# Modrinth-Upload $(Get-Date -Format 'yyyy-MM-dd HH:mm')", '')
    foreach ($t in $targets) {
        $v = $plan[$t.Key]
        $notesText = $notesOf[$t.Key]
        Step "Veröffentliche $($t.Label) $v"
        Add-Changelog $t $v $notesText
        Run-Git $t add -A | Out-Null
        Run-Git $t commit -q -m "Release $($t.Mod.name) $v" -m $notesText | Out-Null
        Run-Git $t tag -a (Get-Tag $t $v) -m "$($t.Mod.name) $v" | Out-Null
        Ok "Commit und Tag $(Get-Tag $t $v)"
        if (Ensure-Remote $t) {
            Run-Git $t push -q origin $t.Branch | Out-Null
            if ($script:GitCode -ne 0) { Fail "Push von $($t.Label) fehlgeschlagen" }
            Run-Git $t push -q origin (Get-Tag $t $v) | Out-Null
            Ok "gepusht nach $($t.Mod.repo) ($($t.Branch))"
            & gh.exe release create (Get-Tag $t $v) $jars[$t.Key] --repo $t.Mod.repo --title "$($t.Mod.name) $v" --notes $notesText *> $null
            if ($LASTEXITCODE -eq 0) { Ok 'GitHub-Release erstellt' } else { Warn 'GitHub-Release konnte nicht erstellt werden' }
        }
        Copy-Item $jars[$t.Key] $outDir -Force
        $required = @('Fabric API') + @(Get-Deps $t | ForEach-Object { $_.Target.Mod.name })
        $optional = @(Get-Dependents $t | ForEach-Object { $_.Mod.name } | Select-Object -Unique)
        $modrinth += "## $($t.Mod.name)", ''
        $modrinth += "- Datei: $(Split-Path $jars[$t.Key] -Leaf)"
        $modrinth += "- Versionsnummer: $v"
        $modrinth += "- Minecraft: $($t.Mc)   Loader: Fabric"
        $modrinth += "- Pflicht-Abhängigkeiten: $($required -join ', ')"
        if ($optional.Count) { $modrinth += "- Optional (empfohlen): $($optional -join ', ')" }
        $modrinth += '', 'Changelog:', '', $notesText, ''
    }
    [IO.File]::WriteAllText((Join-Path $outDir 'MODRINTH.md'), ($modrinth -join "`n"), $Utf8)
    foreach ($t in $checkOnly) { Ok "$($t.Label) läuft mit den neuen Versionen (getestet)" }
    Ok "Fertig. Jars und Modrinth-Texte: $outDir"
    if (-not $NoPrompt) { Start-Process explorer.exe $outDir }
}

function Cmd-Push {
    $chosen = Select-Targets $Names
    if ($null -eq $chosen) { $chosen = @(Get-Targets | Where-Object { Test-Path $_.Path }) }
    foreach ($t in $chosen) {
        if ((Get-Dirty $t) -gt 0) { Warn "$($t.Label): $(Get-Dirty $t) Datei(en) nicht committet (bleiben lokal)" }
        if (-not (Ensure-Remote $t)) { continue }
        Step "Push $($t.Label) ($($t.Branch))"
        if ($DryRun) { continue }
        Run-Git $t push -q --follow-tags origin $t.Branch | Out-Null
        if ($script:GitCode -eq 0) { Ok 'ok' } else { Warn 'Push fehlgeschlagen' }
    }
}

function To-ClassName([string]$id) { return (($id -split '_') | ForEach-Object { if ($_) { $_.Substring(0, 1).ToUpper() + $_.Substring(1) } }) -join '' }

function Save-Config($cfg) { [IO.File]::WriteAllText($ConfigPath, ($cfg | ConvertTo-Json -Depth 8), $Utf8) }

function Cmd-New {
    if ($Names.Count -lt 2) { Fail 'Aufruf: modtool new "Netcraft: Name" mod_id [-Mc 1.21.1]' }
    $name = $Names[0]; $id = $Names[1].ToLower()
    if ($id -notmatch '^[a-z][a-z0-9_]*$') { Fail "Mod-ID '$id' darf nur a-z, 0-9 und _ enthalten" }
    if ($Config.mods | Where-Object { $_.id -eq $id }) { Fail "Mod $id gibt es schon" }
    $mc = if ($Mc) { $Mc } else { '1.21.1' }
    $base = Get-Targets | Where-Object { $_.Mc -eq $mc -and @($_.Mod.dependsOn | Where-Object { $_ }).Count -eq 0 } | Select-Object -First 1
    if (-not $base) { Fail "Keine Basis-Mod für Minecraft $mc" }
    $template = Get-Targets | Where-Object { $_.Mc -eq $mc -and @($_.Mod.dependsOn | Where-Object { $_ }).Count -gt 0 } | Select-Object -First 1
    if (-not $template) { Fail "Kein Addon für Minecraft $mc als Vorlage gefunden" }
    $folder = ($name -replace '[:]', '' -replace '\s+', ' ').ToLower() + " $mc"
    $dir = Join-Path $Root $folder
    if (Test-Path $dir) { Fail "Ordner $dir gibt es schon" }
    $cls = To-ClassName $id
    $pkg = "dev.hannes.$id"
    $pkgDir = $pkg -replace '\.', '\'
    Step "Lege $name ($id) für Minecraft $mc an: $dir"
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($f in @('gradle', 'gradlew', 'gradlew.bat', 'settings.gradle', '.gitignore')) {
        Copy-Item (Join-Path $template.Path $f) $dir -Recurse -Force
    }
    $settings = (Get-Content (Join-Path $dir 'settings.gradle') -Raw) -replace 'rootProject\.name\s*=.*', "rootProject.name = `"$($id -replace '_', '-')`""
    [IO.File]::WriteAllText((Join-Path $dir 'settings.gradle'), $settings, $Utf8)
    $gradle = (Get-Content (Join-Path $template.Path 'build.gradle') -Raw) -replace [regex]::Escape($template.Mod.id), $id
    [IO.File]::WriteAllText((Join-Path $dir 'build.gradle'), $gradle, $Utf8)
    $props = Get-Content (Join-Path $template.Path 'gradle.properties') -Raw
    $props = $props -replace '(?m)^mod_version=.*$', "mod_version=0.1.0+$mc" -replace '(?m)^archives_base_name=.*$', "archives_base_name=$($id -replace '_', '-')"
    $props = $props -replace '(?m)^netcraft_version=.*$', "netcraft_version=$(Get-Version $base)"
    [IO.File]::WriteAllText((Join-Path $dir 'gradle.properties'), $props, $Utf8)

    $main = Join-Path $dir "src\main\java\$pkgDir"
    $client = Join-Path $dir "src\client\java\$pkgDir\client"
    $assets = Join-Path $dir "src\main\resources\assets\$id"
    New-Item -ItemType Directory -Force $main, $client, (Join-Path $assets 'lang') | Out-Null
    $icon = Join-Path $template.Path "src\main\resources\assets\$($template.Mod.id)\icon.png"
    if (Test-Path $icon) { Copy-Item $icon (Join-Path $assets 'icon.png') }
    [IO.File]::WriteAllText((Join-Path $main "$cls.java"), @"
package $pkg;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ${name} - an add-on for Netcraft. Netcraft's hooks for add-ons: dev.hannes.virtual_mashine.api.NetcraftApi. */
public class $cls implements ModInitializer {
    public static final String MOD_ID = "$id";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("$name loaded");
    }
}
"@, $Utf8)
    [IO.File]::WriteAllText((Join-Path $client "${cls}Client.java"), @"
package $pkg.client;

import net.fabricmc.api.ClientModInitializer;

public class ${cls}Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
    }
}
"@, $Utf8)
    [IO.File]::WriteAllText((Join-Path $dir 'src\main\resources\fabric.mod.json'), @"
{
  "schemaVersion": 1,
  "id": "$id",
  "version": "`${version}",
  "name": "$name",
  "description": "An add-on for Netcraft.",
  "authors": ["Hannes"],
  "license": "All-Rights-Reserved",
  "icon": "assets/$id/icon.png",
  "environment": "*",
  "entrypoints": {
    "main": ["$pkg.$cls"],
    "client": ["$pkg.client.${cls}Client"]
  },
  "depends": {
    "fabricloader": ">=0.16.0",
    "fabric-api": "*",
    "minecraft": "~`${minecraft_version}",
    "$($base.Mod.id)": ">=`${netcraft_version}"
  }
}
"@, $Utf8)
    [IO.File]::WriteAllText((Join-Path $assets 'lang\en_us.json'), "{`n}`n", $Utf8)
    [IO.File]::WriteAllText((Join-Path $assets 'lang\de_de.json'), "{`n}`n", $Utf8)

    $short = (($name -replace '^Netcraft:\s*', '') -replace '[^A-Za-z0-9]+', '-').Trim('-').ToLower()
    & git.exe -C $dir init -q -b "$short/mc-$mc"
    & git.exe -C $dir add -A
    & git.exe -C $dir commit -q -m "$name 0.1.0: new Netcraft add-on"

    $cfg = Load-Config
    # All Netcraft mods share one repo; each variant is a branch "<tag>/mc-<version>".
    $repo = $cfg.repo
    $entry = [pscustomobject]@{
        id = $id; name = $name; tag = $short; branchPrefix = "$short/"; repo = $repo
        dependsOn = @([pscustomobject]@{ mod = $base.Mod.id; property = 'netcraft_version' })
        variants = @([pscustomobject]@{ mc = $mc; path = $folder; branch = "$short/mc-$mc" })
    }
    $cfg.mods = @($cfg.mods) + $entry
    Save-Config $cfg
    Ok "Angelegt und in mods.json eingetragen (GitHub-Repo $repo wird beim ersten Release/Push erstellt)."
    Ok "Code: $main"
}

function Cmd-Port {
    if ($Names.Count -lt 2) { Fail 'Aufruf: modtool port mod 1.21.4 [-From 1.21.1]' }
    $mod = Find-Mod $Names[0]
    $newMc = $Names[1]
    $variants = @($mod.variants)
    if ($variants | Where-Object { $_.mc -eq $newMc }) { Fail "$($mod.name) hat schon eine Variante für $newMc" }
    $src = if ($From) { $variants | Where-Object { $_.mc -eq $From } | Select-Object -First 1 } else { $variants[0] }
    if (-not $src) { Fail "$($mod.name) hat keine Variante $From" }
    $srcPath = Join-Path $Root $src.path
    $baseName = $src.path -replace " $([regex]::Escape($src.mc))$", ''
    $folder = "$baseName $newMc"
    $dir = Join-Path $Root $folder
    if (Test-Path $dir) { Fail "Ordner $dir gibt es schon" }
    $newBranch = "$($mod.branchPrefix)mc-$newMc"
    Step "Neuer Worktree $folder (Branch $newBranch, Start: $($src.branch))"
    & git.exe -C $srcPath worktree add -b $newBranch $dir $src.branch
    if ($LASTEXITCODE -ne 0) { Fail 'git worktree add fehlgeschlagen' }
    $t = [pscustomobject]@{ Path = $dir }
    Set-Prop $t 'minecraft_version' $newMc
    $v = Get-Prop $t 'mod_version'
    Set-Prop $t 'mod_version' (($v -replace '\+.*$', '') + "+$newMc")
    $cfg = Load-Config
    $m = $cfg.mods | Where-Object { $_.id -eq $mod.id }
    $m.variants = @($m.variants) + [pscustomobject]@{ mc = $newMc; path = $folder; branch = $newBranch }
    Save-Config $cfg
    Ok "Angelegt und eingetragen. Jetzt in $folder\gradle.properties fabric_version/loader_version für $newMc setzen und den Code anpassen."
}

# ------------------------------------------------------------------ features: what is in which Minecraft version

$FeaturesPath = Join-Path $ToolDir 'features.json'

# JSON objects -> ordered hashtables (and arrays stay arrays), so they can be edited and saved again.
function ConvertTo-Hash($o) {
    if ($o -is [System.Management.Automation.PSCustomObject]) {
        $h = [ordered]@{}
        foreach ($p in $o.PSObject.Properties) { $h[$p.Name] = ConvertTo-Hash $p.Value }
        return $h
    }
    if ($o -is [System.Collections.IEnumerable] -and $o -isnot [string]) {
        $list = New-Object System.Collections.ArrayList
        foreach ($x in $o) { [void]$list.Add((ConvertTo-Hash $x)) }
        return , $list
    }
    return $o
}

# modId -> list of @{ name; in = @{ mc -> mod version } }
function Load-Features {
    if (-not (Test-Path $FeaturesPath)) { return [ordered]@{} }
    return ConvertTo-Hash (Get-Content $FeaturesPath -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Save-Features($f) { [IO.File]::WriteAllText($FeaturesPath, ($f | ConvertTo-Json -Depth 8), $Utf8) }

function Get-Mcs($mod) { return @($mod.variants | ForEach-Object { $_.mc }) }

function Get-FeatureList($f, $mod) {
    if (-not $f.Contains($mod.id)) { $f[$mod.id] = New-Object System.Collections.ArrayList }
    return , $f[$mod.id]
}

function Cmd-Feature {
    $action = if ($Names.Count) { $Names[0].ToLower() } else { 'list' }
    $rest = @($Names | Select-Object -Skip 1)
    $f = Load-Features
    switch ($action) {
        { $_ -in 'add', 'set' } {
            if ($rest.Count -lt 2) { Fail 'Aufruf: modtool feature add mod "Name" [-Mc 1.21.1,26.1] [-Version x]' }
            $mod = Find-Mod $rest[0]
            $name = $rest[1]
            $mcs = if ($Mc) { $Mc -split ',' | ForEach-Object { $_.Trim() } } else { @((Get-Mcs $mod)[0]) }
            $list = Get-FeatureList $f $mod
            $item = $list | Where-Object { $_.name -eq $name } | Select-Object -First 1
            if (-not $item) { $item = [ordered]@{ name = $name; in = [ordered]@{} }; [void]$list.Add($item) }
            foreach ($m in $mcs) {
                $t = Get-Target $mod.id $m
                if (-not $t) { Fail "$($mod.name) hat keine Variante $m" }
                $item.in[$m] = if ($Version) { $Version } else { Get-Version $t }
                Ok "$($mod.name) ${m}: '$name' seit $($item.in[$m])"
            }
            Save-Features $f
        }
        'remove' {
            if ($rest.Count -lt 2) { Fail 'Aufruf: modtool feature remove mod "Name" [-Mc 26.1]' }
            $mod = Find-Mod $rest[0]
            $list = Get-FeatureList $f $mod
            $item = $list | Where-Object { $_.name -eq $rest[1] } | Select-Object -First 1
            if (-not $item) { Fail "Feature '$($rest[1])' gibt es bei $($mod.name) nicht" }
            if ($Mc) { foreach ($m in $Mc -split ',') { $item.in.Remove($m.Trim()) } } else { $list.Remove($item) }
            Save-Features $f
            Ok 'entfernt'
        }
        'missing' {
            $mods = if ($rest.Count) { @(Find-Mod $rest[0]) } else { @($Config.mods) }
            $any = $false
            foreach ($mod in $mods) {
                foreach ($item in (Get-FeatureList $f $mod)) {
                    $lacking = @(Get-Mcs $mod | Where-Object { -not $item.in.Contains($_) })
                    if ($lacking.Count) { $any = $true; Warn "$($mod.name): '$($item.name)' fehlt in $($lacking -join ', ')" }
                }
            }
            if (-not $any) { Ok 'Alle Features sind in allen Minecraft-Versionen.' }
        }
        default {
            $mods = if ($action -ne 'list') { @(Find-Mod $Names[0]) } elseif ($rest.Count) { @(Find-Mod $rest[0]) } else { @($Config.mods) }
            foreach ($mod in $mods) {
                Step $mod.name
                $rows = foreach ($item in (Get-FeatureList $f $mod)) {
                    $row = [ordered]@{ Feature = $item.name }
                    foreach ($m in Get-Mcs $mod) { $row["MC $m"] = if ($item.in.Contains($m)) { $item.in[$m] } else { '--- fehlt' } }
                    [pscustomobject]$row
                }
                if ($rows) { $rows | Format-Table -AutoSize | Out-String | Write-Host } else { Info 'noch keine Features eingetragen' }
            }
        }
    }
}

# ------------------------------------------------------------------ overview and dashboard

# Latest GitHub release per variant (tags v<version>; the +mc suffix tells the variant).
function Get-GithubReleases($mod) {
    if ($Offline) { return $null }
    $raw = & gh.exe release list --repo $mod.repo --limit 100 --json tagName,publishedAt 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $raw) { return , @() }
    return , @(($raw -join '') | ConvertFrom-Json)
}

function Get-Overview {
    $state = Load-State
    $features = Load-Features
    $mods = foreach ($mod in $Config.mods) {
        $releases = Get-GithubReleases $mod
        $variants = foreach ($t in Get-Targets | Where-Object { $_.Mod.id -eq $mod.id }) {
            $exists = Test-Path $t.Path
            $version = if ($exists) { Get-Version $t } else { $null }
            $suffixed = $version -and $version.Contains('+')
            $latest = $null
            if ($releases) {
                $tagPrefix = Get-Tag $t ''
                $mine = @($releases | Where-Object { $_.tagName.StartsWith($tagPrefix) -and $(if ($suffixed) { $_.tagName.EndsWith("+$($t.Mc)") } else { -not $_.tagName.Contains('+') }) })
                if ($mine.Count) { $latest = ($mine | Sort-Object publishedAt -Descending | Select-Object -First 1).tagName.Substring($tagPrefix.Length) }
            }
            [ordered]@{
                mc                 = $t.Mc
                path               = $t.Path
                branch             = $t.Branch
                exists             = $exists
                version            = $version
                dirtyFiles         = if ($exists) { Get-Dirty $t } else { 0 }
                changedSinceRelease = if ($exists) { Test-ChangedSinceRelease $t } else { $false }
                built              = $exists -and $state[$t.Key] -eq (Get-Fingerprint $t)
                github             = if ($exists) { Get-PushState $t } else { '-' }
                latestRelease      = if ($releases -eq $null) { 'offline' } elseif ($latest) { $latest } else { 'keins' }
                dependsOn          = @(Get-Deps $t | ForEach-Object { "$($_.Target.Mod.name) $(Get-Version $_.Target)" })
            }
        }
        [ordered]@{
            id        = $mod.id
            name      = $mod.name
            repo      = $mod.repo
            variants  = @($variants)
            features  = @(Get-FeatureList $features $mod)
        }
    }
    $mcs = @($Config.mods | ForEach-Object { $_.variants } | ForEach-Object { $_.mc } | Select-Object -Unique)
    return [ordered]@{ generated = (Get-Date -Format 'yyyy-MM-dd HH:mm'); minecraftVersions = $mcs; mods = @($mods) }
}

function Cmd-Overview {
    $o = Get-Overview
    if ($Json) { $o | ConvertTo-Json -Depth 10; return }
    $rows = foreach ($mod in $o.mods) {
        $row = [ordered]@{ Mod = $mod.name }
        foreach ($mc in $o.minecraftVersions) {
            $v = $mod.variants | Where-Object { $_.mc -eq $mc } | Select-Object -First 1
            $row["MC $mc"] = if (-not $v) { '-' } elseif (-not $v.exists) { 'Ordner fehlt' } else {
                $flags = @()
                if ($v.changedSinceRelease) { $flags += 'geändert' }
                if (-not $v.built) { $flags += 'nicht gebaut' }
                if ($v.github -ne 'aktuell') { $flags += $v.github }
                "$($v.version) (Release: $($v.latestRelease))" + $(if ($flags) { " [" + ($flags -join ', ') + "]" } else { '' })
            }
        }
        [pscustomobject]$row
    }
    Step 'Mods und Minecraft-Versionen'
    $rows | Format-List | Out-String | Write-Host
    $f = Load-Features
    foreach ($mod in $Config.mods) {
        $list = Get-FeatureList $f $mod
        if ($list.Count -eq 0) { continue }
        Step "Features: $($mod.name)"
        $frows = foreach ($item in $list) {
            $r = [ordered]@{ Feature = $item.name }
            foreach ($m in Get-Mcs $mod) { $r["MC $m"] = if ($item.in.Contains($m)) { $item.in[$m] } else { '--- fehlt' } }
            [pscustomobject]$r
        }
        $frows | Format-Table -AutoSize | Out-String | Write-Host
    }
}

function HtmlEsc([string]$s) { return [System.Net.WebUtility]::HtmlEncode($s) }

function Cmd-Dashboard {
    $o = Get-Overview
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append(@"
<!DOCTYPE html><html lang="de"><head><meta charset="utf-8"><title>Netcraft Mods</title>
<style>
body{font-family:Segoe UI,system-ui,sans-serif;background:#16181d;color:#e6e6e6;margin:32px;max-width:1200px}
h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:32px 0 10px;color:#9ecbff}
.sub{color:#8a8f98;font-size:13px}
table{border-collapse:collapse;width:100%;margin-top:8px;font-size:14px}
th,td{border:1px solid #2c3038;padding:8px 10px;text-align:left;vertical-align:top}
th{background:#20242b;color:#b8c0cc;font-weight:600}
td.mod{font-weight:600;white-space:nowrap}
.v{font-size:15px;font-weight:600}.tag{display:inline-block;border-radius:4px;padding:1px 6px;margin:3px 4px 0 0;font-size:12px}
.ok{background:#1f3d2a;color:#7ee2a0}.warn{background:#4a3a14;color:#ffd27a}.bad{background:#4a1f22;color:#ff9a9a}.mute{background:#2a2e36;color:#9aa3ad}
.has{background:#1b2f22;color:#8fe0a8}.miss{background:#3a1d20;color:#ff9a9a;font-weight:600}
a{color:#9ecbff}
.cmd{font-family:Consolas,monospace;background:#20242b;padding:2px 6px;border-radius:4px}
</style></head><body>
<h1>Netcraft-Mods</h1><div class="sub">Stand $(HtmlEsc $o.generated) &middot; erzeugt mit <span class="cmd">modtool dashboard</span></div>
<h2>Mods &times; Minecraft-Versionen</h2><table><tr><th>Mod</th>
"@)
    foreach ($mc in $o.minecraftVersions) { [void]$sb.Append("<th>Minecraft $(HtmlEsc $mc)</th>") }
    [void]$sb.Append('</tr>')
    foreach ($mod in $o.mods) {
        [void]$sb.Append("<tr><td class=""mod"">$(HtmlEsc $mod.name)<br><a class=""sub"" href=""https://github.com/$($mod.repo)"">$(HtmlEsc $mod.repo)</a></td>")
        foreach ($mc in $o.minecraftVersions) {
            $v = $mod.variants | Where-Object { $_.mc -eq $mc } | Select-Object -First 1
            if (-not $v) { [void]$sb.Append('<td><span class="tag mute">nicht vorhanden</span></td>'); continue }
            if (-not $v.exists) { [void]$sb.Append('<td><span class="tag bad">Ordner fehlt</span></td>'); continue }
            $tags = @()
            $tags += if ($v.changedSinceRelease) { '<span class="tag warn">Änderungen seit Release</span>' } else { '<span class="tag ok">wie Release</span>' }
            $tags += if ($v.built) { '<span class="tag ok">gebaut</span>' } else { '<span class="tag warn">nicht gebaut</span>' }
            $cls = if ($v.github -eq 'aktuell') { 'ok' } elseif ($v.github -like '*Commit*') { 'warn' } else { 'bad' }
            $tags += "<span class=""tag $cls"">GitHub: $(HtmlEsc $v.github)</span>"
            if ($v.dirtyFiles -gt 0) { $tags += "<span class=""tag warn"">$($v.dirtyFiles) Datei(en) nicht committet</span>" }
            $deps = if ($v.dependsOn.Count) { "<div class=""sub"">braucht $(HtmlEsc ($v.dependsOn -join ', '))</div>" } else { '' }
            [void]$sb.Append("<td><div class=""v"">$(HtmlEsc $v.version)</div><div class=""sub"">Branch $(HtmlEsc $v.branch) &middot; letztes Release: $(HtmlEsc $v.latestRelease)</div>$($tags -join '')$deps</td>")
        }
        [void]$sb.Append('</tr>')
    }
    [void]$sb.Append('</table>')
    foreach ($mod in $o.mods) {
        $mcs = @($mod.variants | ForEach-Object { $_.mc })
        [void]$sb.Append("<h2>Features: $(HtmlEsc $mod.name)</h2>")
        if (-not $mod.features.Count) { [void]$sb.Append('<div class="sub">Noch keine Features eingetragen (<span class="cmd">modtool feature add</span>).</div>'); continue }
        [void]$sb.Append('<table><tr><th>Feature</th>')
        foreach ($mc in $mcs) { [void]$sb.Append("<th>Minecraft $(HtmlEsc $mc)</th>") }
        [void]$sb.Append('</tr>')
        foreach ($item in $mod.features) {
            [void]$sb.Append("<tr><td>$(HtmlEsc $item.name)</td>")
            foreach ($mc in $mcs) {
                if ($item.in.Contains($mc)) { [void]$sb.Append("<td class=""has"">seit $(HtmlEsc $item.in[$mc])</td>") }
                else { [void]$sb.Append('<td class="miss">fehlt</td>') }
            }
            [void]$sb.Append('</tr>')
        }
        [void]$sb.Append('</table>')
    }
    [void]$sb.Append(@"
<h2>Befehle</h2><table>
<tr><td class="cmd">modtool status</td><td>kurzer Stand aller Varianten</td></tr>
<tr><td class="cmd">modtool build</td><td>baut nur Geändertes, in Abhängigkeits-Reihenfolge</td></tr>
<tr><td class="cmd">modtool run pc</td><td>startet Minecraft mit Netcraft + PC Builder</td></tr>
<tr><td class="cmd">modtool release -Notes "..."</td><td>Version hoch, Build-Check, Changelog, Push, GitHub-Release, Modrinth-Ordner</td></tr>
<tr><td class="cmd">modtool port netcraft 1.21.4</td><td>neue Minecraft-Version einer Mod anlegen</td></tr>
<tr><td class="cmd">modtool new "Netcraft: Name" id</td><td>neues Addon anlegen</td></tr>
<tr><td class="cmd">modtool feature add netcraft "Name" -Mc 1.21.1</td><td>Feature eintragen</td></tr>
</table></body></html>
"@)
    $file = Join-Path $ToolDir 'dashboard.html'
    [IO.File]::WriteAllText($file, $sb.ToString(), $Utf8)
    Ok "Dashboard: $file"
    if (-not $NoPrompt) {
        # .html may be tied to a text editor: open it in a browser explicitly.
        $url = ([Uri]$file).AbsoluteUri
        $opened = $false
        foreach ($browser in @('chrome', 'msedge', 'firefox')) {
            try { Start-Process $browser $url -ErrorAction Stop; $opened = $true; break } catch { }
        }
        if (-not $opened) { Start-Process $file }
    }
}

# ------------------------------------------------------------------ GUI: local web interface (modtool gui)

# Every branch on GitHub, with the mod variant it belongs to (or none).
function Get-BranchList {
    $base = Get-Targets | Where-Object { Test-Path $_.Path } | Select-Object -First 1
    if (-not $Offline) {
        & git.exe -C $base.Path fetch -q --prune origin *> $null
        if (-not $script:DefaultBranch) {
            $script:DefaultBranch = (& gh.exe repo view $Config.repo --json defaultBranchRef -q .defaultBranchRef.name 2>$null) -join ''
        }
    }
    $refs = & git.exe -C $base.Path for-each-ref --format='%(refname:short)|%(objectname:short)|%(contents:subject)|%(committerdate:relative)' refs/remotes/origin
    $list = foreach ($r in $refs) {
        $p = "$r" -split '\|', 4
        $name = $p[0] -replace '^origin/', ''
        if ($name -eq 'HEAD' -or $name -eq 'origin') { continue }
        $t = Get-Targets | Where-Object { $_.Branch -eq $name } | Select-Object -First 1
        [ordered]@{
            name      = $name
            sha       = $p[1]
            subject   = $p[2]
            when      = $p[3]
            mod       = if ($t) { $t.Mod.name } elseif ($name -eq 'tools') { 'modtool (Werkzeug)' } else { $null }
            mc        = if ($t) { $t.Mc } else { $null }
            folder    = if ($t) { $t.Path } elseif ($name -eq 'tools') { $ToolDir } else { $null }
            state     = if ($t -and (Test-Path $t.Path)) { Get-PushState $t } else { '' }
            isDefault = ($name -eq $script:DefaultBranch)
        }
    }
    return @($list)
}

# Runs modtool itself in a child process and returns everything it printed.
function Invoke-Self([string[]]$argv) {
    $quoted = ($argv | Where-Object { $_ -ne $null -and $_ -ne '' } | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join ' '
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'powershell.exe'
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" $quoted -NoPrompt"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
    $psi.EnvironmentVariables['MODTOOL_CHILD'] = '1'
    $p = [System.Diagnostics.Process]::Start($psi)
    $so = $p.StandardOutput.ReadToEndAsync()
    $se = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    $text = $so.Result + $se.Result
    if ($p.ExitCode -ne 0) { $text += "`n(Fehlgeschlagen, Code $($p.ExitCode))`n" }
    return $text
}

function Invoke-GuiAction($a) {
    switch ($a.cmd) {
        'build' { $argv = @('build'); if ($a.key) { $argv += $a.key } }
        'push' { $argv = @('push'); if ($a.key) { $argv += $a.key } }
        'run' {
            Start-Process powershell.exe -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', "`"$PSCommandPath`"", 'run', $a.key)
            return "Minecraft wird in einem eigenen Fenster gebaut und gestartet.`n"
        }
        'release' {
            $argv = @('release', $a.key, '-Bump', $a.bump)
            if ($a.notes) {
                $tmp = Join-Path $env:TEMP "modtool-notes-$([guid]::NewGuid().ToString('N')).txt"
                [IO.File]::WriteAllText($tmp, $a.notes, $Utf8)
                $argv += @('-NotesFile', $tmp)
            }
            if ($a.dry) { $argv += '-DryRun' }
        }
        'port' { $argv = @('port', $a.mod, $a.mc); if ($a.from) { $argv += @('-From', $a.from) } }
        'new' { $argv = @('new', $a.name, $a.id, '-Mc', $a.mc) }
        'feature-add' { $argv = @('feature', 'add', $a.mod, $a.name, '-Mc', $a.mc) }
        'feature-remove' { $argv = @('feature', 'remove', $a.mod, $a.name, '-Mc', $a.mc) }
        default { return "Unbekannte Aktion '$($a.cmd)'`n" }
    }
    return Invoke-Self $argv
}

function Send-Http($stream, [int]$code, [string]$type, [string]$body) {
    $bytes = $Utf8.GetBytes($body)
    $head = "HTTP/1.1 $code OK`r`nContent-Type: $type; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
    $hb = [Text.Encoding]::ASCII.GetBytes($head)
    $stream.Write($hb, 0, $hb.Length)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
}

function Handle-Http($client) {
    $client.ReceiveTimeout = 10000
    $stream = $client.GetStream()
    $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::ASCII)
    $line = $reader.ReadLine()
    if (-not $line) { return }
    $parts = $line.Split(' ')
    $method = $parts[0]
    $path = $parts[1]
    $len = 0
    while ($true) {
        $h = $reader.ReadLine()
        if ($null -eq $h -or $h -eq '') { break }
        if ($h -match '^Content-Length:\s*(\d+)') { $len = [int]$Matches[1] }
    }
    $body = ''
    if ($len -gt 0) {
        $buf = New-Object char[] $len
        $read = 0
        while ($read -lt $len) { $n = $reader.Read($buf, $read, $len - $read); if ($n -le 0) { break }; $read += $n }
        $body = -join $buf[0..($read - 1)]
    }
    if ($path -eq '/' -or $path.StartsWith('/?') -or $path -eq '/index.html') {
        Send-Http $stream 200 'text/html' ([IO.File]::ReadAllText((Join-Path $ToolDir 'gui.html'), [Text.Encoding]::UTF8))
    } elseif ($path -like '/api/state*') {
        $script:Config = Load-Config
        $script:Offline = -not ($path -match 'online=1')
        $state = [ordered]@{ repo = $Config.repo; overview = (Get-Overview); branches = @(Get-BranchList) }
        Send-Http $stream 200 'application/json' ($state | ConvertTo-Json -Depth 10)
    } elseif ($path -eq '/api/run' -and $method -eq 'POST') {
        $a = [Uri]::UnescapeDataString($body) | ConvertFrom-Json
        Info "Aktion: $($a.cmd) $($a.key)$($a.mod)"
        Send-Http $stream 200 'text/plain' (Invoke-GuiAction $a)
    } else {
        Send-Http $stream 404 'text/plain' 'nicht gefunden'
    }
}

function Cmd-Gui {
    $listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback, $Port)
    try { $listener.Start() } catch { Fail "Port $Port ist belegt (läuft modtool gui schon?). Anderer Port: -Port 8770" }
    $url = "http://127.0.0.1:$Port/"
    Step "modtool-Oberfläche läuft auf $url"
    Info 'Dieses Fenster offen lassen. Beenden: Fenster schließen oder Strg+C.'
    if (-not $NoPrompt) {
        $opened = $false
        foreach ($browser in @('chrome', 'msedge', 'firefox')) {
            try { Start-Process $browser $url -ErrorAction Stop; $opened = $true; break } catch { }
        }
        if (-not $opened) { Start-Process $url }
    }
    try {
        while ($true) {
            $client = $listener.AcceptTcpClient()
            try { Handle-Http $client } catch { Warn "Fehler bei einer Anfrage: $_" } finally { $client.Close() }
        }
    } finally {
        $listener.Stop()
    }
}

switch ($Command.ToLower()) {
    'gui' { Cmd-Gui }
    'overview' { Cmd-Overview }
    'dashboard' { Cmd-Dashboard }
    'feature' { Cmd-Feature }
    'features' { Cmd-Feature }
    'status' { Cmd-Status }
    'build' { Cmd-Build }
    'run' { Cmd-Run }
    'release' { Cmd-Release }
    'push' { Cmd-Push }
    'new' { Cmd-New }
    'port' { Cmd-Port }
    default { Get-Help $PSCommandPath; Fail "Unbekannter Befehl '$Command'" }
}

exit 0

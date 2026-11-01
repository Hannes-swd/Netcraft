# Netcraft-Mods: Anleitung für Claude

**Für alle Netcraft-Mods gibt es ein eigenes Werkzeug: modtool.** Alles (Stand ansehen, bauen,
starten, committen, pushen, veröffentlichen, neue Minecraft-Version, neues Addon, Features) läuft
darüber, nicht mit eigenen Gradle-/Git-Befehlen.

- Skript: `C:\Users\hanne\OneDrive\Dokumente\Mc Mods\modtool\modtool.ps1`
- Konfiguration: `modtool\mods.json` (welche Mods, welche Minecraft-Versionen, Ordner, Branches)
- Features: `modtool\features.json` (welches Feature in welcher Minecraft-Version)
- Oberfläche für den Nutzer: Doppelklick auf `Mc Mods\Netcraft modtool.cmd` → http://127.0.0.1:8765
- Andere Ordner hier (EggBall, overworld_snether, vm, waffenmod-template-26.1) gehören nicht dazu.

Der Nutzer schreibt Deutsch und will kurze deutsche Antworten.

## 1. Immer zuerst den Stand holen

```powershell
& "C:\Users\hanne\OneDrive\Dokumente\Mc Mods\modtool\modtool.ps1" overview -Json -NoPrompt
```

Liefert alle Mods und jede Variante (eine pro Minecraft-Version) mit Ordner, Branch, Version,
`changes` (nicht committet / nicht gepusht / seit Release / geänderte Dateien), `jar` (gebaute
Release-Datei, veröffentlicht?, in Downloads?), Build- und GitHub-Stand, letztem GitHub-Release,
Abhängigkeiten, Features und allen GitHub-Branches. Für Menschen lesbar: `modtool changes`.

Wenn Claude modtool aufruft, **immer `-NoPrompt`** (keine Rückfragen, kein Explorer/Browser).

## 2. Oberfläche und Konsole können dasselbe

| In der Oberfläche (`modtool gui`) | In der Konsole |
|---|---|
| Matrix Mods × Minecraft-Versionen | `modtool overview` / `modtool status` |
| „Was ist neu" + Release-Datei auf der Karte | `modtool changes [mod]` |
| Liste aller Branches auf GitHub | `modtool branches` |
| Bauen | `modtool build [mod@mc]` |
| Spiel starten | `modtool run [mod@mc]` |
| Commit & Push … | `modtool commit mod@mc -Notes "Text" -Push` |
| Release … (Probelauf) | `modtool release mod@mc -Notes "..." -Bump patch -DryRun` |
| Release … (echt) | `modtool release mod@mc -Notes "..." -Bump patch\|minor\|major` |
| In Downloads kopieren | `modtool downloads [mod@mc]` |
| Alles Geänderte bauen / Alles pushen | `modtool build` / `modtool push` |
| + Minecraft-Version | `modtool port <mod> <neue-mc> -From <mc>` |
| + Neues Addon | `modtool new "Netcraft: Name" <id> -Mc <mc>` |
| Feature eintragen / „fehlt" anklicken | `modtool feature add <mod> "Name" -Mc 1.21.1[,26.1]` |
| Feature austragen | `modtool feature remove <mod> "Name" -Mc <mc>` |
| Feature-Tabelle | `modtool feature list` / `modtool feature missing` |
| „Was ist auf Modrinth?" (✓ = neuester Commit hochgeladen) | `modtool modrinth` |
| Modrinth-Feld anklicken → Commit wählen | `modtool modrinth commits mod@mc` / `modtool modrinth set mod@mc [-Commit sha \| -Version x]` |
| „Nichts auf Modrinth" | `modtool modrinth clear mod@mc` |

Mods per ID, Name oder Teil davon (`netcraft`, `pc`); eine Variante mit `mod@1.21.1` oder `-Mc 1.21.1`.
Neue Funktionen im Tool immer in **beiden** einbauen (Konsole und `gui.html`) und hier eintragen.

## 3. Aufbau der Mods

| Mod | Mod-ID | Tag-Kürzel | Minecraft | Ordner (in `Mc Mods`) | Branch |
|---|---|---|---|---|---|
| Netcraft | `virtual_mashine` | `netcraft` | 1.21.1 (Hauptversion) | `virtual mashine 1.21.1` | `mc-1.21.1` |
| Netcraft | `virtual_mashine` | `netcraft` | 26.1 (alt, steht auf 1.10.0) | `virtual mashine` | `main` |
| Netcraft: PC Builder | `netcraft_pc_builder` | `pc-builder` | 1.21.1 | `netcraft pc builder 1.21.1` | `pc-builder/mc-1.21.1` |

Aktuell und vollständig steht es immer in `modtool\mods.json`.

- **Varianten:** pro Minecraft-Version ein eigener Ordner (git worktree) und Branch. Netcraft-Branches
  heißen `mc-<version>`, Addon-Branches `<kürzel>/mc-<version>`.
- **Versionen:** `X.Y.Z+<mc>` in `gradle.properties` (`mod_version`). Jar-Namen automatisch aus
  `archives_base_name` + Version, z. B. `netcraft-1.13.0+1.21.1.jar`.
- **Abhängigkeiten:** Netcraft ist die Basis und läuft allein (empfiehlt Addons per `suggests`). Addons
  hängen von Netcraft ab (`dependsOn`, Property `netcraft_version`) und bauen gegen Netcraft aus
  `mavenLocal`; modtool baut immer in der richtigen Reihenfolge und testet Abhängige mit.
- **Schnittstelle:** Addons klinken sich nur über `dev.hannes.virtual_mashine.api.NetcraftApi`
  (Start-Prüfung: VM-Start erlauben/verbieten, Hardware setzen) und den geschützten
  `DeviceBlockEntity(type, pos, state)`-Konstruktor ein. **Addon-Code gehört nie in Netcraft**; fehlt
  ein Haken, wird die API erweitert.
- **Sprachen:** Alle Texte zweisprachig (en_us + de_de), nie fest eingebaute UI-Texte, nie eine
  Sprache löschen.

## 4. Wo Dateien landen

- Gebaut wird in `%LOCALAPPDATA%\netcraft-modtool\build` (OneDrive sperrt Build-Ordner).
- Test-Builds: `Mc Mods\Releases\latest\<mc>\`.
- Releases: `Mc Mods\Releases\release <Datum>\` (Jars + `MODRINTH.md` mit Texten für Modrinth) und
  **zusätzlich nach Downloads** (`uploadDir` in mods.json) zum Hochladen auf Modrinth.
- Test-Builds nie nach Downloads, nur Release-Dateien bzw. auf Wunsch (`modtool downloads`).

## 5. GitHub

- **Ein Repo für alle Mods:** https://github.com/Hannes-swd/Netcraft (früher `Network-Simulation`; lokale
  Remotes zeigen noch auf die alte URL, GitHub leitet weiter). Der Nutzer will **kein** Repo pro Mod.
- Branches: `main` (Netcraft 26.1), `mc-1.21.1` (Netcraft 1.21.1), `pc-builder/mc-1.21.1`, `tools` (modtool).
- **Branches werden nie zusammengeführt**, keine Pull Requests zwischen Mod-Branches. (Am 2026-11-01 wurde
  aus Versehen `mc-1.21.1` in `main` gemergt und per Revert `c9cbda0` rückgängig gemacht.)
- Release-Tags: `<kürzel>-v<version>`, z. B. `netcraft-v1.13.0+1.21.1`.
- Änderungen am Tool selbst: im Ordner `modtool` committen, `README.md` = Kopie dieser Datei,
  `git push origin tools`.

## 6. Regeln für Claude

- Nach jeder neuen Funktion in einer Mod: `modtool feature add` für die Variante(n), in denen sie drin ist.
- Nach Mod-Arbeit will der Nutzer meist das Spiel gestartet haben (`modtool run`). Minecraft nie hart
  beenden (laufende VMs werden sonst nicht gesichert).
- Commit/Push/Release sind öffentlich: nur, wenn der Nutzer es will; vorher `release -DryRun` zeigen und
  die Changelog-Texte (`-Notes`) mit ihm abstimmen.
- Was auf Modrinth liegt, merkt sich `modtool\modrinth.json` (von Hand markiert, pro Variante ein Commit).
  Lädt der Nutzer etwas hoch, mit `modtool modrinth set mod@mc` eintragen.
- Geplant, aber noch nicht gebaut: direkter Upload zu Modrinth (braucht einen Modrinth-Token des Nutzers,
  nur lokal gespeichert, und ein Modrinth-Projekt für den PC Builder).

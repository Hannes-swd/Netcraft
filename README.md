# Netcraft-Mods: Anleitung für Claude

Alle Netcraft-Mods (Netcraft und seine Addons) werden mit **modtool** verwaltet:
`modtool\modtool.ps1` (Aufruf auch `modtool\modtool.cmd`), Konfiguration `modtool\mods.json`,
Feature-Liste `modtool\features.json`. Andere Ordner hier (EggBall, overworld_snether, vm,
waffenmod-template-26.1) gehören nicht dazu.

Der Nutzer schreibt Deutsch und will kurze deutsche Antworten.

## 1. Immer zuerst den Stand holen

```powershell
& "C:\Users\hanne\OneDrive\Dokumente\Mc Mods\modtool\modtool.ps1" overview -Json -NoPrompt
```

Liefert für jede Mod jede Variante (eine pro Minecraft-Version): Ordner, Branch, Mod-Version,
nicht committete Dateien, Änderungen seit dem letzten Release, Build-Stand, GitHub-Stand, letztes
GitHub-Release, Abhängigkeiten, und welche Features in welcher Minecraft-Version drin sind.
Wenn Claude modtool aufruft, **immer `-NoPrompt`** (keine Rückfragen, kein Explorer/Browser).

## 2. Aufbau

| Mod | Mod-ID | Tag-Kürzel | Minecraft | Ordner (in `Mc Mods`) | Branch |
|---|---|---|---|---|---|
| Netcraft | `virtual_mashine` | `netcraft` | 1.21.1 (Hauptversion) | `virtual mashine 1.21.1` | `mc-1.21.1` |
| Netcraft | `virtual_mashine` | `netcraft` | 26.1 (alt, 1.10.0) | `virtual mashine` | `main` |
| Netcraft: PC Builder | `netcraft_pc_builder` | `pc-builder` | 1.21.1 | `netcraft pc builder 1.21.1` | `pc-builder/mc-1.21.1` |

Die aktuelle, vollständige Liste steht immer in `modtool\mods.json`.

- **Varianten:** Jede Mod hat pro Minecraft-Version einen eigenen Ordner (git worktree) und Branch.
  Netcraft-Branches heißen `mc-<version>`, Addon-Branches `<kürzel>/mc-<version>`.
- **Versionen:** `X.Y.Z+<mc>` in `gradle.properties` (`mod_version`). Jar-Namen kommen automatisch aus
  `archives_base_name` + Version (z. B. `netcraft-1.13.0+1.21.1.jar`).
- **Abhängigkeiten:** Netcraft ist die Basis und läuft allein (empfiehlt die Addons per `suggests`).
  Addons hängen von Netcraft ab (`dependsOn` in mods.json, Property `netcraft_version`) und bauen gegen
  Netcraft aus `mavenLocal` (modtool veröffentlicht Netcraft dort beim Bauen).
- **Schnittstelle:** Addons klinken sich nur über `dev.hannes.virtual_mashine.api.NetcraftApi`
  (Start-Prüfung: VM-Start erlauben/verbieten, Hardware setzen) und den geschützten
  `DeviceBlockEntity(type, pos, state)`-Konstruktor ein. **Addon-Code gehört nie in Netcraft**; fehlt
  ein Haken, wird die API erweitert.
- **Sprachen:** Alle Texte zweisprachig (en_us + de_de), nie fest eingebaute UI-Texte, nie eine
  Sprache löschen.

## 3. GitHub

- **Ein Repo für alle Mods:** https://github.com/Hannes-swd/Netcraft (früher `Network-Simulation`; die
  lokalen Remotes zeigen noch auf die alte URL, GitHub leitet weiter). Der Nutzer will **kein** Repo pro Mod.
- Branches: `main` (Netcraft 26.1), `mc-1.21.1` (Netcraft 1.21.1), `pc-builder/mc-1.21.1`, `tools` (modtool).
- **Branches werden nie zusammengeführt.** Keine Pull Requests zwischen Mod-Branches. (Am 2026-11-01 wurde
  aus Versehen `mc-1.21.1` in `main` gemergt und per Revert-Commit `c9cbda0` rückgängig gemacht.)
- Release-Tags: `<kürzel>-v<version>`, z. B. `netcraft-v1.13.0+1.21.1`, `pc-builder-v1.0.0+1.21.1`.
- Änderungen am Tool selbst: im Ordner `modtool` committen und `git push origin tools`.

## 4. Befehle

| Befehl | Zweck |
|---|---|
| `modtool status` | kurzer Stand aller Varianten |
| `modtool overview [-Json] [-Offline]` | Matrix Mods × Minecraft-Versionen + Features |
| `modtool dashboard` | dasselbe als Webseite `modtool\dashboard.html` (nur Ansicht) |
| `modtool build [mod] [-Mc x] [-All]` | baut nur Geändertes, Abhängigkeiten zuerst; Jars nach `Mc Mods\Releases\latest\<mc>` |
| `modtool run [mod] [-Mc x]` | baut und startet Minecraft mit Mod + Abhängigkeiten (Testwelt im `run` der Basis-Mod) |
| `modtool release [mod] [-Mc x] [-Notes "..."] [-Bump patch\|minor\|major] [-DryRun]` | Version hoch, alles zusammen bauen (Abhängige werden mitgetestet), CHANGELOG.md, Commit, Tag, Push, GitHub-Release mit Jar, Ordner `Mc Mods\Releases\release <Datum>` mit Jars + MODRINTH.md |
| `modtool push [mod]` | Commits aller Varianten pushen |
| `modtool port <mod> <neue-mc> [-From <mc>]` | neue Minecraft-Version einer Mod als Worktree + Branch anlegen |
| `modtool new "Netcraft: Name" <id> [-Mc x]` | neues Addon anlegen (Ordner, Code-Gerüst, Branch, Eintrag) |
| `modtool feature add <mod> "Name" -Mc 1.21.1[,26.1] [-Version x]` | Feature eintragen |
| `modtool feature missing [mod]` | welche Features in welcher Minecraft-Version fehlen (Port-Liste) |

Mods per ID, Name oder Teil davon (`netcraft`, `pc`); eine Variante mit `mod@1.21.1` oder `-Mc 1.21.1`.

## 5. Regeln für Claude

- Nach jeder neuen Funktion: `modtool feature add` für die Variante(n), in denen sie drin ist.
- Bauen und Starten immer über modtool (es baut in `%LOCALAPPDATA%\netcraft-modtool\build`, weil OneDrive
  Build-Ordner sperrt). Jars kommen nach `Mc Mods\Releases`, **nicht** nach Downloads.
- Nach Mod-Arbeit will der Nutzer meist das Spiel gestartet haben (`modtool run`). Minecraft nie hart
  beenden (laufende VMs werden sonst nicht gesichert).
- Push und Releases sind öffentlich: nur, wenn der Nutzer es will; vorher `release -DryRun` zeigen.
- Vor einem Release die Changelog-Texte mit dem Nutzer abstimmen (`-Notes`).

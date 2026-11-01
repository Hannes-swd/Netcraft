# Netcraft-Mods: Arbeitsweise

Alle Netcraft-Mods (Netcraft und seine Addons) werden mit **modtool** verwaltet: `modtool\modtool.ps1`
(Konfiguration `modtool\mods.json`, Feature-Liste `modtool\features.json`). Andere Ordner hier
(EggBall, overworld_snether, vm, waffenmod-template-26.1) gehören nicht dazu.

## Erst den Stand holen

```powershell
& "C:\Users\hanne\OneDrive\Dokumente\Mc Mods\modtool\modtool.ps1" overview -Json -NoPrompt
```

Liefert alle Mods, ihre Varianten pro Minecraft-Version (Ordner, Branch, Mod-Version, Änderungen seit
Release, Build-Stand, GitHub-Stand, letztes GitHub-Release, Abhängigkeiten) und welche Features in
welcher Minecraft-Version drin sind. Beim Aufruf durch Claude immer `-NoPrompt` mitgeben (keine
Rückfragen, kein Explorer/Browser).

## Aufbau

- Jede Mod hat **eine Variante pro Minecraft-Version**: eigener Ordner (git worktree) + Branch
  `mc-<version>` (Ausnahme: Netcraft 26.1 liegt in `virtual mashine` auf `main`).
- Versionen: `X.Y.Z+<mc>` in `gradle.properties` (`mod_version`), Git-Tags `v<mod_version>`.
- **Netcraft** (`virtual_mashine`) ist die Basis und läuft allein. Addons hängen davon ab
  (`dependsOn` in mods.json, Property `netcraft_version`) und bauen gegen Netcraft aus `mavenLocal`.
- Addons klinken sich nur über `dev.hannes.virtual_mashine.api.NetcraftApi` und den geschützten
  `DeviceBlockEntity(type, pos, state)`-Konstruktor ein. Addon-Code gehört nie in Netcraft; fehlt ein
  Haken, wird die API erweitert.
- Alle Texte zweisprachig (en_us + de_de), keine fest eingebauten UI-Texte.

## Befehle

| Befehl | Zweck |
|---|---|
| `modtool status` | kurzer Stand aller Varianten |
| `modtool overview [-Json]` | Matrix Mods × Minecraft-Versionen + Features |
| `modtool dashboard` | dasselbe als Webseite `modtool\dashboard.html` |
| `modtool build [mod] [-Mc x] [-All]` | baut nur Geändertes, Abhängigkeiten zuerst; Jars nach `Mc Mods\Releases\latest\<mc>` |
| `modtool run [mod] [-Mc x]` | baut und startet Minecraft mit Mod + Abhängigkeiten (gemeinsame Testwelt im `run` der Basis-Mod) |
| `modtool release [mod] [-Notes "..."] [-Bump patch\|minor\|major] [-DryRun]` | Version hoch, alles zusammen bauen (Abhängige werden mitgetestet), CHANGELOG.md, Commit, Tag, Push, GitHub-Release mit Jar, Ordner mit Jars + MODRINTH.md |
| `modtool push [mod]` | Commits pushen (legt fehlende GitHub-Repos an) |
| `modtool port <mod> <neue-mc> [-From <mc>]` | neue Minecraft-Version als Worktree anlegen |
| `modtool new "Netcraft: Name" <id> [-Mc x]` | neues Addon anlegen und eintragen |
| `modtool feature add <mod> "Name" -Mc 1.21.1[,26.1] [-Version x]` | Feature eintragen; `feature missing` zeigt Port-Lücken |

Mods per id, Name oder Teil davon (`netcraft`, `pc`), Variante mit `mod@1.21.1` oder `-Mc`.

## Regeln für Claude

- Nach jeder neuen Funktion: `modtool feature add` für die Variante(n), in denen sie drin ist.
- Bauen immer über modtool (baut in `%LOCALAPPDATA%\netcraft-modtool\build`, weil OneDrive Build-Ordner sperrt).
- Releases/Pushes nur, wenn der Nutzer es will; vorher `-DryRun` zeigen ist gut.

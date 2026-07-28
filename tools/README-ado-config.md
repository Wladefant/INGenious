# `ado-config.json` — welches Azure DevOps dieser Rechner meint

Die Werkzeuge und die Studio-Panels reden mit **einem** Azure DevOps. Welches das ist —
Organisation, Projekt, Testplan und Entra-Mandant — steht **nicht im Quelltext** und wird
dort auch nie stehen: dieses Repository ist öffentlich, und ein öffentliches Repository, das
die Organisation nennt, hat sie veröffentlicht, ob das jemand wollte oder nicht.

Stattdessen liest jedes Werkzeug **eine Datei**, die beim Einrichten einmal geschrieben wird.

## Wo die Datei liegt

| Betriebssystem | Pfad |
|---|---|
| Windows | `%LOCALAPPDATA%\IngQaAutopilot\ado-config.json` |
| sonst | `~/.IngQaAutopilot/ado-config.json` |

Das ist derselbe Ordner wie der Token-Zwischenspeicher (`token.json`) und der Panel-Cache
(`ado-testcases.json`). Er liegt **außerhalb** des Repositories — die Datei kann also nicht
versehentlich eingecheckt werden.

## Was drinsteht

```json
{
  "org":      "meine-ado-organisation",
  "project":  "MeinProjekt",
  "planId":   1234567,
  "tenantId": "00000000-0000-0000-0000-000000000000"
}
```

| Schlüssel | Was es ist | Wo Sie es finden |
|---|---|---|
| `org` | Azure-DevOps-Organisation | in der Adresszeile: `https://dev.azure.com/<org>/…` |
| `project` | Azure-DevOps-Projekt | in der Adresszeile: `…/<org>/<project>/…` |
| `planId` | Ihr **Testplan** | Test Plans → der Plan → die Zahl in der Adresszeile |
| `tenantId` | Entra-Mandant, auf den sich `az login` bezieht | `az account show --query tenantId -o tsv`, oder bei Ihrer IT |

`suiteId` ist optional; ohne Angabe wird die Suite zum Testfall automatisch gesucht.

## Anlegen

PowerShell, einmalig:

```powershell
$dir = Join-Path $env:LOCALAPPDATA 'IngQaAutopilot'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
@{
  org      = 'meine-ado-organisation'
  project  = 'MeinProjekt'
  planId   = 1234567
  tenantId = '00000000-0000-0000-0000-000000000000'
} | ConvertTo-Json | Set-Content (Join-Path $dir 'ado-config.json') -Encoding UTF8
```

## Umgebungsvariablen gehen vor

Für einen einzelnen Aufruf — oder auf einem Pipeline-Agenten, der keine Datei hat — gewinnt
die Umgebung über die Datei:

| Variable | Schlüssel |
|---|---|
| `ADO_ORG` | `org` |
| `ADO_PROJECT` | `project` |
| `ADO_TEST_PLAN_ID` | `planId` |
| `ADO_TENANT_ID` | `tenantId` |
| `ADO_TEST_SUITE_ID` | `suiteId` |
| `ING_ADO_CONFIG` | Pfad der Datei selbst (nur Java-Seite) |

Auf der Kommandozeile schlagen `--org` / `--project` / `--plan` von `ado-testcases.mjs`
beides.

## Wenn nichts eingerichtet ist

Es gibt **keine Voreinstellung**, und das ist Absicht. Eine Voreinstellung wäre entweder die
Organisation von jemand anderem — genau das, was hier vermieden wird — oder eine falsche, und
eine falsche scheitert an einem echten Azure DevOps mit einem 404, der wie ein kaputter
Testfall aussieht und nicht wie eine fehlende Einstellung.

Stattdessen sagen alle Teile denselben Satz, mit dem Namen der fehlenden Einstellung:

```
Nicht eingerichtet: org fehlt. Bitte C:\Users\<Sie>\AppData\Local\IngQaAutopilot\ado-config.json
anlegen (Schluessel "org") oder die Umgebungsvariable ADO_ORG setzen.
Siehe tools/README-ado-config.md.
```

## Was **keine** Einstellung ist

`499b84ac-1321-427f-aa17-267ca6975798` steht als Konstante im Quelltext und bleibt dort. Das
ist Microsofts eigene, veröffentlichte Anwendungs-ID für Azure DevOps — die Ressource, **für**
die ein Token ausgestellt wird. Sie ist für jeden Azure-DevOps-Kunden dieselbe und bezeichnet
Microsofts Dienst, nicht Ihren Mandanten.

## Wer die Datei liest

| Teil | Stelle |
|---|---|
| Node-Werkzeuge | `ing-qa-recorder/mvp/ado-automark.mjs` → `CONFIG_FILE` / `CFG` |
| Studio-Plugin | `ingenious-plugin/src/main/java/de/ing/qa/studio/AdoConfig.java` |

Beide zeigen bewusst auf **denselben** Pfad: das Panel und die Werkzeuge, die es startet,
können so nie unterschiedlicher Meinung darüber sein, mit welchem Azure DevOps sie reden.

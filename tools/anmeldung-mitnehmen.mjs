/**
 * anmeldung-mitnehmen.mjs — die gespeicherte Anmeldung erreicht Aufnahme und Testlauf.
 *
 * WARUM ES DAS GIBT
 * -----------------
 * `session-anmelden.mjs` speichert eine angemeldete Sitzung; bis hierher hat genau EIN
 * Knopf sie benutzt — "Aufnahme prüfen". Aufnahme und Testlauf, also die beiden Dinge,
 * die eine Testerin den ganzen Tag macht, haben sie nie gesehen und sind jedes Mal auf
 * der Anmeldeseite gelandet. Der Wunsch dahinter ist wörtlich: nicht jedes Mal wieder
 * auf dieselbe Seite schauen müssen.
 *
 * WIE, GEMESSEN STATT VERMUTET
 * ----------------------------
 * INGenious kennt den Mechanismus für den TESTLAUF bereits, und er hat für die
 * Zielanwendung wirklich gelaufen: `Settings/BrowserContexts/<kontext>.properties` mit
 * `useStorageState=true` und `storageStatePath=<datei>`. Gelesen wird das in
 * `PlaywrightDriverFactory.setStorageStateIfEnabled` — und zwar gutmütig: eine Datei,
 * die es nicht gibt, wird übersprungen statt den Lauf abzubrechen, und die benutzte
 * Datei steht mit "Storage State used" im Lauf-Protokoll. Deshalb ist dieses Werkzeug
 * kein Umbau an der Engine, sondern schreibt nur diese eine Datei.
 *
 * WAS DIESE DATEI NICHT ERREICHT — und das ist gemessen, nicht angenommen: den
 * REKORDER. Studio nimmt mit `com.microsoft.playwright.CLI codegen` in einem eigenen
 * Prozess auf (`TestCaseComponent.launchPlaywright`), und der liest die
 * Browser-Kontexte nicht — kein einziges Vorkommen von `useStorageState` ausserhalb der
 * Engine. Für die Aufnahme braucht codegen `--load-storage`; siehe
 * `docs/reference/ANMELDUNG-ERREICHT-AUFNAHME-UND-LAUF.md`.
 *
 * DIESES WERKZEUG BESITZT ZWEI SCHLÜSSEL
 * --------------------------------------
 * `useStorageState` und `storageStatePath` gehören ab seiner ersten Benutzung ihm. Es
 * schreibt beide bei jedem Lauf und lässt alle anderen Schlüssel, ihre Reihenfolge und
 * ihre Kommentare unangetastet. Ein von Hand eingetragener Pfad wird also überschrieben
 * — das ist Absicht: zwei Stellen, die dasselbe entscheiden, sind der Grund, warum
 * niemand mehr sagen kann, warum ein Lauf auf der Anmeldeseite steht.
 *
 *   node tools/anmeldung-mitnehmen.mjs --projekt <projektverzeichnis>
 *   node tools/anmeldung-mitnehmen.mjs --projekt <verzeichnis> --aus
 *
 * Ausgabe (die erste Zeile liest das Panel, die zweite liest ein Mensch):
 *   ANMELDUNG <ZUSTAND> <BEFUND> <datei|-> <geaendert|unveraendert>
 *   <ein deutscher Satz>
 *
 * Rückgabewerte: 0 die Anmeldung wird jetzt mitgenommen · 1 sie wird nicht mitgenommen,
 * und der Satz sagt warum · 2 es konnte gar nichts getan werden.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { cookieVerdict, defaultStatePath } from './session-anmelden.mjs';
import { config } from './lib/ing-config.mjs';

/** Die beiden Schlüssel, die dieses Werkzeug besitzt. */
const SCHLUESSEL_AN = 'useStorageState';
const SCHLUESSEL_PFAD = 'storageStatePath';

/**
 * Was die Engine anlegt, wenn es die Datei noch nicht gibt (`ContextSettings.loadDefault`).
 * Nachgebildet statt weggelassen: eine Datei mit nur zwei Schlüsseln ist eine Datei, der
 * eine spätere Studio-Version Schlüssel hinzufügt, die wir dann nicht kennen.
 */
const NEUE_DATEI = [
  'authenticateContext=false',
  'userID=',
  'password=',
  `${SCHLUESSEL_AN}=false`,
  `${SCHLUESSEL_PFAD}=`,
];

/**
 * Befunde, mit denen die gespeicherte Datei mitgegeben wird — und die einzige Ausnahme.
 *
 * GUELTIG und SITZUNGSKENNUNG sind die erwarteten Fälle. ABGELAUFEN und KEINE werden
 * ABSICHTLICH auch mitgegeben, und aus demselben Grund, aus dem das Panel sie seit
 * jeher an die Prüfung weiterreicht (`SessionSignIn.Result.usable`): in derselben Datei
 * steht die Anmeldung beim Identitätsanbieter, die Monate hält. Sie kostet nichts und
 * kann der Testerin das Passwort ersparen, auch wenn die Sitzung der Anwendung selbst
 * längst tot ist.
 *
 * UNLESBAR ist die Ausnahme, und zwar keine Geschmacksfrage: die Engine prüft nur, ob
 * die Datei EXISTIERT, und Playwright bricht dann beim Laden ab. Der Lauf würde also gar
 * nicht erst anfangen — schlechter als die Anmeldeseite, auf der man wenigstens sieht,
 * was zu tun ist.
 */
const MITGEBEN = new Set(['GUELTIG', 'SITZUNGSKENNUNG', 'ABGELAUFEN', 'KEINE']);

function parseArgs(argv) {
  const out = {
    projekt: null,
    kontext: 'default',
    zustand: null,
    aus: false,
    dryRun: false,
    auchImRepo: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${argv[i - 1]} braucht einen Wert`);
      return v;
    };
    switch (argv[i]) {
      case '--projekt': case '--project': out.projekt = next(); break;
      case '--kontext': case '--context': out.kontext = next(); break;
      case '--zustand': case '--state': out.zustand = next(); break;
      case '--aus': out.aus = true; break;
      case '--dry-run': out.dryRun = true; break;
      case '--auch-im-repo': out.auchImRepo = true; break;
      case '--help': case '-h': out.help = true; break;
      default: throw new Error(`unbekanntes Argument: ${argv[i]}`);
    }
  }
  return out;
}

/**
 * Die Einstellungsdatei des Browser-Kontexts eines Projekts.
 *
 * @param projekt das Projektverzeichnis (das mit `Settings/` darin)
 */
export function kontextDatei(projekt, kontext = 'default') {
  return join(projekt, 'Settings', 'BrowserContexts', `${kontext}.properties`);
}

/**
 * Wie die Engine einen Pfad geschrieben haben will.
 *
 * Vorwärtsschrägstriche, weil eine Java-`.properties`-Datei den Rückwärtsschrägstrich als
 * Fluchtzeichen liest: `C:\Users\…` käme als `C:Users…` wieder heraus, und der Lauf
 * meldete dann völlig korrekt, dass es diese Datei nicht gibt.
 */
export function alsPfadWert(datei) {
  return String(datei).replace(/\\/g, '/');
}

/**
 * Die beiden Schlüssel in einem Properties-Text setzen, alles andere unverändert lassen.
 *
 * Zeilenweise statt über `Properties.load`/`store`, weil ein Rundlauf durch Java die
 * Reihenfolge wirft, Kommentare frisst und einen Zeitstempel hinschreibt — drei
 * Änderungen an einer Datei, an der wir zwei Werte ändern wollten.
 *
 * @returns {string} der neue Text
 */
export function setzeSchluessel(text, werte) {
  const zeilen = text.split(/\r?\n/);
  const offen = new Map(Object.entries(werte));
  const neu = zeilen.map((zeile) => {
    const m = zeile.match(/^(\s*)([^#!=:\s][^=:]*)([=:])/);
    if (!m) return zeile;
    const name = m[2].trim();
    if (!offen.has(name)) return zeile;
    const wert = offen.get(name);
    offen.delete(name);
    return `${m[1]}${name}${m[3]}${wert}`;
  });
  // Ein Schlüssel, den die Datei nicht hatte, wird angehängt statt verschwiegen: eine
  // ältere Datei ohne `storageStatePath` würde sonst still ohne Anmeldung laufen.
  for (const [name, wert] of offen) {
    if (neu.length && neu[neu.length - 1] === '') neu.splice(neu.length - 1, 0, `${name}=${wert}`);
    else neu.push(`${name}=${wert}`);
  }
  return neu.join('\n');
}

/**
 * Liegt dieses Projekt in diesem Repository?
 *
 * WARUM DAS ZÄHLT. Was hier geschrieben wird, nennt EINEN Rechner und den Ort einer
 * Datei, die eine lebende Sitzung enthält. In einer versionierten Datei reist beides zu
 * jedem, der das Repository klont — und dass das kein erfundenes Risiko ist, steht in
 * diesem Repository: ein Beispielprojekt trägt seit Monaten einen absoluten
 * Benutzerpfad in seiner Kontextdatei eingecheckt mit sich herum.
 *
 * Die Projekte, mit denen wirklich gearbeitet wird, liegen nie hier drin — das
 * Repository ist der Werkzeugkasten, nicht der Arbeitsplatz. Was hier drin liegt, sind
 * Beispiele und die Demo. Für die gibt es `--auch-im-repo`, damit die Demo genau das
 * vorführen kann, worum es geht.
 */
export function imRepo(projekt) {
  const wurzel = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const rel = relative(wurzel, resolve(projekt));
  return rel !== '' && !rel.startsWith('..') && !isAbsolute(rel);
}

/** Ein Satz für einen Menschen, je Befund. */
function satz(befund, datei, host) {
  const fuer = host ? ` für ${host}` : '';
  switch (befund) {
    case 'GUELTIG':
      return `Die gespeicherte Anmeldung${fuer} wird für Aufnahme und Testlauf mitgenommen.`;
    case 'SITZUNGSKENNUNG':
      return `Die gespeicherte Anmeldung${fuer} wird mitgenommen. Ob sie noch gilt, `
        + 'entscheidet die Anwendung — landet der Lauf auf der Anmeldeseite, bitte erneut '
        + 'anmelden.';
    case 'ABGELAUFEN':
      return `Die Sitzung${fuer} ist abgelaufen. Die Datei wird trotzdem mitgenommen: die `
        + 'Anmeldung beim Identitätsanbieter hält darin länger und kann das Passwort '
        + 'ersparen. Erscheint die Anmeldeseite, bitte erneut anmelden.';
    case 'KEINE':
      return `In der gespeicherten Datei steht keine Sitzung${fuer}. Sie wird trotzdem `
        + 'mitgenommen, weil die Anmeldung beim Identitätsanbieter darin steht. Erscheint '
        + 'die Anmeldeseite, bitte erneut anmelden.';
    case 'UNLESBAR':
      return `Die gespeicherte Anmeldung (${datei}) ist nicht lesbar und wird nicht `
        + 'mitgenommen — ein Lauf würde daran abbrechen statt auf der Anmeldeseite zu landen.';
    default:
      return 'Es wird keine Anmeldung mitgenommen.';
  }
}

/**
 * Die Entscheidung und der Schreibvorgang, ohne Ausgabe — damit ein Prüfstand sie
 * befragen kann, ohne stdout zu lesen.
 *
 * @returns {{zustand: string, befund: string, datei: string, geaendert: boolean,
 *   satz: string, code: number, ziel: string}}
 */
export function mitnehmen(args) {
  const projekt = args.projekt ? resolve(args.projekt) : null;
  if (!projekt || !existsSync(projekt)) {
    return {
      zustand: 'KEIN-PROJEKT', befund: '-', datei: '-', geaendert: false, ziel: '-',
      satz: `Das Projektverzeichnis gibt es nicht: ${args.projekt ?? '(keins angegeben)'}`,
      code: 2,
    };
  }
  const ziel = kontextDatei(projekt, args.kontext);

  const zustandsDatei = args.zustand ? resolve(args.zustand) : defaultStatePath();
  const vorhanden = existsSync(zustandsDatei);
  const befund = args.aus || !vorhanden
    ? '-'
    : cookieVerdict(zustandsDatei, config().appUrl).kind;
  const host = args.aus || !vorhanden
    ? '' : cookieVerdict(zustandsDatei, config().appUrl).host;

  const mitgeben = !args.aus && vorhanden && MITGEBEN.has(befund);
  let zustand;
  let text;
  if (args.aus) {
    zustand = 'AUS';
    text = 'Die gespeicherte Anmeldung wird bewusst nicht mitgenommen — Aufnahme und '
      + 'Testlauf beginnen auf der Anmeldeseite.';
  } else if (!vorhanden) {
    zustand = 'KEINE-DATEI';
    text = 'Es ist keine Anmeldung gespeichert. Aufnahme und Testlauf beginnen auf der '
      + 'Anmeldeseite.';
  } else if (mitgeben) {
    zustand = 'MITGENOMMEN';
    text = satz(befund, zustandsDatei, host);
  } else {
    zustand = 'NICHT-MITGENOMMEN';
    text = satz(befund, zustandsDatei, host);
  }

  if (imRepo(projekt) && !args.auchImRepo) {
    return {
      zustand: 'IM-REPO', befund, datei: mitgeben ? zustandsDatei : '-', geaendert: false, ziel,
      satz: `${ziel} gehört zum Repository. Der Pfad, der hier stehen müsste, nennt diesen `
        + 'einen Rechner und den Ort einer lebenden Sitzung; in einer versionierten Datei '
        + 'reist beides zu jedem, der das Repository klont. Es wurde nichts geschrieben.',
      code: 2,
    };
  }

  const werte = {
    [SCHLUESSEL_AN]: mitgeben ? 'true' : 'false',
    [SCHLUESSEL_PFAD]: mitgeben ? alsPfadWert(zustandsDatei) : '',
  };
  const alt = existsSync(ziel) ? readFileSync(ziel, 'utf8') : NEUE_DATEI.join('\n') + '\n';
  const neu = setzeSchluessel(alt, werte);
  // Unverändert heisst NICHT geschrieben. Das Panel ruft das hier jedes Mal auf, wenn es
  // den Anmeldezustand feststellt; ein Schreibvorgang je Anlauf würde die Datei ohne Not
  // anfassen und in einem Projekt unter Versionsverwaltung als Änderung auftauchen.
  const geaendert = neu !== alt;
  if (geaendert && !args.dryRun) {
    mkdirSync(dirname(ziel), { recursive: true });
    writeFileSync(ziel, neu, 'utf8');
  }
  return {
    zustand, befund, datei: mitgeben ? zustandsDatei : '-', geaendert, ziel, satz: text,
    code: mitgeben ? 0 : 1,
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('  node tools/anmeldung-mitnehmen.mjs --projekt <verzeichnis> [--aus]'
      + ' [--kontext <name>] [--zustand <datei>] [--dry-run] [--auch-im-repo]');
    return 0;
  }
  const r = mitnehmen(args);
  console.log(`ANMELDUNG ${r.zustand} ${r.befund} ${alsPfadWert(r.datei)} `
    + `${r.geaendert ? 'geaendert' : 'unveraendert'}`);
  console.log(r.satz);
  if (args.dryRun) {
    console.log(`  (--dry-run: ${r.geaendert ? 'zu schreiben' : 'unverändert'} — ${r.ziel})`);
  }
  return r.code;
}

const invoked = process.argv[1] ? pathToFileURL(process.argv[1]).href : '';
if (invoked === import.meta.url) {
  try {
    process.exit(main());
  } catch (e) {
    console.log('ANMELDUNG FEHLGESCHLAGEN - - unveraendert');
    console.log(`  ${e.stack || e.message}`);
    process.exit(2);
  }
}

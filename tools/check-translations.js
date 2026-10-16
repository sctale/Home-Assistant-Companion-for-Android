// Integrity + coverage gate for the Simplified Chinese resources.
//
// Why this is a script and not a glance: this project ships `values-zh-rCN` as a fork-local addition
// on top of upstream's English strings, so three failure classes are silent until runtime or until
// AAPT/Lint complains - a duplicated key (build error), a key translated here but absent upstream
// (ExtraTranslation), and a translation that drops or reorders a format specifier (crash at
// runtime, e.g. `%1$d%%` typed as `%1$d%`). Coverage is printed too, because the README quotes it.
//
// Usage: node tools/check-translations.js
const fs = require('fs');

const EN_FILE = 'common/src/main/res/values/strings.xml';
const ZH_FILE = 'common/src/main/res/values-zh-rCN/strings.xml';

function readEntries(file) {
  const text = fs.readFileSync(file, 'utf8');
  const map = new Map();
  const dupes = [];
  const patterns = [
    /<string\b([^>]*?)\s*(?:\/>|>([\s\S]*?)<\/string>)/g,
    /<plurals\b([^>]*)>([\s\S]*?)<\/plurals>/g,
    /<string-array\b([^>]*)>([\s\S]*?)<\/string-array>/g,
  ];
  for (const re of patterns) {
    let m;
    while ((m = re.exec(text))) {
      const name = (m[1].match(/name="([^"]+)"/) || [])[1];
      if (!name) continue;
      if (map.has(name)) dupes.push(name);
      map.set(name, (m[2] || '').trim());
    }
  }
  return { map, dupes, text };
}

const en = readEntries(EN_FILE);
const zh = readEntries(ZH_FILE);

const missing = [...en.map.keys()].filter((k) => !zh.map.has(k));
const extra = [...zh.map.keys()].filter((k) => !en.map.has(k));

// Format specifiers must survive verbatim: %s / %1$s / %d and the %% escape.
const specs = (s) =>
  `${(s.match(/%(?:\d+\$)?[ds]/g) || []).sort().join(',')}|%%x${(s.match(/%%/g) || []).length}`;

const broken = [];
for (const [key, zhBody] of zh.map) {
  const enBody = en.map.get(key);
  if (enBody === undefined) continue;
  if (specs(enBody) !== specs(zhBody)) broken.push(`${key}: en[${specs(enBody)}] zh[${specs(zhBody)}]`);
}

const coverage = ((zh.map.size / en.map.size) * 100).toFixed(1);
console.log(`en=${en.map.size} zh=${zh.map.size} coverage=${coverage}%`);
console.log(`untranslated=${missing.length} extra=${extra.length} placeholderMismatch=${broken.length} zhDuplicates=${zh.dupes.length}`);
for (const k of zh.dupes) console.log(`  DUP ${k}`);
for (const k of extra) console.log(`  EXTRA (not in upstream English) ${k}`);
for (const line of broken) console.log(`  PLACEHOLDER ${line}`);
if (missing.length <= 60) for (const k of missing) console.log(`  MISSING ${k}`);

process.exitCode = zh.dupes.length || extra.length || broken.length ? 1 : 0;

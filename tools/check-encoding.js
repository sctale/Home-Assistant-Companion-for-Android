const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const targets = [
  ...fs.readdirSync(root).filter((f) => f.endsWith('.bat')).map((f) => path.join(root, f)),
  ...fs.readdirSync(path.join(root, 'scripts')).filter((f) => f.endsWith('.ps1')).map((f) => path.join(root, 'scripts', f)),
  ...process.argv.slice(2).map((f) => path.resolve(root, f)),
];
for (const p of targets) {
  const b = fs.readFileSync(p);
  // Count the *leading* BOMs: a .ps1 that has been rewritten by several tools can carry 2 or 8,
  // and then PowerShell's parser sees a run of U+FEFF text on line 1.
  let boms = 0;
  while (b[boms] === 0xef && b[boms + 1] === 0xbb && b[boms + 2] === 0xbf) boms += 3;
  boms /= 3;
  let nonAscii = 0;
  for (const byte of b) if (byte > 126) nonAscii++;
  const text = b.toString('latin1');
  const crlf = (text.match(/\r\n/g) || []).length;
  const lf = (text.match(/\n/g) || []).length;
  const problems = [];
  if (p.endsWith('.ps1')) {
    if (boms !== 1) problems.push(`BOM=${boms} (need exactly 1)`);
  } else {
    if (boms !== 0) problems.push('has a BOM');
    if (nonAscii > 0) problems.push(`has ${nonAscii} non-ASCII bytes`);
  }
  if (lf - crlf > 0) problems.push(`bare_lf=${lf - crlf}`);
  const label = path.basename(p);
  if (problems.length) console.log(`FAIL ${label}: ${problems.join(', ')}`);
  else console.log(`ok   ${label}: bom=${boms} nonAscii=${nonAscii} lines=${lf} all-CRLF`);
  if (problems.length) process.exitCode = 1;
}

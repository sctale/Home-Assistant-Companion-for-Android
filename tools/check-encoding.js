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
  const bom = b[0] === 0xEF && b[1] === 0xBB && b[2] === 0xBF;
  let nonAscii = 0;
  for (const byte of b) if (byte > 126) nonAscii++;
  const text = b.toString('latin1');
  const crlf = (text.match(/\r\n/g) || []).length;
  const lf = (text.match(/\n/g) || []).length;
  const kind = p.endsWith('.ps1') ? 'ps1 needs BOM=1, nonAscii>0 ok' : 'bat needs BOM=0, nonAscii=0';
  console.log(`${path.basename(p)}: bom=${bom} nonAscii=${nonAscii} lines=${lf} crlf=${crlf} bare_lf=${lf - crlf}  [${kind}]`);
}

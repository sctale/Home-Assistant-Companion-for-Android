// Normalise the two things that break Windows launchers on this machine, by file extension:
//
//   .ps1  UTF-8 **with** BOM + CRLF. PowerShell 5.1 otherwise decodes the file as GBK and the
//         Chinese strings take the parser with them (quotes and braces get eaten, syntax errors
//         on unrelated lines).
//   .bat  UTF-8 **without** BOM + CRLF. cmd reads a .bat in the OEM codepage, and a BOM shifts the
//         parser by three bytes: `@echo off` stops applying and a `rem` comment can be split
//         mid-line and executed. .bat files here therefore stay pure ASCII.
//
// A BOM'd .ps1 also tends to accumulate extra BOMs every time a tool rewrites it (seen three times
// in this repo's tooling work), so this script counts the leading BOMs first and reduces them to 1.
//
// Usage: node tools/fix-script-encoding.js scripts/release-app.ps1 发布到GitHub.bat
const fs = require('fs');
const path = require('path');

const BOM = Buffer.from([0xef, 0xbb, 0xbf]);

// No arguments: take every launcher in the repo, which also avoids passing Chinese file names
// through a shell that may not be in the same codepage.
const args = process.argv.slice(2);
const targets = args.length
  ? args
  : [
      ...fs.readdirSync('.').filter((f) => f.endsWith('.bat')).map((f) => path.join('.', f)),
      ...fs.readdirSync('scripts').filter((f) => f.endsWith('.ps1')).map((f) => path.join('scripts', f)),
    ];

for (const arg of targets) {
  const file = path.resolve(arg);
  const ext = path.extname(file).toLowerCase();
  if (ext !== '.ps1' && ext !== '.bat' && ext !== '.cmd') {
    console.error(`skip (not .ps1/.bat/.cmd): ${arg}`);
    continue;
  }

  let buf = fs.readFileSync(file);
  let leading = 0;
  while (buf[leading] === 0xef && buf[leading + 1] === 0xbb && buf[leading + 2] === 0xbf) leading += 3;
  const body = buf.slice(leading);
  const wantedBom = ext === '.ps1';
  const notes = [];
  if (leading / 3 > 1) notes.push(`collapsed ${leading / 3} BOMs`);
  buf = wantedBom ? Buffer.concat([BOM, body]) : body;

  const text = buf.toString('latin1');
  const crlfText = text.replace(/\r\n/g, '\n').replace(/\n/g, '\r\n');
  if (crlfText !== text) notes.push('line endings -> CRLF');

  if (notes.length) {
    fs.writeFileSync(file, Buffer.from(crlfText, 'latin1'));
    console.log(`fixed: ${arg}  (${notes.join(', ')})`);
  } else {
    console.log(`ok: ${arg}`);
  }
}

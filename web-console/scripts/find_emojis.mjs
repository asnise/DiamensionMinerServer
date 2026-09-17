import fs from 'fs';
import path from 'path';

function isEmoji(char) {
  const code = char.codePointAt(0);
  return (
    (code >= 0x1F300 && code <= 0x1F9FF) ||
    (code >= 0x2600 && code <= 0x27BF) ||
    (code >= 0x1F600 && code <= 0x1F64F) ||
    (code >= 0x1F680 && code <= 0x1F6FF) ||
    (code >= 0x1FA00 && code <= 0x1FAFF) ||
    (code >= 0x2300 && code <= 0x23FF) ||
    (code >= 0x2B50 && code <= 0x2B55) ||
    (code >= 0x25A0 && code <= 0x25FF) // geometric shapes like ▪
  );
}

function scanDir(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (['node_modules', '.git', '.next', '.gradle', 'build', 'cache', '.paper', 'libraries', 'versions', 'world'].includes(entry.name)) continue;
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      scanDir(fullPath);
    } else if (/\.(java|js|jsx|ts|tsx|yml|yaml|sql|css)$/.test(entry.name)) {
      const content = fs.readFileSync(fullPath, 'utf8');
      const lines = content.split('\n');
      lines.forEach((line, idx) => {
        for (const char of line) {
          if (isEmoji(char)) {
            console.log(`${fullPath}:${idx + 1}: ${line.trim()}`);
            break;
          }
        }
      });
    }
  }
}

scanDir('..');

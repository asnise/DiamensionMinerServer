// (Re)opens a .bbmodel in Blockbench through the MCP plugin: closes an open tab of the same file
// first (no save prompt, the file on disk is the source of truth), then loads it and names it.
//   node bbreload.mjs "<path>.bbmodel" "<project name>"
import path from 'node:path';
import { connect, call, text } from './bbmcp.mjs';

const file = path.resolve(process.argv[2]).replace(/\\/g, '/');
const name = process.argv[3] || path.basename(file, '.bbmodel');
await connect();
const code = `(async () => {
  const file = ${JSON.stringify(file)};
  for (const p of [...ModelProject.all]) {
    if ((p.save_path || '').replace(/\\\\/g, '/') === file || p.name === ${JSON.stringify(name)}) { p.saved = true; await p.close(true); }
  }
  const fs = require('fs');
  loadModelFile({ path: file, name: ${JSON.stringify(path.basename(file))}, content: fs.readFileSync(file, 'utf8') });
  Project.name = ${JSON.stringify(name)};
  return { name: Project.name, cubes: Cube.all.length, groups: Group.all.length, animations: Animation.all.length };
})()`;
console.log(text(await call('risky_eval', { code })));

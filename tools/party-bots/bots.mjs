// Test bots for solo-testing party / dungeon features on the LOCAL dev server.
//
// Requires the server to run with online-mode=false (see ../../switch-online-mode.ps1).
// Mineflayer 4.39 speaks up to Minecraft 26.1; a 26.2 server also needs ViaBackwards (see README.md).
//
// Usage (from tools/party-bots):
//   npm install
//   node bots.mjs                      2 bots: TestBot1, TestBot2 on localhost:25565
//   node bots.mjs --count 3 --prefix Pal --version 26.1
//
// What the bots do:
//   - accept every party invite automatically (/party accept)
//   - print every message they receive, prefixed with their name
//   - obey commands typed in this terminal:     <bot|all> <text or /command>
//         TestBot1 /dungeon join 1a2b3c4d
//         all /party leave
//         TestBot2 quit
//   - obey commands typed in game chat by a real player:   !<bot|all> <text or /command>
//         !TestBot1 /party leave
//         !all quit
import mineflayer from 'mineflayer';
import readline from 'node:readline';

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const HOST = arg('host', 'localhost');
const PORT = Number(arg('port', '25565'));
const COUNT = Math.max(1, Number(arg('count', '2')));
const PREFIX = arg('prefix', 'TestBot');
const VERSION = arg('version', '26.1');
// Paper throttles reconnects from one IP (bukkit.yml connection-throttle, 4000 ms by default).
const STAGGER_MS = Number(arg('stagger', '5000'));

const bots = new Map(); // name -> bot

function log(name, msg) {
  const t = new Date().toLocaleTimeString();
  console.log(`[${t}] [${name}] ${msg}`);
}

/** Runs one instruction on one bot: "quit", "/command ...", or plain chat text. */
function perform(bot, text) {
  const cmd = text.trim();
  if (!cmd) return;
  if (cmd.toLowerCase() === 'quit') {
    log(bot.username, 'quitting');
    bot.quit();
    return;
  }
  log(bot.username, `> ${cmd}`);
  bot.chat(cmd); // chat() sends "/..." as a command
}

/** Routes "<target> <text>" to one bot or all bots. Returns false if the target is unknown. */
function dispatch(target, text) {
  if (target.toLowerCase() === 'all') {
    for (const b of bots.values()) perform(b, text);
    return true;
  }
  const bot = [...bots.values()].find((b) => b.username.toLowerCase() === target.toLowerCase());
  if (!bot) return false;
  perform(bot, text);
  return true;
}

function createBot(name) {
  const bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: name,
    auth: 'offline',
    version: VERSION,
    hideErrors: true, // errors are reported once by the 'error' handler below
  });
  bots.set(name, bot);

  bot.once('spawn', () => log(name, `joined ${HOST}:${PORT}`));

  bot.on('messagestr', (message, position) => {
    if (position === 'game_info') return; // action bar spam
    log(name, message);
    if (/invited you to join their party/i.test(message)) {
      setTimeout(() => perform(bot, '/party accept'), 500);
    }
  });

  // Commands from real players in game chat: "!TestBot1 /party leave" or "!all quit"
  bot.on('chat', (username, message) => {
    if (bots.has(username)) return; // ignore other bots
    const m = message.match(/^!(\S+)\s+(.+)$/);
    if (!m) return;
    // Every bot sees the same chat line; only the first bot (alphabetically) routes it.
    const router = [...bots.keys()].sort()[0];
    if (name !== router) return;
    if (!dispatch(m[1], m[2])) log(name, `no bot named ${m[1]}`);
  });

  bot.on('kicked', (reason) => log(name, `KICKED: ${typeof reason === 'string' ? reason : JSON.stringify(reason)}`));
  bot.on('error', (err) => {
    const why = err.code === 'ECONNREFUSED'
      ? `cannot reach ${HOST}:${PORT} (is the Minecraft server running?)`
      : (err.message || String(err));
    log(name, `ERROR: ${why}`);
  });
  bot.on('end', (reason) => {
    log(name, `disconnected (${reason})`);
    bots.delete(name);
    if (bots.size === 0) {
      console.log('All bots disconnected. Bye.');
      process.exit(0);
    }
  });
}

console.log(`Starting ${COUNT} bot(s) "${PREFIX}1..${COUNT}" -> ${HOST}:${PORT} (protocol ${VERSION})`);
console.log('Type "<bot|all> <text or /command>" here, e.g.  TestBot1 /party leave   or   all quit\n');
for (let i = 1; i <= COUNT; i++) {
  setTimeout(() => createBot(`${PREFIX}${i}`), (i - 1) * STAGGER_MS);
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  const m = line.trim().match(/^(\S+)\s+(.+)$/);
  if (!m) {
    console.log('Format: <bot|all> <text or /command>');
    return;
  }
  if (!dispatch(m[1], m[2])) console.log(`No bot named "${m[1]}". Online: ${[...bots.keys()].join(', ') || 'none'}`);
});

process.on('SIGINT', () => {
  for (const b of bots.values()) b.quit();
  setTimeout(() => process.exit(0), 500);
});

// DM Figma Bridge — runs Plugin API scripts sent from tools/figma-bridge/run.mjs
// through the local socket relay (bunx cursor-talk-to-figma-socket, port 3055).
figma.showUI(__html__, { width: 280, height: 150 });

const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

function plain(v, depth) {
  if (depth > 6) return '[depth]';
  if (v === null || v === undefined) return v;
  if (typeof v === 'bigint') return String(v);
  if (v instanceof Uint8Array) return figma.base64Encode(v);
  if (Array.isArray(v)) return v.map(x => plain(x, depth + 1));
  if (typeof v === 'object') {
    if ('id' in v && 'type' in v && typeof v.type === 'string' && 'parent' in v) {
      return { id: v.id, type: v.type, name: v.name };
    }
    const o = {};
    for (const k of Object.keys(v)) o[k] = plain(v[k], depth + 1);
    return o;
  }
  return v;
}

figma.ui.onmessage = async (msg) => {
  if (msg.type !== 'exec') return;
  try {
    const fn = new AsyncFunction('figma', msg.code);
    const result = await fn(figma);
    figma.ui.postMessage({ type: 'result', id: msg.id, result: plain(result, 0) });
  } catch (e) {
    figma.ui.postMessage({ type: 'result', id: msg.id, error: String((e && e.stack) || e) });
  }
};

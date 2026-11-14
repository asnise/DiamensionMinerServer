// Builds edit forms from the JSON Schemas in EXeaEntityCore/schemas, so the editor always offers the
// keys the plugin reads. An empty field removes the key (the plugin then uses its default).
import { combo } from '../combo.js';

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export class SchemaForms {
    constructor(schemas) {
        // schemas: { 'common.schema.json': {...}, 'effect.schema.json': {...}, ... }
        this.docs = schemas;
        this.suggest = {};
    }

    /** Values offered for a text field: the editor's list for this key (array or function), else the schema's examples. */
    listFor(key, s) {
        const l = this.suggest[key];
        if (typeof l === 'function') return l;
        if (Array.isArray(l) && l.length) return l;
        if (s?.examples?.length && s.examples.every(x => typeof x === 'string')) return s.examples;
        return null;
    }

    /** Resolves "#/$defs/x" (inside doc) or "common.schema.json#/$defs/x". Returns [schema, docName]. */
    resolve(s, doc) {
        let guard = 0;
        while (s && s.$ref && guard++ < 10) {
            const [file, ptr] = s.$ref.split('#');
            const d = file ? file : doc;
            let node = this.docs[d];
            for (const part of (ptr || '').split('/').filter(Boolean)) node = node?.[part];
            s = node ? { ...node, ...Object.fromEntries(Object.entries(s).filter(([k]) => k !== '$ref')) } : null;
            doc = d;
        }
        return [s || {}, doc];
    }

    /** All properties of a schema, allOf and if/then branches that match {@code value} merged in. */
    properties(s, doc, value) {
        [s, doc] = this.resolve(s, doc);
        const props = {};
        const add = (p, d) => { for (const [k, v] of Object.entries(p || {})) props[k] = { schema: v, doc: d }; };
        add(s.properties, doc);
        for (const part of s.allOf || []) {
            const [p, pd] = this.resolve(part, doc);
            if (p.if) {
                const cond = p.if.properties || {};
                const ok = Object.entries(cond).every(([k, c]) => (c.const !== undefined ? value?.[k] === c.const : c.enum ? c.enum.includes(value?.[k]) : true));
                if (ok && p.then) { const [t, td] = this.resolve(p.then, pd); add(t.properties, td); for (const x of t.allOf || []) { const [xx, xd] = this.resolve(x, td); add(xx.properties, xd); } }
            } else add(p.properties, pd);
        }
        return props;
    }

    /**
     * Renders fields for {@code obj} into {@code el}. {@code onChange()} runs after every edit.
     * skip: keys not to show (handled elsewhere).
     */
    render(el, schema, doc, obj, onChange, skip = []) {
        el.innerHTML = '';
        const form = document.createElement('div');
        form.className = 'form';
        el.appendChild(form);
        const props = this.properties(schema, doc, obj);
        for (const [key, { schema: ps, doc: pd }] of Object.entries(props)) {
            if (skip.includes(key) || key === '$schema') continue;
            this.field(form, key, ps, pd, obj, onChange, schema, doc);
        }
    }

    field(form, key, ps, doc, obj, onChange, parentSchema, parentDoc) {
        const [s, sd] = this.resolve(ps, doc);
        const desc = s.description || ps.description || '';
        const def = s.default ?? ps.default;
        const row = document.createElement('div');
        row.className = 'frow';
        const set = obj[key] !== undefined;
        row.innerHTML = `<label title="${esc(key + (desc ? ' — ' + desc : ''))}" class="${set ? 'set' : ''}">${esc(key)}</label><div class="ctl"></div>`;
        const ctl = row.querySelector('.ctl');
        const label = row.querySelector('label');
        const commit = (v) => {
            if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) delete obj[key];
            else obj[key] = v;
            label.classList.toggle('set', obj[key] !== undefined);
            onChange(key);
        };
        const ph = def !== undefined ? 'default ' + (typeof def === 'object' ? JSON.stringify(def) : def) : '';
        const types = [].concat(s.type || []);

        // objects with properties (anchor, shape): a nested section
        if ((types.includes('object') || s.properties) && !s.oneOf && key !== 'options') {
            const sub = document.createElement('div');
            sub.className = 'sub-sec';
            sub.innerHTML = `<div class="t" title="${esc(desc)}">${esc(key)}</div>`;
            form.appendChild(sub);
            const value = obj[key] && typeof obj[key] === 'object' ? obj[key] : {};
            const body = document.createElement('div');
            sub.appendChild(body);
            const rerender = () => {
                this.render(body, s, sd, value, (k) => {
                    if (Object.keys(value).length) obj[key] = value; else delete obj[key];
                    onChange(key);
                    if (k === 'type') rerender(); // a new shape type shows its own keys
                });
            };
            rerender();
            return;
        }

        if (s.enum) {
            const sel = document.createElement('select');
            sel.innerHTML = `<option value="">${esc(ph || '—')}</option>` + s.enum.map(v => `<option${obj[key] === v ? ' selected' : ''}>${esc(v)}</option>`).join('');
            sel.onchange = () => commit(sel.value || undefined);
            ctl.appendChild(sel);
        } else if (s.const !== undefined) {
            ctl.innerHTML = `<span class="muted">${esc(s.const)}</span>`;
        } else if (types.includes('boolean')) {
            const cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.checked = obj[key] ?? def ?? false;
            cb.onchange = () => commit(cb.checked === (def ?? false) && obj[key] === undefined ? undefined : cb.checked);
            ctl.appendChild(cb);
            if (def !== undefined) ctl.insertAdjacentHTML('beforeend', `<span class="unit">default ${def}</span>`);
        } else if (s.pattern && s.pattern.startsWith('^#')) {
            const pick = document.createElement('input');
            pick.type = 'color';
            const txt = document.createElement('input');
            txt.type = 'text';
            txt.placeholder = ph || '#rrggbb';
            txt.value = obj[key] ?? '';
            pick.value = /^#[0-9a-f]{6}$/i.test(obj[key] || def || '') ? (obj[key] || def) : '#ffffff';
            pick.oninput = () => { txt.value = pick.value; commit(pick.value); };
            txt.onchange = () => { if (/^#[0-9a-f]{6}$/i.test(txt.value)) pick.value = txt.value; commit(txt.value.trim() || undefined); };
            ctl.append(pick, txt);
        } else if (s.oneOf && s.oneOf.length === 2 && s.oneOf.some(o => { const r = this.resolve(o, sd)[0]; return r.type === 'array' && r.maxItems === 2; })
            && s.oneOf.some(o => this.resolve(o, sd)[0].type === 'number')) {
            // number or [min, max]
            const v = obj[key];
            const a = document.createElement('input'), b = document.createElement('input');
            for (const i of [a, b]) { i.type = 'number'; i.step = 'any'; }
            a.placeholder = Array.isArray(def) ? 'min ' + def[0] : (ph || 'value');
            b.placeholder = Array.isArray(def) ? 'max ' + def[1] : 'max (range)';
            if (Array.isArray(v)) { a.value = v[0]; b.value = v[1]; } else if (typeof v === 'number') a.value = v;
            const upd = () => {
                const x = a.value === '' ? undefined : +a.value, y = b.value === '' ? undefined : +b.value;
                commit(x === undefined ? undefined : y === undefined ? x : [x, y]);
            };
            a.onchange = upd;
            b.onchange = upd;
            ctl.append(a, b);
        } else if (types.includes('array') && s.minItems === 2 && s.maxItems === 2) {
            // [min, max] (skill range, hp)
            const v = Array.isArray(obj[key]) ? obj[key] : [];
            const ins = ['min', 'max'].map((p, i) => { const n = document.createElement('input'); n.type = 'number'; n.step = 'any'; n.placeholder = p; n.value = v[i] ?? ''; return n; });
            const upd = () => commit(ins.every(n => n.value === '') ? undefined : ins.map(n => (n.value === '' ? 0 : +n.value)));
            ins.forEach(n => { n.onchange = upd; });
            ctl.append(...ins);
        } else if (types.includes('array') && s.items && ['string'].includes(this.resolve(s.items, sd)[0].type)) {
            // a list of names (glow bones, phase skills): comma separated
            const n = document.createElement('input');
            n.type = 'text';
            n.spellcheck = false;
            n.placeholder = 'a, b, c' + (desc ? ' — ' + desc : '');
            n.value = Array.isArray(obj[key]) ? obj[key].join(', ') : '';
            const list = this.listFor(key, this.resolve(s.items, sd)[0]);
            n.onchange = () => { const v = n.value.split(',').map(x => x.trim()).filter(Boolean); commit(v.length ? v : undefined); };
            ctl.appendChild(list ? combo(n, list, { multi: true }) : n);
        } else if (types.includes('array') && s.minItems === 3 && s.maxItems === 3) {
            const v = Array.isArray(obj[key]) ? obj[key] : [];
            const ins = [0, 1, 2].map(i => { const n = document.createElement('input'); n.type = 'number'; n.step = 'any'; n.placeholder = ['x', 'y', 'z'][i]; n.value = v[i] ?? ''; return n; });
            if (key === 'offset') ins.forEach((n, i) => { n.placeholder = ['right', 'up', 'fwd'][i]; });
            const upd = () => { const vals = ins.map(n => (n.value === '' ? 0 : +n.value)); commit(ins.every(n => n.value === '') ? undefined : vals); };
            ins.forEach(n => { n.onchange = upd; });
            ctl.append(...ins);
        } else if (types.includes('number') || types.includes('integer')) {
            const n = document.createElement('input');
            n.type = 'number';
            n.step = types.includes('integer') ? '1' : 'any';
            if (s.minimum !== undefined) n.min = s.minimum;
            if (s.maximum !== undefined) n.max = s.maximum;
            n.placeholder = ph;
            n.value = obj[key] ?? '';
            n.onchange = () => commit(n.value === '' ? undefined : +n.value);
            ctl.appendChild(n);
        } else if (types.includes('string') || s.pattern || (s.oneOf && s.oneOf.some(o => this.resolve(o, sd)[0].type === 'string')
            && !s.oneOf.some(o => ['number', 'object'].includes(this.resolve(o, sd)[0].type)))) {
            const n = document.createElement('input');
            n.type = 'text';
            n.spellcheck = false;
            n.placeholder = ph || (s.examples ? s.examples.slice(0, 3).join(', ') : '');
            const list = this.listFor(key, s);
            // a block can be a list: comma separated
            const v = obj[key];
            n.value = Array.isArray(v) ? v.join(', ') : (v ?? '');
            n.onchange = () => {
                const t = n.value.trim();
                commit(t === '' ? undefined : (s.oneOf && t.includes(',')) ? t.split(',').map(x => x.trim()).filter(Boolean) : t);
            };
            ctl.appendChild(list ? combo(n, list, { multi: !!s.oneOf && key === 'block' }) : n);
        } else {
            // anything else (scale / rotation from-to, potions...): JSON text
            const n = document.createElement('input');
            n.type = 'text';
            n.spellcheck = false;
            n.placeholder = 'JSON' + (ph ? ' · ' + ph : '');
            n.value = obj[key] === undefined ? '' : JSON.stringify(obj[key]);
            n.onchange = () => {
                if (n.value.trim() === '') return commit(undefined);
                try { commit(JSON.parse(n.value)); n.style.borderColor = ''; } catch { n.style.borderColor = 'var(--danger)'; }
            };
            ctl.appendChild(n);
        }
        form.appendChild(row);
    }
}

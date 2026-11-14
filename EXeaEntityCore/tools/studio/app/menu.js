// Menu bar (File / Edit / View / Window / Help) in the top bar, like Figma's. Items:
// { label, accel?, run(), enabled?(), checked?(), sub?: () => items }, or '-' for a separator.
// Ctrl / Alt shortcuts (accel) work everywhere (outside text fields); plain keys stay with each mode and are
// only shown (hint).
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export class MenuBar {
    constructor(el, menus) {
        this.el = el;
        this.menus = menus; // [{ label, items: () => [...] }]
        this.open = -1;
        el.innerHTML = menus.map((m, i) => `<button class="menu-top" data-i="${i}">${esc(m.label)}</button>`).join('');
        el.querySelectorAll('.menu-top').forEach(b => {
            b.addEventListener('mousedown', (e) => { e.preventDefault(); this.toggle(+b.dataset.i); });
            b.addEventListener('mouseenter', () => { if (this.open >= 0 && this.open !== +b.dataset.i) this.show(+b.dataset.i); });
        });
        document.addEventListener('mousedown', (e) => { if (this.open >= 0 && !e.target.closest('.menu-pop') && !e.target.closest('.menu-top')) this.close(); });
        window.addEventListener('blur', () => this.close());
        window.addEventListener('keydown', (e) => this.onKey(e), true);
    }

    toggle(i) { this.open === i ? this.close() : this.show(i); }

    close() {
        document.querySelectorAll('.menu-pop').forEach(p => p.remove());
        this.el.querySelectorAll('.menu-top').forEach(b => b.classList.remove('on'));
        this.open = -1;
    }

    show(i) {
        this.close();
        this.open = i;
        const btn = this.el.querySelector(`.menu-top[data-i="${i}"]`);
        btn.classList.add('on');
        const r = btn.getBoundingClientRect();
        this.popup(this.menus[i].items(), r.left, r.bottom + 2, 0);
    }

    popup(items, x, y, depth) {
        document.querySelectorAll('.menu-pop').forEach(p => { if (+p.dataset.depth >= depth) p.remove(); });
        const pop = document.createElement('div');
        pop.className = 'menu-pop';
        pop.dataset.depth = depth;
        pop.innerHTML = items.map((it, k) => {
            if (it === '-') return '<div class="menu-sep"></div>';
            if (it.hidden) return '';
            if (it.header) return `<div class="menu-head">${esc(it.header)}</div>`;
            const off = it.enabled && !it.enabled();
            const chk = it.checked ? (it.checked() ? '✓' : '') : '';
            return `<div class="menu-item${off ? ' off' : ''}${it.sub ? ' has-sub' : ''}" data-k="${k}">
                <span class="chk">${chk}</span><span class="lbl">${esc(it.label)}</span>
                <span class="acc">${it.sub ? '›' : esc(it.accel || it.hint || '')}</span></div>`;
        }).join('');
        document.body.appendChild(pop);
        // keep on screen
        const w = pop.offsetWidth, h = pop.offsetHeight;
        pop.style.left = Math.min(x, window.innerWidth - w - 6) + 'px';
        pop.style.top = Math.min(y, window.innerHeight - h - 6) + 'px';
        pop.querySelectorAll('.menu-item').forEach(row => {
            const it = items[+row.dataset.k];
            row.addEventListener('mouseenter', () => {
                document.querySelectorAll('.menu-pop').forEach(p => { if (+p.dataset.depth > depth) p.remove(); });
                if (it.sub && !row.classList.contains('off')) {
                    const rr = row.getBoundingClientRect();
                    const subItems = it.sub();
                    this.popup(subItems.length ? subItems : [{ label: '(empty)', enabled: () => false, run() {} }], rr.right - 2, rr.top - 4, depth + 1);
                }
            });
            row.addEventListener('mouseup', () => {
                if (row.classList.contains('off') || it.sub) return;
                this.close();
                setTimeout(() => it.run?.(), 0);
            });
        });
    }

    /** All items (sub-menus included) that have a shortcut. */
    flat(items, out = []) {
        for (const it of items) {
            if (it === '-' || it.header) continue;
            if (it.accel) out.push(it);
        }
        return out;
    }

    onKey(e) {
        if (e.key === 'Escape' && this.open >= 0) { e.stopPropagation(); this.close(); return; }
        if (!e.ctrlKey && !e.altKey && e.key !== 'F12') return;
        if (document.querySelector('.modal-back')) return;
        const typing = e.target.matches('input[type=text], input[type=number], input[type=password], input:not([type]), textarea, select');
        const combo = accelOf(e);
        for (const m of this.menus) {
            for (const it of this.flat(m.items())) {
                if (normalize(it.accel) !== combo) continue;
                // in a text field, let the field have its own Ctrl+Z / Ctrl+C / Delete…
                if (typing && !/^Ctrl\+(S|Shift\+S|O|N|Shift\+N|Shift\+O|[1-3]|Enter)$/.test(combo) && combo !== 'F12') return;
                if (it.enabled && !it.enabled()) return;
                e.preventDefault();
                e.stopPropagation();
                it.run();
                return;
            }
        }
    }
}

function accelOf(e) {
    const parts = [];
    if (e.ctrlKey) parts.push('Ctrl');
    if (e.altKey) parts.push('Alt');
    if (e.shiftKey) parts.push('Shift');
    let k = e.key;
    if (k === ' ') k = 'Space';
    if (k.length === 1) k = k.toUpperCase();
    parts.push(k);
    return parts.join('+');
}

function normalize(a) {
    return String(a || '').split('+').map(s => (s.length === 1 ? s.toUpperCase() : s)).join('+');
}

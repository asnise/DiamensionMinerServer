// Text field + ▾ button that always shows its whole list (a <datalist> only shows entries that match what is
// already typed, so a filled field looked empty). Typing filters; ↑ ↓ Enter pick; free text still works.
// multi: comma separated values (glow bones, phase skills): a pick is added to the list.
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let openPop = null;
function closePop() { openPop?.remove(); openPop = null; }
document.addEventListener('mousedown', (e) => { if (openPop && !e.target.closest('.combo-pop') && !e.target.closest('.combo-btn')) closePop(); }, true);
window.addEventListener('blur', closePop);

/**
 * Wraps {@code input} (already in the DOM or about to be) in a combo. Returns the wrapper element to insert.
 * list: array of strings, or a function returning one (read each time the list opens).
 */
export function combo(input, list, { multi = false, strict = false } = {}) {
    const wrap = document.createElement('div');
    wrap.className = 'combo';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'combo-btn';
    btn.tabIndex = -1;
    btn.title = 'Show all';
    btn.innerHTML = '<svg viewBox="0 0 10 10" width="10" height="10"><path d="M2 3.5l3 3 3-3" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>';
    wrap.append(input, btn);
    input.autocomplete = 'off';
    const items = () => (typeof list === 'function' ? list() : list) || [];
    let hi = -1, shown = [];

    const current = () => {
        if (!multi) return input.value.trim();
        const parts = input.value.split(',');
        return parts[parts.length - 1].trim();
    };
    const pick = (v) => {
        if (multi) {
            const parts = input.value.split(',').map(s => s.trim()).filter(Boolean);
            if (current() && parts.length) parts.pop();
            if (!parts.includes(v)) parts.push(v);
            input.value = parts.join(', ');
        } else input.value = v;
        closePop();
        input.dispatchEvent(new Event('change', { bubbles: true }));
        input.focus();
    };
    const render = (all) => {
        const q = all ? '' : current().toLowerCase();
        const src = items();
        shown = q ? src.filter(x => String(x).toLowerCase().includes(q)) : src.slice();
        if (!openPop || openPop.owner !== input) {
            closePop();
            openPop = document.createElement('div');
            openPop.className = 'combo-pop';
            openPop.owner = input;
            document.body.appendChild(openPop);
        }
        const selVals = multi ? input.value.split(',').map(s => s.trim()) : [input.value.trim()];
        openPop.innerHTML = shown.length
            ? shown.slice(0, 400).map((x, i) => `<div class="combo-item${i === hi ? ' hi' : ''}${selVals.includes(String(x)) ? ' on' : ''}" data-i="${i}">${esc(x)}</div>`).join('')
            : `<div class="combo-empty">${src.length ? 'No match' : 'Nothing to pick yet'}</div>`;
        openPop.querySelectorAll('.combo-item').forEach(el => el.addEventListener('mousedown', (e) => { e.preventDefault(); pick(shown[+el.dataset.i]); }));
        const r = wrap.getBoundingClientRect();
        openPop.style.minWidth = r.width + 'px';
        openPop.style.left = Math.max(4, Math.min(r.left, window.innerWidth - openPop.offsetWidth - 6)) + 'px';
        const below = window.innerHeight - r.bottom;
        const h = Math.min(260, openPop.scrollHeight);
        openPop.style.top = (below > h + 8 || below > r.top ? r.bottom + 2 : r.top - h - 2) + 'px';
        openPop.querySelector('.hi')?.scrollIntoView({ block: 'nearest' });
        if (all) openPop.querySelector('.on')?.scrollIntoView({ block: 'center' });
    };

    btn.addEventListener('mousedown', (e) => {
        e.preventDefault();
        if (openPop?.owner === input) return closePop();
        hi = -1;
        input.focus();
        render(true);
    });
    input.addEventListener('focus', () => { if (!input.value) { hi = -1; render(true); } });
    input.addEventListener('input', () => { hi = -1; render(false); });
    input.addEventListener('blur', () => setTimeout(() => { if (openPop?.owner === input && document.activeElement !== input) closePop(); }, 120));
    input.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            if (!openPop || openPop.owner !== input) { hi = 0; return render(true); }
            hi = Math.max(0, Math.min(shown.length - 1, hi + (e.key === 'ArrowDown' ? 1 : -1)));
            render(false);
        } else if (e.key === 'Enter' && openPop?.owner === input && hi >= 0 && shown[hi] !== undefined) {
            e.preventDefault();
            pick(shown[hi]);
        } else if (e.key === 'Escape' && openPop?.owner === input) {
            e.stopPropagation();
            closePop();
        }
    });
    if (strict) input.addEventListener('change', () => { if (input.value && !items().includes(input.value.trim())) input.style.borderColor = 'var(--warn, #ffc021)'; else input.style.borderColor = ''; });
    return wrap;
}

// Small in-app dialogs (no window.prompt / confirm): ask(), confirmBox(), info().
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/**
 * Shows a dialog. fields: [{ key, label, value, placeholder, hint }] (text inputs).
 * buttons: [{ label, value, primary?, danger? }]. Resolves { button, values } or null (Esc / outside click).
 */
export function modal({ title, html = '', fields = [], buttons = [{ label: 'OK', value: 'ok', primary: true }], width = 420 }) {
    return new Promise((resolve) => {
        const back = document.createElement('div');
        back.className = 'modal-back';
        back.innerHTML = `<div class="modal" style="width:${width}px" role="dialog" aria-label="${esc(title)}">
            <h2>${esc(title)}</h2>
            ${html ? `<div class="modal-body">${html}</div>` : ''}
            ${fields.length ? `<div class="form">${fields.map(f => `<div class="frow"><label>${esc(f.label)}</label><div class="ctl"><input type="text" data-k="${esc(f.key)}" value="${esc(f.value ?? '')}" placeholder="${esc(f.placeholder ?? '')}" spellcheck="false"></div></div>${f.hint ? `<p class="hint" style="margin:-4px 0 0 100px">${esc(f.hint)}</p>` : ''}`).join('')}</div>` : ''}
            <div class="modal-btns">${buttons.map((b, i) => `<button class="btn${b.primary ? ' primary' : ''}${b.danger ? ' danger' : ''}" data-i="${i}">${esc(b.label)}</button>`).join('')}</div>
        </div>`;
        document.body.appendChild(back);
        const values = () => Object.fromEntries([...back.querySelectorAll('input[data-k]')].map(i => [i.dataset.k, i.value.trim()]));
        const done = (r) => { back.remove(); document.removeEventListener('keydown', onKey, true); resolve(r); };
        const onKey = (e) => {
            if (e.key === 'Escape') { e.stopPropagation(); done(null); }
            if (e.key === 'Enter' && !e.target.matches('textarea')) {
                const p = buttons.findIndex(b => b.primary);
                if (p >= 0) { e.preventDefault(); e.stopPropagation(); done({ button: buttons[p].value, values: values() }); }
            }
        };
        document.addEventListener('keydown', onKey, true);
        back.addEventListener('mousedown', (e) => { if (e.target === back) done(null); });
        back.querySelectorAll('.modal-btns button').forEach(b => b.addEventListener('click', () => done({ button: buttons[+b.dataset.i].value, values: values() })));
        back.querySelector('input')?.focus();
        back.querySelector('input')?.select();
    });
}

/** One text field; resolves the text or null. */
export async function ask(title, label, value = '', hint = '') {
    const r = await modal({ title, fields: [{ key: 'v', label, value, hint }], buttons: [{ label: 'Cancel', value: 'cancel' }, { label: 'OK', value: 'ok', primary: true }] });
    return r && r.button === 'ok' ? r.values.v : null;
}

/** Yes / no; resolves true when confirmed. */
export async function confirmBox(title, text, okLabel = 'OK', danger = false) {
    const r = await modal({ title, html: `<p>${esc(text)}</p>`, buttons: [{ label: 'Cancel', value: 'cancel' }, { label: okLabel, value: 'ok', primary: !danger, danger }] });
    return !!r && r.button === 'ok';
}

export function info(title, html, width = 520) {
    return modal({ title, html, width, buttons: [{ label: 'Close', value: 'ok', primary: true }] });
}

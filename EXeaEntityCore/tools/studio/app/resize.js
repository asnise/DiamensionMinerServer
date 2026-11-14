// Drag handles between the panels: left / right panel widths and the timeline height. Sizes are kept
// per computer (local storage); double-clicking a handle resets it.
const DEFAULTS = { left: 248, right: 268, bottom: 216 };

export function initResizers(store) {
    const root = document.documentElement;
    const sizes = { ...DEFAULTS, ...store.get('panels', {}) };
    const limits = {
        left: () => [180, Math.min(560, window.innerWidth * 0.4)],
        right: () => [220, Math.min(600, window.innerWidth * 0.45)],
        bottom: () => [110, window.innerHeight * 0.65],
    };
    const vars = { left: '--left-w', right: '--right-w', bottom: '--tl-h' };
    const apply = (k) => {
        const [lo, hi] = limits[k]();
        sizes[k] = Math.round(Math.max(lo, Math.min(hi, sizes[k])));
        root.style.setProperty(vars[k], sizes[k] + 'px');
    };
    Object.keys(vars).forEach(apply);
    window.addEventListener('resize', () => Object.keys(vars).forEach(apply));

    document.querySelectorAll('.resizer').forEach((h) => {
        const k = h.dataset.r;
        h.addEventListener('pointerdown', (e) => {
            e.preventDefault();
            h.setPointerCapture(e.pointerId);
            h.classList.add('drag');
            document.body.classList.add('resizing', k === 'bottom' ? 'row' : 'col');
            const start = { x: e.clientX, y: e.clientY, size: sizes[k] };
            const move = (ev) => {
                if (k === 'left') sizes.left = start.size + (ev.clientX - start.x);
                else if (k === 'right') sizes.right = start.size - (ev.clientX - start.x);
                else sizes.bottom = start.size - (ev.clientY - start.y);
                apply(k);
            };
            const up = () => {
                h.classList.remove('drag');
                document.body.classList.remove('resizing', 'row', 'col');
                h.removeEventListener('pointermove', move);
                h.removeEventListener('pointerup', up);
                store.set('panels', sizes);
            };
            h.addEventListener('pointermove', move);
            h.addEventListener('pointerup', up);
        });
        h.addEventListener('dblclick', () => {
            sizes[k] = DEFAULTS[k];
            apply(k);
            store.set('panels', sizes);
        });
    });
    return {
        reset() {
            Object.assign(sizes, DEFAULTS);
            Object.keys(vars).forEach(apply);
            store.set('panels', sizes);
        },
    };
}

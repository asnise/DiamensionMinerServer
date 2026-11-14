// Bottom timeline: ruler, an Events row and one row per animated bone, drawn on a canvas.
// Click or drag to scrub, wheel to scroll rows, Ctrl+wheel to zoom time.
const ROW = 20;
const RULER = 24;

export class Timeline {
    constructor({ canvas, wrap, names, onScrub, onSelectBone }) {
        this.canvas = canvas;
        this.wrap = wrap;
        this.names = names;
        this.onScrub = onScrub;
        this.onSelectBone = onSelectBone;
        this.anim = null;
        this.rows = [];
        this.time = 0;
        this.scrollY = 0;
        this.zoom = 1;
        this.scrollX = 0;
        this.selected = null;
        this.ctx = canvas.getContext('2d');
        new ResizeObserver(() => this.resize()).observe(wrap);

        let dragging = false;
        const scrub = (e) => {
            const r = canvas.getBoundingClientRect();
            this.onScrub?.(this.xToTime(e.clientX - r.left));
        };
        canvas.addEventListener('pointerdown', (e) => {
            const r = canvas.getBoundingClientRect();
            const y = e.clientY - r.top;
            if (y > RULER) {
                const row = this.rows[Math.floor((y - RULER + this.scrollY) / ROW)];
                if (row?.bone) this.onSelectBone?.(row.bone);
            }
            dragging = true;
            canvas.setPointerCapture(e.pointerId);
            scrub(e);
        });
        canvas.addEventListener('pointermove', (e) => { if (dragging) scrub(e); });
        canvas.addEventListener('pointerup', () => { dragging = false; });
        wrap.addEventListener('wheel', (e) => {
            e.preventDefault();
            if (e.ctrlKey) {
                const r = canvas.getBoundingClientRect();
                const t = this.xToTime(e.clientX - r.left);
                this.zoom = Math.min(40, Math.max(1, this.zoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15)));
                const px = e.clientX - r.left - 12;
                this.scrollX = Math.max(0, t * this.pxPerSec() - px);
            } else if (e.shiftKey) {
                this.scrollX = Math.max(0, this.scrollX + e.deltaY);
            } else {
                const max = Math.max(0, this.rows.length * ROW - (this.h - RULER));
                this.scrollY = Math.min(max, Math.max(0, this.scrollY + e.deltaY * 0.5));
            }
            this.draw();
        }, { passive: false });
    }

    setAnim(anim, model) {
        this.anim = anim;
        this.model = model;
        this.rows = [];
        this.scrollY = 0;
        this.scrollX = 0;
        this.zoom = 1;
        if (anim) {
            this.rows.push({ label: 'Events', events: true });
            // Bones in tree order, only those with keys.
            const order = [];
            (function walk(bs) { for (const b of bs) { order.push(b.name); walk(b.children); } })(model.roots);
            const keyed = new Set(Object.keys(anim.tracks));
            for (const n of order) if (keyed.has(n)) this.rows.push({ label: n, bone: n });
            for (const n of keyed) if (!model.byName[n]) this.rows.push({ label: n + ' (missing)', bone: n });
        }
        this.renderNames();
        this.draw();
    }

    setSelected(bone) {
        this.selected = bone;
        this.renderNames();
        const i = this.rows.findIndex(r => r.bone === bone);
        if (i >= 0) {
            const top = i * ROW, view = this.h - RULER;
            if (top < this.scrollY) this.scrollY = top;
            else if (top + ROW > this.scrollY + view) this.scrollY = top + ROW - view;
        }
        this.draw();
    }

    setTime(t) { this.time = t; this.draw(); }

    renderNames() {
        const html = ['<div class="tl-name head">Bone</div><div class="tl-rows">'];
        for (const r of this.rows) html.push(`<div class="tl-name${r.bone && r.bone === this.selected ? ' sel' : ''}" data-bone="${r.bone || ''}">${r.label}</div>`);
        html.push('</div>');
        this.names.innerHTML = this.anim ? html.join('') : '<div class="empty">Pick an animation</div>';
        this.namesRows = this.names.querySelector('.tl-rows');
        this.names.querySelectorAll('.tl-name[data-bone]').forEach(el => el.addEventListener('click', () => el.dataset.bone && this.onSelectBone?.(el.dataset.bone)));
        this.syncNames();
    }

    syncNames() {
        if (this.namesRows) this.namesRows.style.transform = `translateY(${-this.scrollY}px)`;
    }

    resize() {
        const dpr = window.devicePixelRatio || 1;
        this.w = this.wrap.clientWidth;
        this.h = this.wrap.clientHeight;
        this.canvas.width = this.w * dpr;
        this.canvas.height = this.h * dpr;
        this.canvas.style.width = this.w + 'px';
        this.canvas.style.height = this.h + 'px';
        this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.draw();
    }

    pxPerSec() {
        const len = Math.max(0.5, this.anim?.length || 1);
        return ((this.w - 44) / len) * this.zoom;
    }
    timeToX(t) { return 12 + t * this.pxPerSec() - this.scrollX; }
    xToTime(x) {
        const len = this.anim?.length || 0;
        return Math.min(len, Math.max(0, (x - 12 + this.scrollX) / this.pxPerSec()));
    }

    css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

    draw() {
        const c = this.ctx;
        if (!this.w) return;
        const col = {
            line: this.css('--line'), text: this.css('--text-3'), panel: this.css('--panel'), panel2: this.css('--panel-2'),
            accent: this.css('--accent'), pos: this.css('--kf-pos'), rot: this.css('--kf-rot'), scale: this.css('--kf-scale'), ev: this.css('--kf-event'),
            soft: this.css('--accent-soft'),
        };
        c.clearRect(0, 0, this.w, this.h);
        this.syncNames();
        if (!this.anim) return;
        const len = this.anim.length;

        // rows
        c.save();
        c.beginPath();
        c.rect(0, RULER, this.w, this.h - RULER);
        c.clip();
        this.rows.forEach((r, i) => {
            const y = RULER + i * ROW - this.scrollY;
            if (y + ROW < RULER || y > this.h) return;
            if (r.bone && r.bone === this.selected) { c.fillStyle = col.soft; c.fillRect(0, y, this.w, ROW); }
            c.fillStyle = col.line;
            c.fillRect(0, y + ROW - 1, this.w, 1);
            const mid = y + ROW / 2;
            if (r.events) {
                for (const ev of this.anim.events) this.diamond(this.timeToX(ev.time), mid, 5, col.ev);
            } else {
                const t = this.anim.tracks[r.bone] || {};
                const lanes = [['position', col.pos, -4], ['rotation', col.rot, 0], ['scale', col.scale, 4]];
                for (const [ch, color, dy] of lanes) for (const k of t[ch] || []) this.diamond(this.timeToX(k.time), mid + dy, 3.5, color);
            }
        });
        // past the end
        const endX = this.timeToX(len);
        c.fillStyle = 'rgba(0,0,0,0.18)';
        if (endX < this.w) c.fillRect(endX, RULER, this.w - endX, this.h - RULER);
        c.restore();

        // ruler
        c.fillStyle = col.panel;
        c.fillRect(0, 0, this.w, RULER);
        c.fillStyle = col.line;
        c.fillRect(0, RULER - 1, this.w, 1);
        const pps = this.pxPerSec();
        const steps = [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10];
        const step = steps.find(s => s * pps >= 48) || 10;
        c.font = '10px ' + this.css('--font');
        c.textBaseline = 'middle';
        for (let t = 0; t <= len + 1e-6; t += step) {
            const x = this.timeToX(t);
            if (x < -20 || x > this.w + 20) continue;
            c.fillStyle = col.line;
            c.fillRect(Math.round(x), RULER - 7, 1, 6);
            c.fillStyle = col.text;
            c.fillText(t.toFixed(step < 0.1 ? 2 : step < 1 ? 2 : 0) + 's', x + 3, 10);
        }
        // 1-tick marks when zoomed in far enough
        if (0.05 * pps >= 8) {
            c.fillStyle = col.line;
            for (let t = 0; t <= len + 1e-6; t += 0.05) { const x = this.timeToX(t); if (x >= 0 && x <= this.w) c.fillRect(Math.round(x), RULER - 4, 1, 3); }
        }

        // playhead
        const px = Math.round(this.timeToX(this.time)) + 0.5;
        c.strokeStyle = col.accent;
        c.lineWidth = 1;
        c.beginPath();
        c.moveTo(px, RULER - 2);
        c.lineTo(px, this.h);
        c.stroke();
        c.fillStyle = col.accent;
        c.beginPath();
        c.moveTo(px - 5, 3); c.lineTo(px + 5, 3); c.lineTo(px + 5, 13); c.lineTo(px, 19); c.lineTo(px - 5, 13);
        c.closePath();
        c.fill();
    }

    diamond(x, y, r, color) {
        if (x < -r || x > this.w + r) return;
        const c = this.ctx;
        c.fillStyle = color;
        c.beginPath();
        c.moveTo(x, y - r); c.lineTo(x + r, y); c.lineTo(x, y + r); c.lineTo(x - r, y);
        c.closePath();
        c.fill();
    }
}

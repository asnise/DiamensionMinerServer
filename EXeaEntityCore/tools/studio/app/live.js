// Live link to a running server (EXeaEntityCore live_link): settings popover in the top bar, status
// dot, and send / command helpers the editors use. The token stays on this computer (local storage).
const S = window.studio;
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export class Live {
    constructor({ store, toast }) {
        this.store = store;
        this.toast = toast;
        this.link = store.get('live', { url: 'http://localhost:8086', token: '', player: '' });
        // start options: --live-url=… --live-token=… (scripts, CI)
        const q = new URLSearchParams(location.search);
        if (q.get('liveUrl')) this.link.url = q.get('liveUrl');
        if (q.get('liveToken')) this.link.token = q.get('liveToken');
        this.status = null;
        this.listeners = new Set();
        $('#btnLive').addEventListener('click', (e) => { e.stopPropagation(); this.toggle(); });
        document.addEventListener('click', (e) => { if (!e.target.closest('#livePop') && !e.target.closest('#btnLive')) $('#livePop').hidden = true; });
        this.render();
        if (this.link.token) this.check(true);
        setInterval(() => { if (this.link.token && !document.hidden) this.check(true); }, 15000);
    }

    get ok() { return !!this.status; }

    onChange(fn) { this.listeners.add(fn); }

    save() {
        this.store.set('live', this.link);
    }

    toggle() {
        const p = $('#livePop');
        p.hidden = !p.hidden;
        if (!p.hidden) this.render();
    }

    async check(quiet = false) {
        try {
            this.status = await S.live.call(this.link, 'GET', 'status');
            this.error = null;
        } catch (e) {
            this.status = null;
            this.error = e.message;
            if (!quiet) this.toast('Live link: ' + e.message, true);
        }
        this.render();
        this.listeners.forEach(f => f());
        return this.ok;
    }

    /** Uploads a job (see main.js live:send) and reloads. */
    async send(job) {
        if (!(await this.ensure())) return null;
        try {
            const r = await S.live.send(this.link, job);
            this.status = r.status;
            this.render();
            this.toast('Sent to server: ' + r.log.join(' · '));
            return r;
        } catch (e) {
            this.toast('Live send failed: ' + e.message, true);
            return null;
        }
    }

    /** A test command: spawn / skill / fx / anim / kill. */
    async cmd(name, body = {}) {
        if (!(await this.ensure())) return null;
        try {
            const r = await S.live.call(this.link, 'POST', name, { ...body, ...(this.link.player ? { player: this.link.player } : {}) });
            this.toast(r.ok || 'done');
            return r;
        } catch (e) {
            this.toast(`Live ${name}: ${e.message}`, true);
            return null;
        }
    }

    async ensure() {
        if (this.ok) return true;
        if (!this.link.token) {
            $('#livePop').hidden = false;
            this.render();
            this.toast('Set up the live link first (Live in the top bar)', true);
            return false;
        }
        return this.check();
    }

    render() {
        const dot = $('#btnLive .live-dot');
        dot.className = 'live-dot ' + (this.ok ? 'on' : this.link.token ? 'err' : '');
        $('#btnLive').title = this.ok ? `Live: connected (${this.status.players.length} player(s) online)` : this.error ? 'Live: ' + this.error : 'Live link: not set up';
        const p = $('#livePop');
        if (p.hidden) return;
        const players = this.status?.players || [];
        p.innerHTML = `
            <h3>Live link <span class="muted">${this.ok ? 'connected · EXea ' + esc(this.status.version) : this.error ? esc(this.error) : 'not connected'}</span></h3>
            <p class="hint">Sends models, movesets and effects to a running server and runs test commands. On the server, in
              <b>plugins/EXeaEntityCore/config.yml</b>: <code>live_link.enabled: true</code> and a <code>token</code> of 16+ characters.</p>
            <div class="form">
              <div class="frow"><label>Server</label><div class="ctl"><input type="text" id="lvUrl" value="${esc(this.link.url)}" placeholder="http://localhost:8086"></div></div>
              <div class="frow"><label>Token</label><div class="ctl"><input type="password" id="lvToken" value="${esc(this.link.token)}" placeholder="live_link.token">
                <button class="btn sm" id="lvFromFolder" title="Read it from the server folder's config.yml">From folder…</button></div></div>
              <div class="frow"><label title="Test commands happen at this player (default: the first one online)">Player</label><div class="ctl">
                <select id="lvPlayer"><option value="">${players.length ? 'first online' : 'nobody online'}</option>${players.map(n => `<option${n === this.link.player ? ' selected' : ''}>${esc(n)}</option>`).join('')}</select></div></div>
            </div>
            <div class="pane-tools" style="padding:10px 0 0"><button class="btn sm primary" id="lvConnect">Connect</button></div>
            ${this.ok ? `<p class="hint" style="margin-top:10px">${this.status.models.length} model(s) · ${this.status.effects.length} effect(s) · ${this.status.movesets.length} moveset(s) · ${this.status.entities} live entit${this.status.entities === 1 ? 'y' : 'ies'}</p>` : ''}`;
        const read = () => {
            this.link.url = $('#lvUrl').value.trim() || 'http://localhost:8086';
            this.link.token = $('#lvToken').value.trim();
            this.link.player = $('#lvPlayer').value;
            this.save();
        };
        $('#lvConnect').onclick = async () => { read(); if (await this.check()) this.toast('Live link connected'); };
        $('#lvPlayer').onchange = () => { read(); };
        $('#lvFromFolder').onclick = async () => {
            const d = this.store.get('serverDir', null) || await S.openDialog({ title: 'Server plugins/EXeaEntityCore folder', directory: true });
            if (!d) return;
            const t = await S.live.tokenFrom(d);
            if (!t) { this.toast('No live_link.token in that config.yml', true); return; }
            if (!t.enabled) this.toast('live_link.enabled is false in that config.yml: turn it on and restart the server', true);
            $('#lvToken').value = t.token;
            read();
            this.check();
        };
    }
}

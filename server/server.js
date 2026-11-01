const http = require('http');
const fs = require('fs');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3100;
const DATA_FILE = process.env.DATA_FILE || './data.json';
const TZ = process.env.TZ_NAME || 'Europe/Prague';
const CODE_RE = /^[A-HJ-NP-Z2-9]{8}$/;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const PKG_RE = /^[A-Za-z0-9._]{1,150}$/;
const MAX_CODES = 500;
const MAX_AGO_MS = 7 * 24 * 3600 * 1000;
const MAX_ENC_LEN = 60000;

const newDoc = () => ({
  blocked: false,
  blockedUntil: 0,
  blockedApps: [],
  schedule: [],
  ruleActive: null,
  grantUntil: 0,
  active: false,
  lockAt: 0,
  adminDisabledAt: 0,
  apps: null,
  usage: null,
  status: null,
  request: null,
});

const enc = (v) => (typeof v === 'string' && v.length > 0 && v.length <= MAX_ENC_LEN ? v : null);

let db = {};
try { db = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); } catch { /* první start */ }
for (const code of Object.keys(db)) db[code] = { ...newDoc(), ...db[code] };

let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => fs.writeFile(DATA_FILE, JSON.stringify(db), () => {}), 500);
}

const str = (v, n) => String(v == null ? '' : v).slice(0, n);
const num = (v, lo, hi) => Math.min(Math.max(Number(v) || 0, lo), hi);

const rooms = new Map();
const sigs = new Map();

function localNow() {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: TZ, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date());
  const get = (t) => parts.find((p) => p.type === t).value;
  const day = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 }[get('weekday')];
  return { day, min: parseInt(get('hour'), 10) * 60 + parseInt(get('minute'), 10) };
}
const toMin = (s) => { const [h, m] = s.split(':').map(Number); return h * 60 + m; };

function ruleActiveNow(r, n) {
  const s = toMin(r.s), e = toMin(r.e);
  if (s === e) return false;
  const prev = n.day === 1 ? 7 : n.day - 1;
  if (s < e) return r.d.includes(n.day) && n.min >= s && n.min < e;
  return (r.d.includes(n.day) && n.min >= s) || (r.d.includes(prev) && n.min < e);
}

function tick(code) {
  const d = db[code];
  const now = Date.now();
  let changed = false;
  if (d.blockedUntil && now >= d.blockedUntil) { d.blocked = false; d.blockedUntil = 0; changed = true; }
  if (d.grantUntil && now >= d.grantUntil) { d.grantUntil = 0; changed = true; }

  const n = localNow();
  const act = d.schedule.map((r) => ruleActiveNow(r, n));
  if (Array.isArray(d.ruleActive) && d.ruleActive.length === act.length) {
    act.forEach((a, i) => {
      if (a && !d.ruleActive[i] && d.schedule[i].lock) { d.lockAt = now; changed = true; }
    });
  }
  if (JSON.stringify(act) !== JSON.stringify(d.ruleActive)) { d.ruleActive = act; changed = true; }
  if (changed) save();
  return changed;
}

function stateObj(code) {
  const d = db[code];
  const now = Date.now();
  const granted = d.grantUntil > now;
  const scheduleActive = (d.ruleActive || []).some(Boolean);
  const netBlocked = !granted && (d.blocked || scheduleActive);
  return {
    t: 'state',
    blocked: d.blocked,
    blockedUntil: d.blockedUntil,
    grantUntil: granted ? d.grantUntil : 0,
    scheduleActive,
    netBlocked,
    appsBlocked: granted || netBlocked ? [] : d.blockedApps,
    blockedApps: d.blockedApps,
    schedule: d.schedule,
    active: d.active,
    lockAt: d.lockAt,
    adminDisabledAt: d.adminDisabledAt,
    childOnline: [...(rooms.get(code) || [])].some((c) => c.role === 'child'),
    now,
  };
}

function send(ws, obj) { if (ws.readyState === 1) ws.send(JSON.stringify(obj)); }

function broadcast(code) {
  const s = stateObj(code);
  const { now, ...rest } = s;
  sigs.set(code, JSON.stringify(rest));
  for (const c of rooms.get(code) || []) send(c, s);
}

function toRole(code, role, obj) {
  for (const c of rooms.get(code) || []) if (c.role === role) send(c, obj);
}

function cachedFor(code) {
  const d = db[code];
  const now = Date.now();
  const out = [];
  if (d.apps) out.push({ t: 'apps', ...d.apps, now });
  if (d.usage) out.push({ t: 'usage', ...d.usage, now });
  if (d.status) out.push({ t: 'status', ...d.status, now });
  if (d.request) out.push({ t: 'request', ...d.request, now });
  return out;
}

const fails = new Map();
const tooManyFails = (ip) => { const f = fails.get(ip); return f && f.reset > Date.now() && f.count >= 10; };
function addFail(ip) {
  const f = fails.get(ip);
  if (!f || f.reset <= Date.now()) fails.set(ip, { count: 1, reset: Date.now() + 60000 });
  else f.count++;
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('ok');
});
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 262144 });

function handleParent(ws, m) {
  const code = ws.code;
  const d = db[code];
  const now = Date.now();
  switch (m.t) {
    case 'set': {
      if (typeof m.blocked !== 'boolean') return false;
      d.blocked = m.blocked;
      const mins = num(m.minutes, 0, 1440);
      d.blockedUntil = m.blocked && mins > 0 ? now + mins * 60000 : 0;
      if (m.blocked) d.grantUntil = 0;
      return true;
    }
    case 'grant': {
      const mins = num(m.min, 0, 600);
      d.grantUntil = mins > 0 ? now + mins * 60000 : 0;
      if (mins > 0) toRole(code, 'child', { t: 'granted', min: mins });
      return true;
    }
    case 'lock':
      d.lockAt = now;
      return true;
    case 'setApps': {
      if (!Array.isArray(m.apps)) return false;
      d.blockedApps = m.apps.filter((p) => typeof p === 'string' && PKG_RE.test(p)).slice(0, 400);
      return true;
    }
    case 'setSchedule': {
      if (!Array.isArray(m.rules)) return false;
      const rules = [];
      for (const r of m.rules.slice(0, 8)) {
        if (!r || !Array.isArray(r.d) || !TIME_RE.test(r.s) || !TIME_RE.test(r.e)) return false;
        const days = [...new Set(r.d.map(Number).filter((x) => x >= 1 && x <= 7))];
        if (!days.length) return false;
        rules.push({ d: days, s: r.s, e: r.e, lock: !!r.lock });
      }
      d.schedule = rules;
      d.ruleActive = rules.map((r) => ruleActiveNow(r, localNow()));
      return true;
    }
    case 'getUsage':
    case 'getLocation':
      toRole(code, 'child', { t: m.t });
      return false;
    case 'find':
      toRole(code, 'child', { t: 'find', on: !!m.on });
      return false;
    case 'volume': {
      const mute = !!m.mute;
      const level = num(m.level, 0, 100);
      toRole(code, 'child', { t: 'volume', mute, level });
      return false;
    }
    case 'msg': {
      const textEnc = enc(m.textEnc);
      if (textEnc) toRole(code, 'child', { t: 'msg', textEnc });
      return false;
    }
    default:
      return false;
  }
}

function handleChild(ws, m) {
  const code = ws.code;
  const d = db[code];
  const now = Date.now();
  switch (m.t) {
    case 'active':
      if (typeof m.v !== 'boolean') return false;
      d.active = m.v;
      return true;
    case 'adminOff': {
      d.adminDisabledAt = now - num(m.ago, 0, MAX_AGO_MS);
      send(ws, { t: 'ack', k: 'adminOff' });
      return true;
    }
    case 'apps': {
      const listEnc = enc(m.listEnc);
      if (!listEnc) return false;
      d.apps = { listEnc };
      save();
      toRole(code, 'parent', { t: 'apps', listEnc });
      return false;
    }
    case 'usage': {
      const listEnc = enc(m.listEnc);
      d.usage = { listEnc, denied: !!m.denied, at: now };
      save();
      toRole(code, 'parent', { t: 'usage', ...d.usage, now });
      return false;
    }
    case 'status': {
      const s = d.status || {};
      if (Number.isFinite(m.bat)) s.bat = Math.round(num(m.bat, 0, 100));
      if (typeof m.chg === 'boolean') s.chg = m.chg;
      if (Number.isFinite(m.vol)) s.vol = Math.round(num(m.vol, 0, 100));
      s.vpnErr = !!m.vpnErr;
      s.locOk = !!m.locOk;
      const locEnc = enc(m.locEnc);
      if (locEnc) { s.locEnc = locEnc; s.locAt = now; }
      s.at = now;
      d.status = s;
      save();
      toRole(code, 'parent', { t: 'status', ...s, now });
      return false;
    }
    case 'request': {
      if (d.request && now - d.request.at < 30000) return false;
      const textEnc = enc(m.textEnc);
      if (!textEnc) return false;
      d.request = { at: now, textEnc };
      save();
      toRole(code, 'parent', { t: 'request', ...d.request, now });
      return false;
    }
    default:
      return false;
  }
}

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.count = 0;
  ws.on('pong', () => { ws.isAlive = true; });
  const ip = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();

  const fail = (reason) => {
    addFail(ip);
    send(ws, { t: 'error', m: reason });
    ws.close();
  };

  ws.on('message', (raw) => {
    if (++ws.count > 60) return ws.close();
    let m;
    try { m = JSON.parse(raw.toString()); } catch { return; }
    if (!m || typeof m !== 'object') return;

    if (!ws.code) {
      if (m.t !== 'join') return ws.close();
      if (tooManyFails(ip)) return ws.close();
      const code = String(m.code || '').toUpperCase();
      if (!CODE_RE.test(code) || (m.role !== 'child' && m.role !== 'parent')) return fail('bad_request');
      if (!db[code]) {
        if (m.role !== 'child' || Object.keys(db).length >= MAX_CODES) return fail('unknown_code');
        db[code] = newDoc();
        save();
      }
      ws.code = code;
      ws.role = m.role;
      if (!rooms.has(code)) rooms.set(code, new Set());
      rooms.get(code).add(ws);
      tick(code);
      broadcast(code);
      if (ws.role === 'parent') for (const msg of cachedFor(code)) send(ws, msg);
      return;
    }

    tick(ws.code);
    const changed = ws.role === 'parent' ? handleParent(ws, m) : handleChild(ws, m);
    if (changed) {
      tick(ws.code);
      save();
      broadcast(ws.code);
    }
  });

  ws.on('close', () => {
    if (!ws.code) return;
    const room = rooms.get(ws.code);
    if (!room) return;
    room.delete(ws);
    if (room.size === 0) rooms.delete(ws.code);
    else broadcast(ws.code);
  });
  ws.on('error', () => {});
});

setInterval(() => {
  for (const code of Object.keys(db)) {
    tick(code);
    if (!rooms.has(code)) continue;
    const { now, ...rest } = stateObj(code);
    if (JSON.stringify(rest) !== sigs.get(code)) broadcast(code);
  }
}, 5000);

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);
setInterval(() => { for (const ws of wss.clients) ws.count = 0; }, 10000);
setInterval(() => {
  for (const [ip, f] of fails) if (f.reset <= Date.now()) fails.delete(ip);
}, 60000);

server.listen(PORT, '127.0.0.1', () => console.log(`Watch server běží na 127.0.0.1:${PORT} (TZ ${TZ})`));

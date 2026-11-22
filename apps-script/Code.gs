/**
 * Price Scanner — sheet connector (auto-updating loader)
 *
 * This is the LAST time you paste a script. It downloads the latest sheet logic from
 * github.com/rentschlerm/price-scanner (apps-script/logic.js) and runs it, so future app
 * updates need no changes here.
 *
 * Setup / update:
 *  1. Extensions → Apps Script. Delete everything, paste this whole file, press Save.
 *  2. First time: Deploy → New deployment → ⚙ Web app → Execute as: Me, Who has access: Anyone → Deploy.
 *     Updating:   Deploy → Manage deployments → ✏ Edit → Version: New version → Deploy.
 *  3. Authorize when asked (it now also needs "connect to an external service" to download the logic).
 *  4. The /exec link stays the same.
 */

const LOGIC_URL = 'https://raw.githubusercontent.com/rentschlerm/price-scanner/main/apps-script/logic.js';
const FRESH_SECONDS = 300;      // check GitHub for new logic every 5 minutes
const BACKUP_SECONDS = 21600;   // keep the last good copy 6 hours in case GitHub is unreachable

function doGet(e) { return run_('doGet', e); }
function doPost(e) { return run_('doPost', e); }

function run_(fn, e) {
  let logic;
  try {
    logic = loadLogic_();
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: 'Could not load sheet logic: ' + err }))
      .setMimeType(ContentService.MimeType.JSON);
  }
  return logic[fn](e);
}

function loadLogic_() {
  const cache = CacheService.getScriptCache();
  let code = getBig_(cache, 'logic');
  if (!code) {
    try {
      const res = UrlFetchApp.fetch(LOGIC_URL + '?t=' + Date.now(), { muteHttpExceptions: true });
      if (res.getResponseCode() !== 200) throw new Error('HTTP ' + res.getResponseCode());
      code = res.getContentText();
      if (code.indexOf('handlePost_') < 0) throw new Error('unexpected file');
      putBig_(cache, 'logic', code, FRESH_SECONDS);
      putBig_(cache, 'logic_backup', code, BACKUP_SECONDS);
    } catch (err) {
      code = getBig_(cache, 'logic_backup');
      if (!code) throw err;
    }
  }
  return (new Function(code))();
}

// CacheService values are limited to 100 KB; split larger code into parts.
function putBig_(cache, key, text, ttl) {
  const size = 90000, parts = Math.ceil(text.length / size), map = {};
  map[key + ':n'] = String(parts);
  for (let i = 0; i < parts; i++) map[key + ':' + i] = text.slice(i * size, (i + 1) * size);
  cache.putAll(map, ttl);
}
function getBig_(cache, key) {
  const n = Number(cache.get(key + ':n') || 0);
  if (!n) return null;
  const keys = []; for (let i = 0; i < n; i++) keys.push(key + ':' + i);
  const got = cache.getAll(keys);
  let out = '';
  for (const k of keys) { if (got[k] == null) return null; out += got[k]; }
  return out;
}

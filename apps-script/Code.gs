/**
 * Price Scanner — sheet connector
 * Lets the Price Scanner app write barcodes and products into this spreadsheet.
 *
 * Setup (once):
 *  1. In this spreadsheet: Extensions → Apps Script. Delete what's there and paste this whole file. Save.
 *  2. Deploy → New deployment → type "Web app".
 *     Execute as: Me.   Who has access: Anyone.   → Deploy → Authorize (allow access).
 *  3. Copy the Web app URL (ends with /exec) and paste it into the app: ⟳ Sheet → "Sheet write link".
 */

const SCRIPT_VERSION = 2;
const SALES_TAB = 'Sales';
const STOCK_LOG_TAB = 'Stock Log';
// Tabs whose names contain any of these words are never treated as product tabs.
const SKIP_TAB_WORDS = ['search', 'lookup', 'dashboard', 'sales', 'stock log'];

function doGet() {
  return out_({ ok: true, version: SCRIPT_VERSION, msg: 'Price Scanner sheet link is working', tabs: dataSheets_().map(s => s.getName()) });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const req = JSON.parse(e.postData.contents);
    if (req.action === 'ping') return out_({ ok: true, version: SCRIPT_VERSION, tabs: dataSheets_().map(s => s.getName()) });
    if (req.action === 'save') return out_(saveProduct_(req));
    if (req.action === 'sale') return out_(recordSale_(req));
    if (req.action === 'stock') return out_(adjustStock_(req));
    return out_({ ok: false, error: 'Unknown action' });
  } catch (err) {
    return out_({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function norm_(s) {
  return String(s == null ? '' : s).trim().toLowerCase().replace(/\s+/g, ' ');
}

function dataSheets_() {
  return SpreadsheetApp.getActive().getSheets().filter(sh => !SKIP_TAB_WORDS.some(w => norm_(sh.getName()).includes(w)));
}

// Finds the header row (a "Product" column + a price column) in the first 15 rows.
function header_(sh, createBarcodeCol) {
  const lastR = Math.min(15, sh.getLastRow());
  if (lastR < 1) return null;
  const lastC = Math.max(sh.getLastColumn(), 1);
  const vals = sh.getRange(1, 1, lastR, lastC).getDisplayValues();
  for (let r = 0; r < vals.length; r++) {
    const row = vals[r].map(norm_);
    const pi = row.findIndex(h => h === 'product' || h === 'product name' || h === 'item' || h === 'item name');
    if (pi < 0 || !row.some(h => h.includes('price'))) continue;
    const f = (...k) => row.findIndex(h => k.some(x => h.includes(x)));
    const cols = {
      category: f('category'), name: pi, brand: f('brand'), size: f('size', 'unit'), qty: f('qty', 'quantity'),
      cost: f('cost'), price: f('selling', 'srp', 'retail'), barcode: f('barcode', 'upc', 'ean'),
      stock: f('stock', 'on hand', 'inventory'), reorder: f('reorder', 'low stock', 'min')
    };
    if (cols.price < 0) cols.price = row.findIndex((h, i) => h.includes('price') && i !== cols.cost);
    if (createBarcodeCol) {
      let c = row.length; // first empty column after the header's last filled cell
      while (c > 0 && row[c - 1] === '') c--;
      const addCol = (key, title) => {
        if (cols[key] >= 0) return;
        sh.getRange(r + 1, c + 1).setValue(title).setFontWeight('bold');
        cols[key] = c; c++;
      };
      const want = createBarcodeCol === true ? ['barcode'] : createBarcodeCol;
      if (want.includes('barcode')) addCol('barcode', 'Barcode');
      if (want.includes('stock')) addCol('stock', 'Stock');
      if (want.includes('reorder')) addCol('reorder', 'Reorder At');
    }
    return { row: r + 1, cols: cols };
  }
  return null;
}

function findRow_(sh, h, m) {
  const last = sh.getLastRow();
  if (last <= h.row) return -1;
  const vals = sh.getRange(h.row + 1, 1, last - h.row, sh.getLastColumn()).getDisplayValues();
  const c = h.cols;
  for (let i = 0; i < vals.length; i++) {
    const v = vals[i];
    if (norm_(v[c.name]) !== norm_(m.name)) continue;
    if (c.brand >= 0 && m.brand && norm_(v[c.brand]) && norm_(v[c.brand]) !== norm_(m.brand)) continue;
    if (c.size >= 0 && m.size && norm_(v[c.size]) && norm_(v[c.size]) !== norm_(m.size)) continue;
    return h.row + 1 + i;
  }
  return -1;
}

function lastDataRow_(sh, h) {
  const last = sh.getLastRow();
  if (last <= h.row) return h.row;
  const vals = sh.getRange(h.row + 1, h.cols.name + 1, last - h.row, 1).getDisplayValues();
  for (let i = vals.length - 1; i >= 0; i--) if (String(vals[i][0]).trim() !== '') return h.row + 1 + i;
  return h.row;
}

function writeFields_(sh, h, row, p) {
  const c = h.cols;
  const set = (col, v, asText) => {
    if (col < 0 || v === undefined || v === null) return;
    const cell = sh.getRange(row, col + 1);
    if (cell.getFormula()) return; // never overwrite formulas
    if (asText) cell.setNumberFormat('@');
    cell.setValue(v);
  };
  set(c.name, p.name);
  if (p.brand) set(c.brand, p.brand);
  if (p.size) set(c.size, p.size);
  if (p.category) set(c.category, p.category);
  if (p.qty !== '' && p.qty != null) set(c.qty, Number(p.qty));
  if (p.cost !== '' && p.cost != null) set(c.cost, Number(p.cost));
  if (p.price !== '' && p.price != null) set(c.price, Number(p.price));
  if (p.barcode != null) set(c.barcode, String(p.barcode), true);
  if (p.reorder !== '' && p.reorder != null) set(c.reorder, Number(p.reorder));
}

function saveProduct_(req) {
  const p = req.product || {};
  const m = req.match && req.match.name ? req.match : p;
  if (!p.name) return { ok: false, error: 'Missing product name' };
  let sheets = dataSheets_();
  if (req.tab) sheets.sort((a, b) => (b.getName() === req.tab) - (a.getName() === req.tab));

  // 1) Update the existing row wherever it is.
  for (const sh of sheets) {
    const h = header_(sh, false);
    if (!h) continue;
    const r = findRow_(sh, h, m);
    if (r > 0) {
      const h2 = header_(sh, p.reorder != null && p.reorder !== '' ? ['barcode', 'reorder'] : (p.barcode ? ['barcode'] : []));
      writeFields_(sh, h2, r, p);
      return { ok: true, updated: true, tab: sh.getName(), row: r };
    }
  }

  // 2) New product: pick the tab — the one the app asked for, else the tab named like the category,
  //    else a tab whose rows already use that category, else the first product tab.
  const withHeader = sheets.map(sh => ({ sh: sh, h: header_(sh, false) })).filter(x => x.h);
  if (!withHeader.length) return { ok: false, error: 'No tab with a Product / Price header row found' };
  let target = withHeader.find(x => req.tab && x.sh.getName() === req.tab)
    || withHeader.find(x => p.category && norm_(x.sh.getName()) === norm_(p.category))
    || withHeader.find(x => p.category && norm_(x.sh.getName()).includes(norm_(p.category)))
    || withHeader.find(x => {
      if (!p.category || x.h.cols.category < 0) return false;
      const last = x.sh.getLastRow();
      if (last <= x.h.row) return false;
      return x.sh.getRange(x.h.row + 1, x.h.cols.category + 1, last - x.h.row, 1).getDisplayValues()
        .some(v => norm_(v[0]) === norm_(p.category));
    })
    || withHeader[0];
  const h = header_(target.sh, ['barcode', 'stock', 'reorder']);
  const row = lastDataRow_(target.sh, h) + 1;
  writeFields_(target.sh, h, row, p);
  return { ok: true, added: true, tab: target.sh.getName(), row: row };
}

// ---------------- Inventory ----------------

function findProduct_(m, tab) {
  let sheets = dataSheets_();
  if (tab) sheets.sort((a, b) => (b.getName() === tab) - (a.getName() === tab));
  for (const sh of sheets) {
    const h = header_(sh, false);
    if (!h) continue;
    const r = findRow_(sh, h, m);
    if (r > 0) return { sh: sh, row: r };
  }
  return null;
}

function stockCell_(found) {
  const h = header_(found.sh, ['stock']);
  return found.sh.getRange(found.row, h.cols.stock + 1);
}

function logSheet_(name, headers) {
  const ss = SpreadsheetApp.getActive();
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  return sh;
}

function logStock_(when, p, change, after, reason, device) {
  const sh = logSheet_(STOCK_LOG_TAB, ['Date', 'Time', 'Product', 'Brand', 'Size', 'Change', 'Stock After', 'Reason', 'Device']);
  const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
  sh.appendRow([Utilities.formatDate(when, tz, 'yyyy-MM-dd'), Utilities.formatDate(when, tz, 'HH:mm:ss'),
    p.name || '', p.brand || '', p.size || '', change, after, reason || '', device || '']);
}

// Receive stock (delta), or set a counted amount (set). Logged in "Stock Log".
function adjustStock_(req) {
  const p = req.product || {};
  const found = findProduct_(req.match && req.match.name ? req.match : p, req.tab);
  if (!found) return { ok: false, error: 'Product not found in the sheet: ' + p.name };
  const cell = stockCell_(found);
  const before = Number(cell.getValue()) || 0;
  let after;
  if (req.set !== undefined && req.set !== null && req.set !== '') after = Number(req.set);
  else after = before + Number(req.delta || 0);
  cell.setValue(after);
  logStock_(new Date(req.at || Date.now()), p, after - before, after, req.reason || (req.set != null ? 'Count' : 'Received'), req.device);
  return { ok: true, tab: found.sh.getName(), row: found.row, stock: after };
}

// Records a completed sale in the "Sales" tab and deducts the stock of each item. Safe to retry.
function recordSale_(req) {
  const sale = req.sale || {};
  if (!sale.id || !sale.items || !sale.items.length) return { ok: false, error: 'Empty sale' };
  const sh = logSheet_(SALES_TAB, ['Date', 'Time', 'Sale ID', 'Product', 'Brand', 'Size', 'Qty', 'Price', 'Line Total', 'Sale Total', 'Cash', 'Change', 'Device']);
  const last = sh.getLastRow();
  if (last > 1) {
    const from = Math.max(2, last - 1500);
    const ids = sh.getRange(from, 3, last - from + 1, 1).getDisplayValues();
    if (ids.some(r => r[0] === sale.id)) return { ok: true, duplicate: true, stock: {} };
  }
  const when = new Date(sale.at || Date.now());
  const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
  const d = Utilities.formatDate(when, tz, 'yyyy-MM-dd'), t = Utilities.formatDate(when, tz, 'HH:mm:ss');
  const rows = sale.items.map((it, i) => [d, t, sale.id, it.name || '', it.brand || '', it.size || '', Number(it.qty) || 0,
    Number(it.price) || 0, (Number(it.price) || 0) * (Number(it.qty) || 0),
    i === 0 ? Number(sale.total) || 0 : '', i === 0 && sale.cash ? Number(sale.cash) : '', i === 0 && sale.change != null ? Number(sale.change) : '',
    sale.device || '']);
  sh.getRange(last + 1, 1, rows.length, rows[0].length).setValues(rows);

  const stock = {};
  for (const it of sale.items) {
    const found = findProduct_({ name: it.name, brand: it.brand, size: it.size }, it.tab);
    if (!found) continue;
    const h = header_(found.sh, false);
    if (h.cols.stock < 0) continue; // stock not tracked on this tab yet
    const cell = found.sh.getRange(found.row, h.cols.stock + 1);
    const raw = cell.getValue();
    if (raw === '' || raw === null) continue; // not counted yet — nothing to deduct from
    const after = (Number(raw) || 0) - (Number(it.qty) || 0);
    cell.setValue(after);
    stock[it.key || it.name] = after;
  }
  return { ok: true, stock: stock };
}

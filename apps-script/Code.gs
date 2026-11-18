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

// Tabs whose names contain any of these words are never written to (e.g. a search/lookup tab).
const SKIP_TAB_WORDS = ['search', 'lookup', 'dashboard'];

function doGet() {
  return out_({ ok: true, msg: 'Price Scanner sheet link is working', tabs: dataSheets_().map(s => s.getName()) });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const req = JSON.parse(e.postData.contents);
    if (req.action === 'ping') return out_({ ok: true, tabs: dataSheets_().map(s => s.getName()) });
    if (req.action === 'save') return out_(saveProduct_(req));
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
      cost: f('cost'), price: f('selling', 'srp', 'retail'), barcode: f('barcode', 'upc', 'ean')
    };
    if (cols.price < 0) cols.price = row.findIndex((h, i) => h.includes('price') && i !== cols.cost);
    if (cols.barcode < 0 && createBarcodeCol) {
      let c = row.length; // first empty column after the header's last filled cell
      while (c > 0 && row[c - 1] === '') c--;
      sh.getRange(r + 1, c + 1).setValue('Barcode').setFontWeight('bold');
      cols.barcode = c;
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
      const h2 = p.barcode ? header_(sh, true) : h;
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
  const h = header_(target.sh, true);
  const row = lastDataRow_(target.sh, h) + 1;
  writeFields_(target.sh, h, row, p);
  return { ok: true, added: true, tab: target.sh.getName(), row: row };
}

// Price Scanner web — offline cache. The page loads from the network when online (so updates show
// right away) and from this cache when offline. Sales made offline wait in the app and are sent later.
const CACHE = 'price-scanner-v1';
const FILES = ['./', 'index.html', 'zxing.min.js', 'manifest.json', 'icon-180.png', 'icon-192.png', 'icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return; // sheet traffic always goes to the network
  e.respondWith(
    fetch(e.request).then(res => { const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return res; })
      .catch(() => caches.match(e.request).then(m => m || caches.match('index.html')))
  );
});

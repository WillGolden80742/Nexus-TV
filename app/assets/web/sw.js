const CACHE_NAME = 'nexus-remote-v2';
const STATIC_ASSETS = [
  '/',
  '/manifest.json',
  '/css/materialdesignicons.min.css',
  '/css/style.css',
  '/fonts/materialdesignicons-webfont.woff2',
  '/js/app.js',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-maskable-512.png'
];

self.addEventListener('install', e => {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(STATIC_ASSETS)).catch(() => {})
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
    )).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const url = e.request.url;
  // Let WebSockets and API requests pass directly to the network
  if (url.includes('/api/') || url.includes('/ws')) {
    return;
  }
  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request).catch(() => caches.match('/') || caches.match('/index.html'))
    );
    return;
  }
  e.respondWith(
    fetch(e.request).catch(() => caches.match(e.request))
  );
});

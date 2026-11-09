// Yobareta PWA Service Worker - Handles offline caching and background Web Push alerts

const CACHE_NAME = 'yobareta-pwa-v1';
const ASSETS_TO_CACHE = [
  '/',
  '/index.html',
  '/nurse.html',
  '/css/style.css',
  '/js/patient.js',
  '/js/nurse.js',
  '/manifest.json'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS_TO_CACHE);
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  // Let API and WebSocket requests bypass the cache
  if (event.request.url.includes('/api/') || event.request.url.includes('/ws/')) {
    return;
  }
  event.respondWith(
    fetch(event.request).catch(() => caches.match(event.request))
  );
});

// Handle incoming Web Push notifications (if configured)
self.addEventListener('push', (event) => {
  let data = {};
  if (event.data) {
    try {
      data = event.data.json();
    } catch (_) {
      data = { title: 'Yobareta Alert', body: event.data.text() };
    }
  }

  const title = data.title || 'You were called! / お呼び出しです';
  const options = {
    body: data.instruction_plain || data.body || 'Please proceed to your destination.',
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    vibrate: [600, 200, 600, 200, 800],
    tag: 'yobareta-call-alert',
    renotify: true,
    requireInteraction: true,
    data: data
  };

  event.waitUntil(
    self.registration.showNotification(title, options)
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) return client.focus();
      }
      if (clients.openWindow) return clients.openWindow('/');
    })
  );
});

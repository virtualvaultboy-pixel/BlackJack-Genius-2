// ═══════════════════════════════════════════════════════════════════
// ══ BJ GENIUS — SERVICE WORKER ══
// ═══════════════════════════════════════════════════════════════════
// Stratégie utilisée : "stale-while-revalidate"
//   → Démarrage instantané depuis le cache (même hors-ligne)
//   → Mise à jour silencieuse en arrière-plan (à la connexion suivante)
//   → L'utilisateur reçoit automatiquement la nouvelle version au prochain lancement
//
// Pour forcer un rafraîchissement complet du cache, change CACHE_VERSION ci-dessous.

const CACHE_VERSION = 'bjgenius-v1';

// Ressources pré-chargées à l'installation (critique pour le mode hors-ligne)
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './manifest.json',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png'
];

// ── INSTALL : pré-cache les ressources, active immédiatement ──
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_VERSION)
      .then(cache => cache.addAll(ASSETS_TO_CACHE))
      .then(() => self.skipWaiting())
      .catch(err => console.warn('[SW] Pre-cache failed:', err))
  );
});

// ── ACTIVATE : nettoie les anciennes versions de cache ──
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE_VERSION).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

// ── FETCH : stale-while-revalidate ──
// Renvoie immédiatement la version en cache (si dispo), tout en téléchargeant en
// arrière-plan la dernière version pour la prochaine fois.
self.addEventListener('fetch', event => {
  // Ne traiter que les GET, et uniquement les ressources de notre origine
  if (event.request.method !== 'GET') return;
  if (!event.request.url.startsWith(self.location.origin)) return;

  event.respondWith(
    caches.open(CACHE_VERSION).then(async cache => {
      const cached = await cache.match(event.request);

      // Lancer le fetch réseau en arrière-plan pour rafraîchir le cache
      const networkPromise = fetch(event.request)
        .then(response => {
          if (response && response.ok) {
            cache.put(event.request, response.clone()).catch(() => {});
          }
          return response;
        })
        .catch(() => null);

      // Si on a une version en cache → retour immédiat (même si réseau dispo)
      // Sinon → on attend le réseau, et en dernier recours on retourne index.html
      return cached || (await networkPromise) || cache.match('./index.html');
    })
  );
});

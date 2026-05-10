// ═══════════════════════════════════════════════════════════════════
// ══ BJ GENIUS — SERVICE WORKER (auto-version) ══
// ═══════════════════════════════════════════════════════════════════
// Stratégie hybride :
//   • Network-first pour index.html → l'user a TOUJOURS la dernière version quand il a internet,
//     fallback cache si offline. Évite que l'user reste bloqué sur une vieille version.
//   • Stale-while-revalidate pour le reste (icônes, manifest, assets) → démarrage instantané.
//   • Listener `message` pour skipWaiting → permet au client de déclencher l'update sans reload manuel.
//
// 🎯 AUTO-VERSIONING — Plus besoin de bumper manuellement le cache à chaque release !
//
// Le SW lit la version dans `<span id="version-badge">vX.Y</span>` d'index.html au moment
// de l'install/activate, et utilise cette valeur comme nom de cache. Quand tu bumpes
// `#version-badge` dans index.html, le SW détecte automatiquement le changement → ouvre un
// nouveau cache "bjgenius-vX.Y" → l'ancien est supprimé à l'activate.
//
// Workflow tes prochaines releases :
//   1. Modifie index.html
//   2. Bump #version-badge (ex. v2.4 → v2.5)
//   3. Push GitHub
//   → Le SW autodétecte v2.5, vide l'ancien cache, recharge tout. Zéro action manuelle ici.

// Ressources pré-chargées à l'installation (critique pour le mode hors-ligne)
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './manifest.json',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png'
];

// Détecte la version courante depuis index.html. Fallback sur la longueur du contenu
// (≈ hash) si le badge n'est pas trouvé. Réseau requis (cache:'no-store').
async function detectVersion() {
  try {
    const r = await fetch('./index.html', { cache: 'no-store' });
    const text = await r.text();
    const m = text.match(/id="version-badge"[^>]*>(v[\d.]+)</);
    if (m) return m[1];
    // Fallback : hash basé sur la longueur du fichier (change si contenu change)
    return 'h' + text.length;
  } catch (e) {
    return 'unknown';
  }
}

// Cache name promise — calculé lazy une fois, réutilisé pour tous les fetch.
// Si le SW est réveillé après dormance, recalcule à l'install/activate.
let _cacheNamePromise = null;
function getCacheName() {
  if (!_cacheNamePromise) {
    _cacheNamePromise = (async () => {
      const v = await detectVersion();
      return 'bjgenius-' + v;
    })().catch(async () => {
      // Fallback ultime : utilise le bjgenius-* le plus récent en storage
      try {
        const keys = await caches.keys();
        const bjg = keys.filter(k => k.startsWith('bjgenius-'));
        return bjg[bjg.length - 1] || 'bjgenius-fallback';
      } catch (e) {
        return 'bjgenius-fallback';
      }
    });
  }
  return _cacheNamePromise;
}

// ── INSTALL : pré-cache les ressources sous le nom de cache versionné.
// On NE PAS skipWaiting auto : l'user déclenche le skip via le toast côté client (UX claire).
self.addEventListener('install', event => {
  // Reset de la promesse au cas où le SW est ré-installé après update
  _cacheNamePromise = null;
  event.waitUntil(
    (async () => {
      try {
        const cacheName = await getCacheName();
        const cache = await caches.open(cacheName);
        await cache.addAll(ASSETS_TO_CACHE);
        console.log('[SW] Pre-cache OK in', cacheName);
      } catch (err) {
        console.warn('[SW] Pre-cache failed:', err);
      }
    })()
  );
});

// ── ACTIVATE : nettoie les anciennes versions de cache, prend le contrôle des clients
self.addEventListener('activate', event => {
  // Reset pour re-détecter la version (au cas où elle a changé entre install et activate)
  _cacheNamePromise = null;
  event.waitUntil(
    (async () => {
      const currentName = await getCacheName();
      const keys = await caches.keys();
      await Promise.all(
        keys.filter(k => k.startsWith('bjgenius-') && k !== currentName).map(k => caches.delete(k))
      );
      await self.clients.claim();
      console.log('[SW] Activate. Active cache:', currentName);
    })()
  );
});

// ── MESSAGE : permet au client de déclencher skipWaiting (toast "Toucher pour recharger")
self.addEventListener('message', event => {
  if (event.data === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

// ── Helper : un fetch qui tente le réseau d'abord, fallback cache après timeout/erreur
function networkFirst(req, cache, timeoutMs = 3500) {
  return new Promise((resolve) => {
    let settled = false;
    const onCache = () => {
      if (settled) return;
      cache.match(req).then(c => {
        if (settled) return;
        settled = true;
        resolve(c || cache.match('./index.html'));
      });
    };
    const timer = setTimeout(onCache, timeoutMs);
    fetch(req).then(resp => {
      if (settled) return;
      clearTimeout(timer);
      settled = true;
      if (resp && resp.ok) {
        cache.put(req, resp.clone()).catch(() => {});
      }
      resolve(resp);
    }).catch(onCache);
  });
}

// ── FETCH ──
// • index.html / racine → network-first (priorité au frais, fallback cache)
// • Reste → stale-while-revalidate (cache immédiat + maj background)
self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  if (!event.request.url.startsWith(self.location.origin)) return;

  const url = new URL(event.request.url);
  const isHtml = url.pathname === '/' ||
                 url.pathname.endsWith('/') ||
                 url.pathname.endsWith('/index.html') ||
                 event.request.mode === 'navigate' ||
                 (event.request.headers.get('accept') || '').includes('text/html');

  event.respondWith(
    (async () => {
      const cacheName = await getCacheName();
      const cache = await caches.open(cacheName);

      if (isHtml) {
        return networkFirst(event.request, cache);
      }
      const cached = await cache.match(event.request);
      const networkPromise = fetch(event.request)
        .then(response => {
          if (response && response.ok) {
            cache.put(event.request, response.clone()).catch(() => {});
          }
          return response;
        })
        .catch(() => null);
      return cached || (await networkPromise) || cache.match('./index.html');
    })()
  );
});

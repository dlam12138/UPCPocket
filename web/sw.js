const VERSION = "v21";
const scope = new URL(self.registration.scope);
const CACHE_PREFIX = `upc-pocket-static:${encodeURIComponent(scope.href)}:`;
const CACHE_NAME = CACHE_PREFIX + VERSION;
const INDEX = new URL("./index.html", scope).href;
const APP_ENTRIES = new Set([scope.href, INDEX, new URL("./?library=1", scope).href]);
const STATIC_URLS = new Set([
  "./index.html",
  "./styles.css?v=19",
  "./app.js?v=21",
  "./db.js",
  "./todo.js",
  "./focus.js",
  "./classroom.js",
  "./classroom.css",
  "./timetable.js",
  "./timetable-import.js",
  "./timetable-store.js",
  "./timetable.css",
  "./calendar.js",
  "./calendar.css",
  "./service-catalog.js",
  "./service-catalog.css",
  "./library.js?v=20",
  "./library.css?v=20",
  "./login.js?v=1",
  "./login-crypto.js",
  "./manifest.webmanifest",
  "./app-icon.svg",
  "./campus.webp",
  "./icons/apple-touch-icon.png",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
].map(path => new URL(path, scope).href));
// Used only for .ics previews and bookmarklet setup, so they are cached on
// first use instead of being downloaded on every install.
const ON_DEMAND_URLS = new Set([
  "./vendor/ical.js?v=20",
  "./timetable-bookmarklet.js?v=20",
].map(path => new URL(path, scope).href));

self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll([...STATIC_URLS].map(url => new Request(url, {
      cache: "reload",
      redirect: "error",
      credentials: "omit",
    })));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names
      .filter(name => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME)
      .map(name => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", event => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== scope.origin) return;
  if (request.mode === "navigate") {
    // WebKit can retain the route fragment on a navigation request.
    url.hash = "";
    if (!APP_ENTRIES.has(url.href)) return;
    event.respondWith((async () => {
      try {
        return await fetch(request, { cache: "no-store" });
      } catch {
        const cache = await caches.open(CACHE_NAME);
        return await cache.match(INDEX) ?? Response.error();
      }
    })());
    return;
  }
  if (ON_DEMAND_URLS.has(url.href)) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match(url.href);
      if (cached) return cached;
      let response;
      try {
        response = await fetch(request);
      } catch {
        return Response.error();
      }
      if (response.ok) {
        try {
          await cache.put(url.href, response.clone());
        } catch {
          // A full or revoked cache must not break the online response.
        }
      }
      return response;
    })());
    return;
  }
  if (!STATIC_URLS.has(url.href)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    return await cache.match(url.href) ?? fetch(request);
  })());
});

# Fuentes declarativas

Cada `*.json` de esta carpeta describe una fuente sin código: dónde están sus listas, cómo leerlas y en qué dominios
vive. Cuando una web cambia su HTML o su dominio, el arreglo es editar su JSON; el paquete firmado llega solo al
servidor (cada 6 h) y a la app (al abrirla, como mucho cada 6 h), sin publicar APK.

## Cómo se publica un cambio

1. Edita `sources/<id>.json` (y añade el HTML nuevo como fixture en `shared/src/test/resources/` con su test).
2. Firma el paquete: `java scripts/SignSources.java` (usa la clave privada de `~/.yomukai/sources-signing.key`).
   En GitHub lo hace el workflow «Firmar fuentes» si el secreto `SOURCES_SIGNING_KEY` está configurado.
3. Sube `sources/` y `shared/src/main/resources/sources/bundle.json(.sig)`.

Sin una firma válida de la clave de Yomukai, el servidor y la app **ignoran** el paquete (siguen con el anterior).
La clave pública está en `SourceBundles.PUBLIC_KEY`; la privada nunca entra en el repositorio.

## Formato

```jsonc
{
  "id": "marmota",              // estable: nunca cambiarlo
  "name": "Marmota Comics",
  "lang": "ES",
  "kind": "manga",              // "manga" (imágenes) o "novel" (texto)
  "types": ["COMIC"],           // MANGA, MANHWA, MANHUA, COMIC, NOVEL
  "engine": "madara",           // html | madara | nextjs | sveltekit
  "baseUrl": "https://marmota.me",
  "domains": ["https://marmota.me", "https://marmota.net"],  // se prueban en orden si uno no responde
  "headers": { "X-Requested-With": "XMLHttpRequest" },       // nunca Cookie ni Authorization
  "latest":  { "url": "{base}/", "nextUrl": "{base}/page/{page}/", "item": ".card" },
  "search":  { "url": "{base}/?s={query}", "item": ".card" },
  "browse":  { "url": "{base}/lista?orden={sort}&p={page}", "item": ".card", "multi": { "status": "&estado[]={value}" } },
  "card":    { "link": "a@href", "title": ".titulo", "cover": "img", "altTitles": ".alt" },
  "details": { "title": "h1", "cover": ".portada img", "description": ".sinopsis", "author": ".autor a", "genres": ".generos a",
               "status": { "rows": ".info li", "heading": "b", "match": ["Estado"], "value": "span" } },
  "chapters":{ "url": "{media}capitulos/", "method": "GET", "item": "li.cap", "link": "a@href", "title": "a", "date": ".fecha" },
  "pages":   { "image": ".lector img" },
  "text":    { "content": ".texto", "remove": ["script", ".anuncio"] },
  "images":  { "attrs": ["data-src", "src"], "ignore": ["lazy.gif"], "forceHttps": true },
  "filters": [ { "key": "sort", "label": "Orden", "options": [ { "value": "latest", "label": "Recientes" } ] } ]
}
```

- **Selectores**: CSS (Jsoup). `selector@atributo` lee un atributo (`a@href`); `@own` el texto propio sin hijos.
- **Plantillas**: `{base}` (dominio actual), `{page}`, `{query}`, `{media}` (URL de la obra), y en catálogos cada filtro (`{sort}`).
- **JSON** (motores `nextjs` y `sveltekit`, o APIs que devuelven JSON): `"json": "props.pageProps.series[*]"` y
  `"map": { "title": "title", "url": "{base}/series/{slug}", "cover": "cover" }` — los valores son rutas o plantillas
  con rutas entre llaves. `nextjs` lee `__NEXT_DATA__`; `sveltekit` decodifica `__data.json` (formato devalue).
- **madara**: el tema de WordPress más común; trae todos los selectores por defecto, basta con id, nombre y dominio.

## Si algo cambia en la web

Cada dato prueba primero lo configurado y luego respaldos genéricos, así que un cambio pequeño rompe un campo, no la
fuente: páginas → JSON configurado → selector → imágenes dentro de los scripts → el contenedor con más imágenes;
ficha → JSON → selector → ld+json → metas `og:`. Un dominio que redirige a otro se sigue solo y avisa al admin.
El canario (`server canary`) detecta lo que siga roto y lo oculta hasta que se arregla.

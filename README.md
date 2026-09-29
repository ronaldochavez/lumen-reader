# Lumen — e-reader para Android con compañero de lectura IA (sin spoilers)

¿Vas en la página 180 y aparece un personaje que no recuerdas? Selecciónalo y pregúntale a Lumen quién es:
la IA solo lee el libro **desde el inicio hasta tu página**, así que nunca te adelanta nada.

Lector con estética cálida: biblioteca con portadas, colecciones, subrayados en 4 colores con notas,
marcadores, índice, temas Papel / Sepia / Noche / Negro y color de acento a elegir.

## Descargar e instalar

1. En tu teléfono o tablet Android (8.0 o más nuevo), abre la página de
   [**Releases**](../../releases/latest) y descarga `Lumen-*.apk`.
2. Ábrelo. Android pedirá permiso para «instalar apps de fuentes desconocidas» para tu navegador o gestor de
   archivos: acéptalo solo para esta instalación.
3. Importa tus libros con **Importar libro** (EPUB, PDF, MOBI, cómics…).
4. Para la IA, la app te guía para crear una key **gratuita** de Google Gemini (sin tarjeta) la primera vez
   que preguntes algo. Opcionalmente puedes usar tu propia key de Anthropic (Claude).

Tus libros, subrayados y keys se quedan en tu dispositivo; no hay cuentas ni servidores de Lumen. Solo el texto
que preguntas (y el libro hasta tu página) se envía al proveedor de IA que elijas.

## Formatos

| Formato | Cómo se lee | IA / árbol |
|---|---|---|
| EPUB | Texto reflowable (WebView paginado) | Sí |
| MOBI, AZW3, AZW, PRC (sin DRM) | Convertido a capítulos HTML al importar | Sí |
| FB2 / FB2.ZIP, DOCX, RTF, TXT, MD, HTML | Convertido a capítulos HTML al importar | Sí |
| PDF | Páginas con su diseño original (PdfRenderer), zoom con pellizco | Sí, con el texto extraído por página (no en PDFs escaneados) |
| CBZ, CBR (RAR4) | Páginas de imagen | No (no hay texto) |

No se admiten libros con DRM (Kindle, Adobe, Apple, LCP): la app lo detecta y lo explica. Tampoco DjVu, CHM,
LIT ni KFX; para esos se sugiere convertir con Calibre. MOBI con compresión HUFF/CDIC (poco común) aún no.

## Más funciones

- **Definir / traducir:** en el menú de selección, «Definir» explica la palabra en ese pasaje (y la traduce si el
  libro no está en español). Solo envía el párrafo alrededor, nunca texto más allá de la página visible.
- **Enciclopedia del libro:** lugares, objetos, términos, facciones y eventos con su ficha, hasta tu página.
- **Búsqueda en el libro:** sin distinguir mayúsculas ni acentos; por defecto solo hasta donde vas (sin spoilers).
- **Leer en voz alta:** voz del sistema (offline y gratis), resalta la frase y pasa de página sola. Se pausa al
  salir de la app (aún no hay servicio de audio en segundo plano).
- **Progreso:** minutos por día, racha, meta diaria y «te faltan ~X para terminar» según tu ritmo real. El tiempo
  solo cuenta con el lector en pantalla y actividad en los últimos 3 minutos.
- **Botones de volumen:** bajar = siguiente página, subir = anterior (se desactiva en Ajustes → Lectura; mientras
  lee en voz alta, los botones controlan el volumen).
- **Volver a donde ibas:** tras saltar (búsqueda, índice, subrayado, barra, enlace) aparece un botón para regresar;
  la posición guardada no cambia hasta que lees 3 páginas en el nuevo lugar o tocas ✕.
- **Respaldo:** Ajustes → Tus datos crea un .zip con libros, subrayados, notas, marcadores, colecciones, progreso y
  estadísticas, y lo restaura (lo anterior se guarda 7 días en `pre-restore/`). También exporta los subrayados a
  Markdown. Antes de cualquier actualización de la base de datos se copia a `db-snapshots/`.

Además tiene funciones de IA sin spoilers:

- **Pregúntale a Lumen.** Mantén presionado cualquier texto → *Preguntar a la IA* → «¿Quién es y de dónde salió?».
  La IA recibe **solo el libro desde el inicio hasta el final de tu página actual** y tiene instrucciones de no
  usar lo que pueda saber del libro por otras fuentes. También sirve para lugares, objetos, términos o eventos.
  El botón **¿Dónde me quedé?** hace un resumen hasta tu posición.
- **Árbol de personajes.** Mapa interactivo (zoom, paneo, tocar un personaje) de quién es quién y cómo se
  relacionan, generado hasta tu página. Se guarda; si avanzas, te ofrece actualizarlo. Si retrocediste y el mapa
  guardado va más adelante, te avisa antes de mostrarlo.

## Compilar e instalar

Requisitos: JDK 17 y Android SDK 36 (o simplemente Android Studio reciente).

```bash
./gradlew :app:assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Con un teléfono conectado (depuración USB):

```bash
./gradlew :app:installDebug
```

Para un APK de release firmado, crea `keystore.properties` en la raíz (no se sube a git) con `storeFile`,
`storePassword`, `keyAlias` y `keyPassword`, y ejecuta `./gradlew :app:assembleRelease`.

## IA: gratis primero, key personal después

1. **Gratis (Gemini).** La primera vez que preguntas algo, la hoja de la IA ofrece activarla: se abre Google AI
   Studio, el usuario crea una key gratuita (sin tarjeta) y la pega ahí mismo. Usa `gemini-3.8-flash` (1M de
   contexto). En el plan gratuito Google puede usar los textos para mejorar sus productos; la app lo avisa.
2. **Personal (Claude).** Si se acaba la cuota gratis (error 429) la app lo explica y sugiere agregar una key de
   Anthropic en Ajustes. Con key de Anthropic, Claude tiene prioridad (Opus 5.5 por defecto; Sonnet 5.5 o
   Haiku 4.5 opcionales).

Ambas keys se guardan cifradas con el Android Keystore. Los prompts y reglas anti-spoiler son los mismos para
los dos proveedores (`ai/AiCommon.kt`); `AiService` elige el proveedor en cada petición.

## Cómo funciona el «sin spoilers»

1. Al importar, cada capítulo del EPUB se convierte a texto plano (`files/books/<id>/.folio/text/<n>.txt`).
2. El lector pagina cada capítulo con columnas CSS dentro de un WebView. `reader.js` mide posiciones como
   *offsets de carácter* en el texto del capítulo, así que sabe exactamente qué carácter es el último visible.
3. Al preguntar, se envían los capítulos anteriores completos + el capítulo actual **cortado en el último carácter
   de tu página** (o el final de tu selección si pasa de página), con un marcador `[EL LECTOR VA AQUÍ…]`.
4. El prompt de sistema prohíbe usar conocimiento externo del libro y especular sobre lo que viene.

Costo: los capítulos ya leídos van en un bloque con *prompt caching* (TTL 1 h), así que las preguntas seguidas
en el mismo capítulo reutilizan la caché y cuestan una fracción de la primera.

## Estructura

```
app/src/main/
  assets/reader/          reader.js (paginación, offsets, subrayados, selección) + reader.css
  java/com/folio/reader/
    ai/                   ClaudeService (SDK Java de Anthropic), contexto sin spoilers, modelo del grafo
    data/                 Room (libros, capítulos, índice, subrayados, marcadores, colecciones, grafos),
                          importador/parser EPUB, ajustes (DataStore + Keystore)
    data/convert/         Detección de formato y convertidores (MOBI, FB2, DOCX, RTF, TXT, HTML),
                          importación de PDF (pdfbox-android) y cómics (CBZ/CBR)
    ui/reader/fixed       Lector de páginas para PDF y cómics
    ui/library            Biblioteca («Seguir leyendo», filtros por colección, importar)
    ui/collections        Colecciones
    ui/reader             Lector: WebView, menú de selección propio, hojas de índice/notas/tipografía/IA
    ui/graph              Árbol de personajes (layout de fuerzas + Canvas)
    ui/settings           API key y modelo
tools/reader-harness.html Prueba el motor de lectura en un navegador de escritorio (tools/serve.ps1)
```

Tests JVM: `./gradlew :app:testDebugUnitTest` (forma de las peticiones a Claude y corte de texto).

## Pendiente / ideas

- Subrayados en PDF (hoy solo marcadores) y modo "reflow" de PDF.
- MOBI con compresión HUFF/CDIC; CBR en RAR5; CB7.
- Sincronización automática entre dispositivos (hoy: respaldo manual en .zip).
- Para publicar en Play Store con usuarios que no tengan API key: un backend propio que guarde la key.

## Licencia

[MIT](LICENSE). El código interno (paquete `com.folio.reader`) conserva el nombre original del proyecto.

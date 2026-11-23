# PasaArchivos

App Android para pasar fotos, videos y cualquier archivo **entre el celular y la PC por wifi**, en los dos sentidos, sin cuentas, sin nube y sin instalar nada en la PC. Pensada para computadoras de trabajo donde no se puede instalar programas ni conviene vincular cuentas personales.

Hecha por [Garúa](https://garuas.com).

## Cómo se usa

1. Instalá el APK de la [última versión](https://github.com/AmargoRM/PasaArchivos/releases/latest/download/PasaArchivos.apk) en el celular y abrilo.
2. En la PC, conectada a la misma red wifi, escribí en el navegador la dirección que muestra el celular y el código de 4 dígitos.
3. En esa página tocá **Guardar acceso en esta PC**. Se descarga `PasaArchivos.html`: guardalo en el Escritorio y la próxima vez solo abrilo con doble clic. El acceso busca al celular solo y espera si todavía no está listo.

- **Del celular a la PC:** en la app, «Fotos y videos» o «Archivos». En la PC aparecen con miniatura; se descargan uno por uno, seleccionados o todos en un .zip.
- **De la PC al celular:** arrastrá archivos a la página. Quedan en el celular en *Descargas › PasaArchivos*.

## Seguridad, en corto

- Todo viaja solo dentro de la red wifi; nada pasa por internet ni por servidores.
- Para entrar hace falta el código (cambia cada vez que se inicia la app) o el acceso guardado, que se puede anular desde la app.
- La conexión **no va cifrada**: dentro de la misma wifi, alguien con conocimientos podría interceptar archivos. Evitá usarla en redes públicas.
- La única conexión a internet que hace la app es revisar, como máximo cada 6 horas, si hay una versión nueva en esta página de GitHub.

## Para desarrollar

- Proyecto Android en Java, sin dependencias externas. `minSdk 26`, `targetSdk 36`.
- GitHub Actions compila cada cambio en `main`. El número de versión está en el archivo `VERSION`: si cambia y todavía no hay publicación para ese número, **publica una versión** nueva en Releases. El `versionCode` sale de ese número: `2.1.3` → `20103`.
- Las notas de cada versión van en `NOTAS_VERSION.txt`; la app las muestra en el aviso de actualización.
- La firma usa los secretos `KEYSTORE_B64` y `KEYSTORE_PASS` del repositorio. **Sin esa llave no se pueden publicar actualizaciones** para las apps ya instaladas.

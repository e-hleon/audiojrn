<p align="center">
  <img src="assets/audiojrn-logo.png" width="160" alt="AudioJrn logo">
</p>

<h1 align="center">AudioJrn</h1>

AudioJrn permite grabar notas de audio desde Android y procesarlas con un backend
local. El backend transcribe el audio, puede generar análisis estructurado cuando se
configura OpenAI y guarda las interacciones en PostgreSQL.

El proyecto incluye una aplicación Android, un backend FastAPI, PostgreSQL y Docker
Compose para ejecutar los servicios.

## Requisitos

- Docker Engine o Docker Desktop con Docker Compose v2 y acceso NVIDIA para la
  transcripción por GPU.
- Una GPU NVIDIA compatible, conexión a Internet para la primera descarga de imagen,
  dependencias y modelo, y un puerto local libre (8000 por defecto).
- Android Studio reciente o Android SDK 35, JDK 17 y el Gradle Wrapper incluido para
  la aplicación Android (minSdk 26).
- `curl` para comprobar la API y `adb` para instalar la APK.

## Backend y base de datos

Crear la configuración local a partir del ejemplo:

```bash
cp .env.example .env
```

`OPENAI_API_KEY` es opcional. Configúrala en `.env` solo si se van a usar las
funciones de análisis; la transcripción no la necesita.

Levantar el backend y PostgreSQL:

```bash
docker compose up -d
```

Aplicar las migraciones una vez que PostgreSQL esté disponible:

```bash
docker compose exec api alembic upgrade head
```

Comprobar la API:

```bash
curl --fail http://127.0.0.1:8000/health
```

Para usar otro puerto o permitir acceso desde una LAN de confianza, ajustar
`API_PORT` y `API_BIND_HOST` en `.env`. La URL configurada en Android debe apuntar
al host y puerto accesibles desde el dispositivo; se establece desde la pantalla de
Configuración de la aplicación.

## Conectividad y seguridad

La aplicación Android admite direcciones HTTP y HTTPS. El despliegue Compose queda
limitado por defecto a loopback y no configura terminación TLS ni autenticación. Para
acceso desde una red local puede configurarse, por ejemplo,
`http://192.168.x.x:8000`; HTTP no cifra el tráfico. Para acceso remoto puede
situarse un proxy inverso con HTTPS delante de FastAPI sin modificar la aplicación
Android. Antes de exponer el servicio públicamente sigue siendo necesario añadir un
mecanismo adecuado de autenticación.

## Android

Desde el directorio `android/`, compilar la APK de depuración con el Gradle Wrapper:

```bash
cd android
./gradlew assembleDebug
```

Instalarla en un dispositivo conectado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pruebas

Los tests principales del backend se ejecutan con la imagen de test:

```bash
docker build --target test -t audiojrn-tests .
docker run --rm --entrypoint python3 audiojrn-tests -m pytest -q
```

Los tests unitarios principales de Android se pueden ejecutar desde `android/`:

```bash
./gradlew testDebugUnitTest
```

También está disponible una comprobación rápida de la superficie de privacidad de
Android:

```bash
python3 scripts/check_android_privacy.py
```

## Detener los servicios

```bash
docker compose down
```

Los volúmenes de PostgreSQL y del modelo se conservan para el siguiente arranque.

## License

AudioJrn is licensed under the [Apache License 2.0](LICENSE). Third-party
components retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

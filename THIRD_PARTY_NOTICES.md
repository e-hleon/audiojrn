# Third-party notices

AudioJrn uses the following third-party components. Each component remains
subject to its own copyright notices and license terms; this document does not
relicense any third-party software.

## Android / APK

| Component | License | Project |
| --- | --- | --- |
| AndroidX / Jetpack (Core, Activity, Lifecycle, Room and WorkManager) | Apache License 2.0 | <https://developer.android.com/jetpack/androidx> |
| Jetpack Compose, Material and Material 3 | Apache License 2.0 | <https://developer.android.com/jetpack/compose> |
| Kotlin runtime, coroutines and serialization | Apache License 2.0 | <https://kotlinlang.org/> |
| Retrofit | Apache License 2.0 | <https://github.com/square/retrofit> |
| OkHttp / Okio | Apache License 2.0 | <https://github.com/square/okhttp> |
| Gradle Wrapper | Apache License 2.0 | <https://gradle.org/> |

## Backend runtime

| Component | License | Project |
| --- | --- | --- |
| FastAPI | MIT License | <https://fastapi.tiangolo.com/> |
| Uvicorn | BSD 3-Clause License | <https://www.uvicorn.org/> |
| python-multipart | Apache License 2.0 | <https://github.com/Kludex/python-multipart> |
| faster-whisper | MIT License | <https://github.com/SYSTRAN/faster-whisper> |
| CTranslate2 | MIT License | <https://github.com/OpenNMT/CTranslate2> |
| huggingface-hub | Apache License 2.0 | <https://github.com/huggingface/huggingface_hub> |
| OpenAI Python SDK | Apache License 2.0 | <https://github.com/openai/openai-python> |
| SQLAlchemy | MIT License | <https://www.sqlalchemy.org/> |
| psycopg | GNU Lesser General Public License v3.0 | <https://www.psycopg.org/> |
| Alembic | MIT License | <https://alembic.sqlalchemy.org/> |

The default Whisper model is downloaded at runtime and is not included in this
repository. Its model license is maintained separately by its upstream source.

A redistributed Docker image can include additional operating-system, native
library, CUDA, or cuDNN components subject to their own license terms and
notices.

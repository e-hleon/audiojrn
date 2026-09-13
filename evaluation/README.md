# Caracterización de la configuración probada

Esta carpeta permite medir una configuración concreta de AudioJrn. Los resultados
dependen del modelo ASR, el tipo de cómputo, el hardware, el modelo externo y los
casos utilizados; no constituyen un resultado universal de Whisper, OpenAI o la
plataforma.

Los audios, modelos y resultados se guardan en rutas ignoradas por Git. La suite de
CI solo ejecuta `tests/test_evaluation.py`: no descarga corpus, no utiliza GPU y no
realiza llamadas reales a OpenAI.

## ASR

La preparación selecciona de forma determinista un subconjunto español de FLEURS y
conserva un manifiesto con los identificadores y las referencias:

```bash
python -m evaluation.asr.prepare --limit 50 --seed 20260905
```

Una vez elegido el modelo de uso habitual, se caracteriza esa configuración:

```bash
python -m evaluation.asr.run --models MODELO --limit 50 \
  --device cuda --compute-type int8_float16
```

El agregado incluye WER y CER de corpus, latencia, RTF, fallos, tiempo de carga,
modelo, dispositivo, tipo de cómputo, plataforma y datos de la GPU. Se realiza un
calentamiento antes de cronometrar las muestras.

## Extracción estructurada

El conjunto principal actual contiene 18 casos y el holdout, 6. Ambos usan el
contrato `highlights`, `tasks` y `events`, con contexto temporal fijo. El
emparejamiento es uno a uno mediante `evidence` literal y no utiliza otro modelo de
lenguaje como juez.

```bash
python -m evaluation.llm.run \
  --fixtures evaluation/fixtures/llm_cases_current.jsonl \
  --output-dir evaluation/results/llm

python -m evaluation.llm.run \
  --fixtures evaluation/fixtures/llm_holdout_current.jsonl \
  --output-dir evaluation/results/llm-holdout
```

Se calculan TP, FP, FN, precisión, exhaustividad y F1 para cada categoría y de forma
global, además del porcentaje de respuestas válidas y la latencia. Para los
elementos emparejados también se comprueban `tasks.due_at`, `events.start_at` y
`events.all_day` por igualdad exacta.

Los ficheros `llm_cases.jsonl` y `llm_holdout.jsonl` se conservan como material
histórico del contrato anterior. Los comandos actuales no los seleccionan por
defecto.

## Informe

```bash
python -m evaluation.report
```

El informe lee los agregados JSON y genera tablas y figuras sin introducir cifras
manualmente. Si ya existe un fichero de predicciones, debe usarse `--resume` o un
directorio de salida nuevo para evitar sobrescribir resultados.

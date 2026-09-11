FROM nvidia/cuda:12.3.2-cudnn9-runtime-ubuntu22.04 AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HF_HOME=/models HF_HUB_DISABLE_TELEMETRY=1
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates tzdata && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY alembic.ini .
COPY alembic ./alembic
EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]

FROM runtime AS test
COPY requirements-test.txt .
RUN pip install --no-cache-dir -r requirements-test.txt
COPY tests ./tests
COPY evaluation ./evaluation
CMD ["python3", "-m", "pytest", "-q"]

FROM runtime AS eval
COPY requirements-eval.txt .
RUN pip install --no-cache-dir -r requirements-eval.txt
COPY evaluation ./evaluation
CMD ["python3", "-m", "evaluation.report"]

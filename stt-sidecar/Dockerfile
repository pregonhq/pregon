FROM python:3.13.16-slim-trixie

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    # Models are downloaded once into this volume (ADR-0002); nothing else is ever written.
    PREGON_STT_MODEL_DIR=/models \
    HF_HOME=/models/hf \
    HF_HUB_DISABLE_TELEMETRY=1 \
    TOKENIZERS_PARALLELISM=false

WORKDIR /app

COPY requirements.txt .
# --require-hashes: every package must match the hash recorded in requirements.txt.
RUN pip install --root-user-action=ignore --require-hashes -r requirements.txt

COPY app/ app/

RUN useradd --system --no-create-home --shell /usr/sbin/nologin pregon \
 && mkdir /models && chown pregon:pregon /models
USER pregon
VOLUME /models

EXPOSE 8000
# /health is 503 until the model is loaded; the first start downloads it, hence the long start period.
HEALTHCHECK --interval=10s --timeout=3s --start-period=300s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).status == 200 else 1)"]

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header"]

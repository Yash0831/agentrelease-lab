# AgentRelease Lab — agent worker image.
# Base pinned; verified 2026-09-29 (python:3.12-slim).
# Build context is the repository root (see docker-compose.yml).
FROM python:3.12-slim
WORKDIR /srv/worker
COPY agent-worker/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY agent-worker/app ./app
COPY agent-worker/fixtures ./fixtures
# Model price table (metrics.py resolves ARL_PRICE_TABLE or the repo path).
COPY eval/price-table.json ./eval/price-table.json
ENV ARL_PRICE_TABLE=/srv/worker/eval/price-table.json
EXPOSE 8001
HEALTHCHECK --interval=10s --timeout=5s --retries=12 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/health', timeout=4)" || exit 1
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port 8001 & python -m app.worker consume"]

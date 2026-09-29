# AgentRelease Lab — agent worker image.
# Base pinned; verified 2026-09-29 (python:3.12-slim).
FROM python:3.12-slim
WORKDIR /srv/worker
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY fixtures ./fixtures
EXPOSE 8001
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port 8001 & python -m app.worker consume"]

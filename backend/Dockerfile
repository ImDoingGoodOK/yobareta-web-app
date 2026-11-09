FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8080 \
    GOOGLE_CLOUD_PROJECT=yobareta \
    STT_LOCATION=us \
    STT_MODEL=chirp_3 \
    GEMINI_LOCATION=asia-northeast1 \
    GEMINI_MODEL=gemini-2.5-flash \
    REQUIRE_AUTH=false \
    REQUIRE_APP_CHECK=false

WORKDIR /app

# Install system build dependencies if needed by wheels
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Run unit tests at build time so broken matcher logic can never deploy
RUN pytest -q tests/test_matcher.py

# Run as non-root user on Cloud Run
RUN useradd -m appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8080} --workers 1"]

FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY pyproject.toml README.md ./
COPY strat ./strat
COPY runner ./runner

RUN python -m pip install --upgrade pip \
    && python -m pip install . \
    && python -m pip install uvicorn[standard]

EXPOSE 10000

CMD ["sh", "-c", "uvicorn runner.server:app --host 0.0.0.0 --port ${PORT:-10000}"]

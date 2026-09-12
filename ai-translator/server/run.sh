#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if [ ! -d .venv ]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo ".env yaratildi — GEMINI_API_KEY ni to'ldiring."
  exit 1
fi

exec .venv/bin/python app.py

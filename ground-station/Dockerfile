FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# To use actian-vectorai wheel:
# COPY vendor/ vendor/
# RUN pip install vendor/*.whl
COPY . .
CMD uvicorn app:app --host 0.0.0.0 --port 8000

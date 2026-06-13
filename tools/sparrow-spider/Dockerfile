FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY tools/sparrow-spider/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY tools/sparrow-spider/ .

ENTRYPOINT ["python", "main.py"]
CMD ["--all", "--limit", "20"]

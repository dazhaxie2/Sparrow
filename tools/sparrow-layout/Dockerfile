FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY tools/sparrow-layout/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY tools/sparrow-layout/ .

# 默认对真实节点做原子重建布局(rebuild 走 staging 表 + RENAME 切换,失败不清空线上)。
# scope/strategy/max-nodes 由 docker-compose 的 command 覆盖。
ENTRYPOINT ["python", "layout.py"]
CMD ["--scope", "real", "--rebuild"]

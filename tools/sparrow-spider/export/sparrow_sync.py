# -*- coding: utf-8 -*-
"""一键内化:把抽取产物直接同步进 Sparrow 业务库(替代手工导 SQL 文件)。

做四件事(全部幂等):
1. 新技术节点 + 依赖边 → sparrow.tech_node / tech_edge
2. 既有节点 detail 增补(仅当新内容更充实)
3. 全部词条语料 → sparrow.rag_document(给 Sparrow 的 RAG embedding 管道消费)
4. 失效 Redis 科技树缓存(sparrow:graph:tree),前端立即可见
"""
import socket
import time
import urllib.request

import pymysql

import config
from knowledge_extraction.extractor import canonical_name_of
from storage import db
from topic_discovery.seeds import SPARROW_NODES

RAG_TABLE_DDL = """
CREATE TABLE IF NOT EXISTS rag_document (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    title      VARCHAR(256) NULL,
    url        VARCHAR(512) NULL,
    content    MEDIUMTEXT   NOT NULL,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
"""


def _graph_conn():
    return pymysql.connect(
        host=config.SPARROW_GRAPH_DB_HOST, port=config.SPARROW_GRAPH_DB_PORT,
        user=config.SPARROW_GRAPH_DB_USER, password=config.SPARROW_GRAPH_DB_PASSWORD,
        database=config.SPARROW_GRAPH_DB_NAME, charset="utf8mb4", autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def _rag_conn():
    return pymysql.connect(
        host=config.SPARROW_RAG_DB_HOST, port=config.SPARROW_RAG_DB_PORT,
        user=config.SPARROW_RAG_DB_USER, password=config.SPARROW_RAG_DB_PASSWORD,
        database=config.SPARROW_RAG_DB_NAME, charset="utf8mb4", autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def _invalidate_tree_cache():
    """RESP 协议手搓一条 DEL,避免为一个命令引入 redis 依赖。"""
    key = "sparrow:graph:tree"
    cmd = f"*2\r\n$3\r\nDEL\r\n${len(key)}\r\n{key}\r\n".encode()
    try:
        with socket.create_connection(
                (config.SPARROW_REDIS_HOST, config.SPARROW_REDIS_PORT), timeout=3) as s:
            s.sendall(cmd)
            resp = s.recv(64).decode()
        print(f"[sync] Redis 缓存已失效(DEL 返回 {resp.strip().lstrip(':')})")
    except OSError as e:
        print(f"[sync] Redis 不可达,跳过缓存失效(应用重启或 TTL 到期后自然生效): {e}")


def _trigger_graph_import():
    if not config.SPARROW_GRAPH_IMPORT_URL:
        print("[sync] SPARROW_GRAPH_IMPORT_URL 未配置,跳过 Neo4j 导入/RAG 事件触发")
        return

    req = urllib.request.Request(config.SPARROW_GRAPH_IMPORT_URL, method="POST")
    last_error = None
    for attempt in range(1, config.SPARROW_GRAPH_IMPORT_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=config.SPARROW_GRAPH_IMPORT_TIMEOUT_SECONDS) as resp:
                body = resp.read(512).decode("utf-8", errors="replace")
                print(f"[sync] graph import triggered: HTTP {resp.status} {body[:200]}")
                return
        except Exception as e:  # noqa: BLE001
            last_error = e
            print(f"[sync] graph import attempt {attempt} failed: {e}")
            if attempt < config.SPARROW_GRAPH_IMPORT_RETRIES:
                time.sleep(min(attempt * 2, 10))
    raise RuntimeError(f"graph import failed after retries: {last_error}")


def run():
    src = db.connect()
    graph = _graph_conn()
    rag = _rag_conn()
    try:
        extracted = db.all_extracted(src)
        relations = db.all_relations(src)

        with graph.cursor() as cur:
            # ── 1. 新节点 ──
            name_to_code = dict(SPARROW_NODES.items())
            new_nodes = 0
            for c in extracted:
                if c["sparrow_code"] or not c["era_rank"]:
                    continue
                code = f"sp_{c['id']}"
                name_to_code[canonical_name_of(c)] = code
                cur.execute(
                    "INSERT INTO tech_node (id, code, name, era, era_rank, year_label, summary, detail, premium) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 0) "
                    "ON DUPLICATE KEY UPDATE era=VALUES(era), era_rank=VALUES(era_rank), "
                    "year_label=VALUES(year_label), summary=VALUES(summary), detail=VALUES(detail)",
                    (config.SPARROW_ID_BASE + c["id"], code, c["term"], c["era"],
                     c["era_rank"], c["year_label"], c["summary"], c["detail"]),
                )
                new_nodes += 1

            # ── 2. 依赖边 ──
            edges = 0
            for r in relations:
                f_code, t_code = name_to_code.get(r["from_name"]), name_to_code.get(r["to_name"])
                if not f_code or not t_code or f_code == t_code:
                    continue
                cur.execute(
                    "INSERT IGNORE INTO tech_edge (from_id, to_id) "
                    "SELECT f.id, t.id FROM tech_node f, tech_node t WHERE f.code=%s AND t.code=%s",
                    (f_code, t_code),
                )
                edges += cur.rowcount

            # ── 3. 既有节点增补 ──
            enriched = 0
            for c in extracted:
                if not c["sparrow_code"] or not c["detail"]:
                    continue
                cur.execute(
                    "UPDATE tech_node SET detail = %s WHERE code = %s "
                    "AND CHAR_LENGTH(COALESCE(detail, '')) < CHAR_LENGTH(%s)",
                    (c["detail"], c["sparrow_code"], c["detail"]),
                )
                enriched += cur.rowcount

            # ── 4. RAG 语料表 ──
        with rag.cursor() as cur:
            cur.execute(RAG_TABLE_DDL)
            docs = 0
            for c in extracted:
                page = db.raw_page_of(src, c["id"])
                if not page or not page["extract_text"]:
                    continue
                cur.execute(
                    "INSERT INTO rag_document (code, name, title, url, content) "
                    "VALUES (%s, %s, %s, %s, %s) "
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), title=VALUES(title), "
                    "url=VALUES(url), content=VALUES(content)",
                    (c["sparrow_code"] or f"sp_{c['id']}", canonical_name_of(c),
                     page["title"], page["url"], page["extract_text"][:12000]),
                )
                docs += 1

        print(f"[sync] 新节点 {new_nodes}, 新边 {edges}, 既有节点增补 {enriched}, RAG 语料 {docs} 篇")
        _invalidate_tree_cache()
        _trigger_graph_import()
        print(f"[sync] graph={config.SPARROW_GRAPH_DB_NAME}@{config.SPARROW_GRAPH_DB_HOST}:{config.SPARROW_GRAPH_DB_PORT}, "
              f"rag={config.SPARROW_RAG_DB_NAME}@{config.SPARROW_RAG_DB_HOST}:{config.SPARROW_RAG_DB_PORT}")
    finally:
        src.close()
        graph.close()
        rag.close()

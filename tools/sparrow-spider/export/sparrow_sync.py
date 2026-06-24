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
from opencc import OpenCC

_cc = OpenCC("t2s")


def _s(text):
    """繁体→简体(维基部分词条/正文为繁体,统一转简,保证显示与搜索一致)。"""
    return _cc.convert(text) if text else text
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

        # 维基别名:sparrow 候选的维基标题 → sparrow_code(让指向"用火"之类别名的边能落到内置节点)
        title_to_sparrow_code = {_s(c["page_title"]): c["sparrow_code"]
                                 for c in db.candidates_with_page(src)
                                 if c["sparrow_code"] and c["page_title"]}
        sparrow_titles = set(title_to_sparrow_code)

        with graph.cursor() as cur:
            # ── 1. 新节点(带 category / importance,name 用规范标题)──
            new_nodes = skipped = 0
            for c in extracted:
                if c["sparrow_code"] or not c["era_rank"]:
                    continue
                if c["page_title"] and _s(c["page_title"]) in sparrow_titles:
                    continue  # 与内置节点同词条,跳过,避免重复概念
                name = _s(canonical_name_of(c))
                if not name or len(name) > 128:
                    skipped += 1
                    continue
                code = f"sp_{c['id']}"
                importance = c["importance"] if c["importance"] else 1
                summary = _s(c["summary"]) or name  # tech_node.summary NOT NULL,空则退回名称
                cur.execute(
                    "INSERT INTO tech_node "
                    "(id, code, name, era, era_rank, year_label, summary, detail, premium, category, importance) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 0, %s, %s) "
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), era=VALUES(era), era_rank=VALUES(era_rank), "
                    "year_label=VALUES(year_label), summary=VALUES(summary), detail=VALUES(detail), "
                    "category=COALESCE(tech_node.category, VALUES(category)), importance=VALUES(importance)",
                    (config.SPARROW_ID_BASE + c["id"], code, name, _s(c["era"]),
                     c["era_rank"], _s(c["year_label"]), summary, _s(c["detail"]),
                     _s(c["category"]), importance),
                )
                new_nodes += 1

            # ── 2. 既有节点增补(detail 更长才覆盖;category 为空才回填)──
            enriched = 0
            for c in extracted:
                if not c["sparrow_code"]:
                    continue
                if c["detail"]:
                    detail_s = _s(c["detail"])
                    cur.execute(
                        "UPDATE tech_node SET detail = %s WHERE code = %s "
                        "AND CHAR_LENGTH(COALESCE(detail, '')) < CHAR_LENGTH(%s)",
                        (detail_s, c["sparrow_code"], detail_s),
                    )
                    enriched += cur.rowcount
                if c["category"]:
                    cur.execute(
                        "UPDATE tech_node SET category = %s WHERE code = %s AND category IS NULL",
                        (_s(c["category"]), c["sparrow_code"]),
                    )

            # ── 3. 依赖边:名称消解 → id,按 era_rank 定向并去环 ──
            cur.execute("SELECT id, code, name, era_rank FROM tech_node")
            node_rows = cur.fetchall()
            code_to_id = {r["code"]: r["id"] for r in node_rows}
            name_to_id = {r["name"]: r["id"] for r in node_rows}
            rank_of = {r["id"]: r["era_rank"] for r in node_rows}
            for title, scode in title_to_sparrow_code.items():  # 别名标题 → 内置节点 id
                if scode in code_to_id:
                    name_to_id.setdefault(title, code_to_id[scode])

            edge_set = set()
            dropped = 0
            for r in relations:
                f_id = name_to_id.get(_s(r["from_name"]))
                t_id = name_to_id.get(_s(r["to_name"]))
                if not f_id or not t_id or f_id == t_id:
                    dropped += 1
                    continue
                fr, tr = rank_of.get(f_id), rank_of.get(t_id)
                if r["kind"] == "structural":
                    # 共现边:方向未知,按时代早→晚定向;同代跳过(去环)
                    if fr is None or tr is None or fr == tr:
                        dropped += 1
                        continue
                    src_id, dst_id = (f_id, t_id) if fr < tr else (t_id, f_id)
                else:
                    # LLM 前置边:信任方向,但前置不能晚于后继(违反则丢,保证 DAG)
                    if fr is not None and tr is not None and fr > tr:
                        dropped += 1
                        continue
                    src_id, dst_id = f_id, t_id
                edge_set.add((src_id, dst_id))

            edges = 0
            if edge_set:
                cur.executemany(
                    "INSERT IGNORE INTO tech_edge (from_id, to_id) VALUES (%s, %s)",
                    list(edge_set),
                )
                edges = cur.rowcount

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
                    (c["sparrow_code"] or f"sp_{c['id']}", _s(canonical_name_of(c)),
                     _s(page["title"]), page["url"], _s(page["extract_text"][:12000])),
                )
                docs += 1

        print(f"[sync] 新节点 {new_nodes}(跳过 {skipped}), 新边 {edges}(消解丢弃 {dropped}), "
              f"既有节点增补 {enriched}, RAG 语料 {docs} 篇")
        _invalidate_tree_cache()
        _trigger_graph_import()
        print(f"[sync] graph={config.SPARROW_GRAPH_DB_NAME}@{config.SPARROW_GRAPH_DB_HOST}:{config.SPARROW_GRAPH_DB_PORT}, "
              f"rag={config.SPARROW_RAG_DB_NAME}@{config.SPARROW_RAG_DB_HOST}:{config.SPARROW_RAG_DB_PORT}")
    finally:
        src.close()
        graph.close()
        rag.close()

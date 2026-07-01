# -*- coding: utf-8 -*-
"""MySQL 存取层:连接管理 + 自动建库建表 + 各阶段 CRUD。"""
import os
import re

import pymysql

import config

_SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "..", "schema", "init.sql")


def connect(with_db: bool = True):
    return pymysql.connect(
        host=config.DB_HOST,
        port=config.DB_PORT,
        user=config.DB_USER,
        password=config.DB_PASSWORD,
        database=config.DB_NAME if with_db else None,
        charset="utf8mb4",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


# 对既有 techspider 库补列(MySQL 无 ADD COLUMN IF NOT EXISTS,靠忽略 1060/1061 实现幂等)。
_MIGRATIONS = [
    "ALTER TABLE tech_candidate ADD COLUMN category VARCHAR(32) NULL",
    "ALTER TABLE tech_candidate ADD COLUMN importance INT NOT NULL DEFAULT 0",
    "ALTER TABLE tech_candidate ADD KEY idx_importance (importance)",
    "ALTER TABLE tech_relation ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'llm'",
    "ALTER TABLE tech_relation MODIFY COLUMN from_name VARCHAR(160) NOT NULL",
    "ALTER TABLE tech_relation MODIFY COLUMN to_name VARCHAR(160) NOT NULL",
]
_IGNORABLE_ALTER_ERRORS = (1060, 1061, 1091)  # 列已存在 / 键已存在 / 键不存在


def init_schema():
    """执行 schema/init.sql + 增量补列(均幂等)。"""
    with open(_SCHEMA_PATH, encoding="utf-8") as f:
        sql = f.read()
    conn = connect(with_db=False)
    try:
        with conn.cursor() as cur:
            for stmt in [s.strip() for s in re.split(r";\s*\n", sql) if s.strip()]:
                cur.execute(stmt)
        with conn.cursor() as cur:
            cur.execute(f"USE {config.DB_NAME}")
            for stmt in _MIGRATIONS:
                try:
                    cur.execute(stmt)
                except pymysql.err.OperationalError as e:
                    if e.args and e.args[0] in _IGNORABLE_ALTER_ERRORS:
                        continue
                    raise
    finally:
        conn.close()


# ───────────────────── 候选词条 ─────────────────────

def upsert_candidate(conn, term, source, sparrow_code=None, category=None):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tech_candidate (term, source, sparrow_code, category) VALUES (%s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE sparrow_code = COALESCE(VALUES(sparrow_code), sparrow_code), "
            "category = COALESCE(category, VALUES(category))",
            (term[:128], source, sparrow_code, category),
        )


def count_candidates(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) AS n FROM tech_candidate")
        return cur.fetchone()["n"]


def candidates_by_status(conn, status, limit=None, order="id"):
    """order: 'id'(默认稳定顺序)或 'importance'(入链中心度降序,分层抽取用)。"""
    order_sql = "importance DESC, id" if order == "importance" else "id"
    with conn.cursor() as cur:
        sql = f"SELECT * FROM tech_candidate WHERE status = %s ORDER BY {order_sql}"
        if limit:
            sql += f" LIMIT {int(limit)}"
        cur.execute(sql, (status,))
        return cur.fetchall()


def candidates_with_page(conn):
    """所有已解析出词条标题的候选(用于结构边的标题→规范名映射)。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, term, page_title, sparrow_code, era_rank, category, importance, status "
            "FROM tech_candidate WHERE page_title IS NOT NULL")
        return cur.fetchall()


def update_candidate_importance(conn, cid, importance):
    with conn.cursor() as cur:
        cur.execute("UPDATE tech_candidate SET importance = %s WHERE id = %s", (importance, cid))


def mark_candidate(conn, cid, status, **fields):
    sets = ["status = %s"]
    args = [status]
    for k, v in fields.items():
        sets.append(f"{k} = %s")
        args.append(v)
    args.append(cid)
    with conn.cursor() as cur:
        cur.execute(f"UPDATE tech_candidate SET {', '.join(sets)} WHERE id = %s", args)


def all_extracted(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT * FROM tech_candidate WHERE status = 'EXTRACTED' ORDER BY era_rank, id")
        return cur.fetchall()


def tier_a_cutoff(conn, top):
    """返回重要度第 top 名的 importance 值,作为深度抽取(Tier-A)的阈值;不足 top 个则 0。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT importance FROM tech_candidate WHERE status IN ('CRAWLED', 'EXTRACTED') "
            "ORDER BY importance DESC, id LIMIT 1 OFFSET %s", (max(top - 1, 0),))
        row = cur.fetchone()
        return row["importance"] if row else 0


# ───────────────────── 原始页面 ─────────────────────

def save_raw_page(conn, candidate_id, page_id, title, url, extract_text, links_json):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO raw_page (candidate_id, page_id, title, url, extract_text, links_json) "
            "VALUES (%s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE page_id=VALUES(page_id), title=VALUES(title), "
            "url=VALUES(url), extract_text=VALUES(extract_text), links_json=VALUES(links_json)",
            (candidate_id, page_id, title, url, extract_text, links_json),
        )


def raw_page_of(conn, candidate_id):
    with conn.cursor() as cur:
        cur.execute("SELECT * FROM raw_page WHERE candidate_id = %s", (candidate_id,))
        return cur.fetchone()


def all_raw_pages(conn):
    """所有原始页面的标题与内链(批量构建结构边 / 重要度用)。"""
    with conn.cursor() as cur:
        cur.execute("SELECT candidate_id, title, links_json FROM raw_page")
        return cur.fetchall()


# ───────────────────── 依赖关系 ─────────────────────

def add_relation(conn, from_name, to_name, kind="llm", confidence="llm"):
    if not from_name or not to_name or from_name == to_name:
        return
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tech_relation (from_name, to_name, kind, confidence) VALUES (%s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE kind = VALUES(kind)",
            (from_name[:160], to_name[:160], kind, confidence),
        )


def add_relations_bulk(conn, rows):
    """批量写入关系(rows = [(from, to, kind, confidence)]),幂等。结构边量大时用。"""
    rows = [(f[:160], t[:160], k, conf) for (f, t, k, conf) in rows if f and t and f != t]
    if not rows:
        return 0
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO tech_relation (from_name, to_name, kind, confidence) VALUES (%s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE kind = VALUES(kind)",
            rows,
        )
    return len(rows)


def all_relations(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT * FROM tech_relation ORDER BY id")
        return cur.fetchall()


# ───────────────────── 产业链专题（与科技图表隔离） ─────────────────────

def upsert_supply_chain_company(conn, chain_slug, name, wiki_title, page_id, summary):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO supply_chain_company "
            "(chain_slug, name, wiki_title, page_id, summary) VALUES (%s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE wiki_title=VALUES(wiki_title), page_id=VALUES(page_id), "
            "summary=VALUES(summary)",
            (chain_slug[:64], name[:160], (wiki_title or "")[:200] or None, page_id, summary),
        )


def replace_supply_chain_relations(conn, chain_slug, company_name, relations):
    """以一次成功抽取的完整结果替换该公司旧关系；解析失败时调用方不会触发本函数。"""
    conn.begin()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM supply_chain_relation WHERE chain_slug=%s AND company_name=%s",
                (chain_slug, company_name),
            )
            rows = [(
                chain_slug[:64], company_name[:160], r["counterparty"][:160],
                r["node_type"][:32], r["edge_type"][:32], r.get("product"),
                r.get("confidence", "low")[:16],
            ) for r in relations]
            if rows:
                cur.executemany(
                    "INSERT INTO supply_chain_relation "
                    "(chain_slug, company_name, counterparty_name, counterparty_type, edge_type, product, confidence) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s) "
                    "ON DUPLICATE KEY UPDATE counterparty_type=VALUES(counterparty_type), "
                    "edge_type=VALUES(edge_type), product=VALUES(product), confidence=VALUES(confidence)",
                    rows,
                )
        conn.commit()
        return len(rows)
    except Exception:
        conn.rollback()
        raise


def supply_chain_companies(conn, chain_slug=None):
    with conn.cursor() as cur:
        if chain_slug:
            cur.execute("SELECT * FROM supply_chain_company WHERE chain_slug=%s ORDER BY id", (chain_slug,))
        else:
            cur.execute("SELECT * FROM supply_chain_company ORDER BY chain_slug, id")
        return cur.fetchall()


def supply_chain_relations(conn, chain_slug=None):
    with conn.cursor() as cur:
        if chain_slug:
            cur.execute("SELECT * FROM supply_chain_relation WHERE chain_slug=%s ORDER BY id", (chain_slug,))
        else:
            cur.execute("SELECT * FROM supply_chain_relation ORDER BY chain_slug, id")
        return cur.fetchall()

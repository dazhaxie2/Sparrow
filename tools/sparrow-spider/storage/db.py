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


def init_schema():
    """执行 schema/init.sql(幂等)。"""
    with open(_SCHEMA_PATH, encoding="utf-8") as f:
        sql = f.read()
    conn = connect(with_db=False)
    try:
        with conn.cursor() as cur:
            for stmt in [s.strip() for s in re.split(r";\s*\n", sql) if s.strip()]:
                cur.execute(stmt)
    finally:
        conn.close()


# ───────────────────── 候选词条 ─────────────────────

def upsert_candidate(conn, term, source, sparrow_code=None):
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tech_candidate (term, source, sparrow_code) VALUES (%s, %s, %s) "
            "ON DUPLICATE KEY UPDATE sparrow_code = COALESCE(VALUES(sparrow_code), sparrow_code)",
            (term, source, sparrow_code),
        )


def candidates_by_status(conn, status, limit=None):
    with conn.cursor() as cur:
        sql = "SELECT * FROM tech_candidate WHERE status = %s ORDER BY id"
        if limit:
            sql += f" LIMIT {int(limit)}"
        cur.execute(sql, (status,))
        return cur.fetchall()


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


# ───────────────────── 依赖关系 ─────────────────────

def add_relation(conn, from_name, to_name, confidence="llm"):
    if from_name == to_name:
        return
    with conn.cursor() as cur:
        cur.execute(
            "INSERT IGNORE INTO tech_relation (from_name, to_name, confidence) VALUES (%s, %s, %s)",
            (from_name, to_name, confidence),
        )


def all_relations(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT * FROM tech_relation ORDER BY id")
        return cur.fetchall()

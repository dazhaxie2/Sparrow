# -*- coding: utf-8 -*-
"""导出器:把抽取产物变成 Sparrow 可直接消费的三个文件。

out/sparrow_new_nodes.sql   新技术节点 + 依赖边(幂等,可重复执行)
out/enrich_existing.sql     既有 77 节点的 detail 增补(仅当新内容更充实时生效)
out/rag_corpus.json         全部词条语料(Sparrow 的 Milvus embedding 流水线直接吃)
"""
import json
import os

import config
from knowledge_extraction.extractor import canonical_name_of
from storage import db
from topic_discovery.seeds import SPARROW_NODES


def _esc(s):
    return (s or "").replace("\\", "\\\\").replace("'", "''")


def run():
    os.makedirs(config.OUT_DIR, exist_ok=True)
    conn = db.connect()
    try:
        extracted = db.all_extracted(conn)
        relations = db.all_relations(conn)

        name_to_code = dict(SPARROW_NODES.items())
        new_nodes = []
        for c in extracted:
            if c["sparrow_code"]:
                continue
            if not c["era_rank"]:
                continue  # 规则降级抽不出时代,不进图谱,只进 RAG 语料
            code = f"sp_{c['id']}"
            name_to_code[canonical_name_of(c)] = code
            new_nodes.append((config.SPARROW_ID_BASE + c["id"], code, c))

        # ── 1. 新节点 + 边 ──
        lines = ["-- SparrowSpider 导出:新技术节点与依赖边(幂等)", "SET NAMES utf8mb4;", ""]
        for node_id, code, c in new_nodes:
            lines.append(
                "INSERT INTO tech_node (id, code, name, era, era_rank, year_label, summary, detail, premium)\n"
                f"VALUES ({node_id}, '{code}', '{_esc(c['term'])}', '{_esc(c['era'])}', {c['era_rank']}, "
                f"'{_esc(c['year_label'])}', '{_esc(c['summary'])}', '{_esc(c['detail'])}', 0)\n"
                "ON DUPLICATE KEY UPDATE era=VALUES(era), era_rank=VALUES(era_rank), "
                "year_label=VALUES(year_label), summary=VALUES(summary), detail=VALUES(detail);"
            )
        lines.append("")
        skipped = 0
        for r in relations:
            f_code = name_to_code.get(r["from_name"])
            t_code = name_to_code.get(r["to_name"])
            if not f_code or not t_code or f_code == t_code:
                skipped += 1
                continue
            lines.append(
                "INSERT IGNORE INTO tech_edge (from_id, to_id) "
                f"SELECT f.id, t.id FROM tech_node f, tech_node t "
                f"WHERE f.code='{f_code}' AND t.code='{t_code}';"
            )
        sql_path = os.path.join(config.OUT_DIR, "sparrow_new_nodes.sql")
        with open(sql_path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

        # ── 2. 既有节点 detail 增补(只在更充实时覆盖) ──
        enrich_lines = ["-- 既有节点内容增补:仅当抓取内容比现有 detail 更长时生效", "SET NAMES utf8mb4;", ""]
        enriched = 0
        for c in extracted:
            if not c["sparrow_code"] or not c["detail"]:
                continue
            enrich_lines.append(
                f"UPDATE tech_node SET detail = '{_esc(c['detail'])}' "
                f"WHERE code = '{c['sparrow_code']}' "
                f"AND CHAR_LENGTH(COALESCE(detail, '')) < CHAR_LENGTH('{_esc(c['detail'])}');"
            )
            enriched += 1
        enrich_path = os.path.join(config.OUT_DIR, "enrich_existing.sql")
        with open(enrich_path, "w", encoding="utf-8") as f:
            f.write("\n".join(enrich_lines) + "\n")

        # ── 3. RAG 语料 ──
        corpus = []
        for c in extracted:
            page = db.raw_page_of(conn, c["id"])
            if not page or not page["extract_text"]:
                continue
            code = c["sparrow_code"] or f"sp_{c['id']}"
            corpus.append({
                "code": code,
                "name": canonical_name_of(c),
                "title": page["title"],
                "url": page["url"],
                "text": page["extract_text"][:4000],
            })
        corpus_path = os.path.join(config.OUT_DIR, "rag_corpus.json")
        with open(corpus_path, "w", encoding="utf-8") as f:
            json.dump(corpus, f, ensure_ascii=False, indent=2)

        print(f"[export] 新节点 {len(new_nodes)} 个, 边 {len(relations) - skipped} 条(跳过未对齐 {skipped})")
        print(f"[export] 既有节点增补 {enriched} 条, RAG 语料 {len(corpus)} 篇")
        print(f"[export] 产物目录: {config.OUT_DIR}")
        print("[export] 导入 Sparrow: docker exec -i sparrow-mysql-1 mysql -usparrow -psparrow123 sparrow"
              " < out/sparrow_new_nodes.sql(然后清掉 Redis 缓存 key sparrow:graph:tree)")
    finally:
        conn.close()

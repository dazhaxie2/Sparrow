# -*- coding: utf-8 -*-
"""阶段一:候选词条发现(类比 MindSpider 的 BroadTopicExtraction)。

两个来源:
1. load_seeds —— 内置种子(Sparrow 77 节点 + 扩展词表),离线写入候选表
2. expand_from_links —— 已爬词条的站内链接,经 LLM 筛选出"技术类"词条(需配置 AI_API_KEY)
"""
import json

import config
from knowledge_extraction import extractor
from storage import db
from topic_discovery.seeds import EXTENSION_TERMS, SEARCH_ALIAS, SPARROW_NODES


def load_seeds():
    conn = db.connect()
    try:
        for name, code in SPARROW_NODES.items():
            term = SEARCH_ALIAS.get(name, name)
            db.upsert_candidate(conn, term, "seed", sparrow_code=code)
        for term in EXTENSION_TERMS:
            db.upsert_candidate(conn, term, "seed")
    finally:
        conn.close()
    print(f"[discover] 种子已载入: Sparrow 对齐 {len(SPARROW_NODES)} + 扩展 {len(EXTENSION_TERMS)}")


def expand_from_links(limit: int = 30):
    """从已爬页面的内链里发现新候选(LLM 充当'是不是技术'的过滤器)。"""
    if not config.llm_configured():
        print("[discover] 未配置 AI_API_KEY,跳过链接扩展(种子词表已足够跑通全流程)")
        return
    conn = db.connect()
    try:
        known = set()
        with conn.cursor() as cur:
            cur.execute("SELECT term FROM tech_candidate")
            known = {r["term"] for r in cur.fetchall()}
            cur.execute("SELECT links_json FROM raw_page WHERE links_json IS NOT NULL")
            rows = cur.fetchall()
        link_pool = []
        for r in rows:
            for title in json.loads(r["links_json"]):
                if title not in known and title not in link_pool and len(title) <= 24:
                    link_pool.append(title)
        if not link_pool:
            print("[discover] 暂无可扩展链接(先执行 --crawl)")
            return
        picked = extractor.filter_tech_terms(link_pool[:300], limit=limit)
        for term in picked:
            db.upsert_candidate(conn, term, "link")
        print(f"[discover] 链接扩展完成: 入池 {len(picked)} 条 → {picked[:10]}{' …' if len(picked) > 10 else ''}")
    finally:
        conn.close()

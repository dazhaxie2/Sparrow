# -*- coding: utf-8 -*-
"""阶段一:候选词条发现(类比 MindSpider 的 BroadTopicExtraction)。

两个来源:
1. load_seeds —— 内置种子(Sparrow 77 节点 + 扩展词表),离线写入候选表
2. expand_from_links —— 已爬词条的站内链接,经 LLM 筛选出"技术类"词条(需配置 AI_API_KEY)
"""
import asyncio
import json
from collections import deque

import config
from deep_crawling.wiki_client import WikiClient
from knowledge_extraction import extractor
from storage import db
from topic_discovery.seeds import EXTENSION_TERMS, SEARCH_ALIAS, SEED_CATEGORIES, SPARROW_NODES


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


async def _discover_categories(max_depth: int):
    """分类目录子类目 BFS:把页面成员灌进候选,并打上领域标签。到万级的主引擎。"""
    conn = db.connect()
    client = WikiClient()
    before = db.count_candidates(conn)
    seen_cats = set()
    pages_seen = set()
    frontier = deque()
    for domain, cats in SEED_CATEGORIES.items():
        dom = None if domain == "_top" else domain
        for cat in cats:
            if cat not in seen_cats:
                seen_cats.add(cat)
                frontier.append((cat, dom, 0))
    try:
        while frontier and before + len(pages_seen) < config.MAX_NODES:
            cat, dom, depth = frontier.popleft()
            try:
                members = await client.category_members(cat, cmtype="page|subcat")
            except ConnectionError as e:
                print(f"[discover] 网络不可达,本轮终止: {e}")
                print("        提示: 大陆网络可设 SPIDER_PROXY,或 SPIDER_WIKI_API 指向可达镜像")
                break
            except Exception as e:  # noqa: BLE001 - 单个类目失败跳过
                print(f"[discover] 跳过类目 {cat}: {e}")
                continue
            new_pages = 0
            for m in members:
                title = m["title"]
                if m["type"] == "subcat":
                    if depth < max_depth:
                        sub = title.split(":", 1)[-1]
                        if sub not in seen_cats:
                            seen_cats.add(sub)
                            frontier.append((sub, dom, depth + 1))
                    continue
                # 页面成员:过滤掉命名空间页(标题含冒号),只要正文条目
                if ":" in title or title in pages_seen:
                    continue
                if before + len(pages_seen) >= config.MAX_NODES:
                    break
                db.upsert_candidate(conn, title, "category", category=dom)
                pages_seen.add(title)
                new_pages += 1
            tag = dom or "顶层"
            print(f"[discover] [{tag}] {cat}: +{new_pages} 页 (累计候选 ~{before + len(pages_seen)}, "
                  f"待展开类目 {len(frontier)})")
    finally:
        await client.close()
        conn.close()
    print(f"[discover] 分类发现完成: 本轮新增约 {len(pages_seen)} 个候选,候选总量约 "
          f"{before + len(pages_seen)} (上限 {config.MAX_NODES})")


def discover_from_categories(max_depth: int = None):
    asyncio.run(_discover_categories(config.DISCOVER_MAX_DEPTH if max_depth is None else max_depth))


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

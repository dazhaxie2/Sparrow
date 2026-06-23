# -*- coding: utf-8 -*-
"""产业链抓取流水线：维基正文 -> LLM 关系 -> techspider 专用表。"""
import asyncio

import config
from deep_crawling.wiki_client import WikiClient
from knowledge_extraction.chain_extractor import extract_chain_relations
from storage import db
from topic_discovery.chain_seeds import CHAINS


def _summary(text: str) -> str | None:
    paragraphs = [line.strip() for line in (text or "").splitlines() if len(line.strip()) >= 30]
    return paragraphs[0][:1000] if paragraphs else None


async def _run_async(company_names=None):
    if not config.llm_configured():
        raise RuntimeError("产业链抽取需要配置 AI_BASE_URL 和 AI_API_KEY")

    requested = {name.strip() for name in (company_names or []) if name.strip()}
    available = {name for chain in CHAINS.values() for name in chain["seeds"]}
    unknown = requested - available
    if unknown:
        raise ValueError(f"未知产业链种子公司：{', '.join(sorted(unknown))}")

    conn = db.connect()
    wiki = WikiClient()
    company_count = relation_count = failed = 0
    try:
        for slug, chain in CHAINS.items():
            seeds = [name for name in chain["seeds"] if not requested or name in requested]
            if not seeds:
                continue
            print(f"[chains] {chain['name']}：{len(seeds)} 个种子公司")
            for company_name in seeds:
                try:
                    resolved = await wiki.resolve_title(company_name)
                    if not resolved:
                        raise LookupError("未找到维基词条")
                    page_id, wiki_title = resolved
                    page = await wiki.fetch_page(page_id)
                    if not page["text"].strip():
                        raise ValueError("维基词条正文为空")
                    db.upsert_supply_chain_company(
                        conn, slug, company_name, wiki_title, page_id, _summary(page["text"]))
                    relations = extract_chain_relations(company_name, page["text"])
                    relation_count += db.replace_supply_chain_relations(
                        conn, slug, company_name, relations)
                    company_count += 1
                    print(f"  ✓ {company_name} -> {wiki_title}，关系 {len(relations)} 条")
                except Exception as error:  # 单个公司失败不阻断其余三条链
                    failed += 1
                    print(f"  ✗ {company_name}：{error}")
    finally:
        await wiki.close()
        conn.close()
    print(f"[chains] 抓取完成：公司 {company_count}，关系 {relation_count}，失败 {failed}")
    return {"companies": company_count, "relations": relation_count, "failed": failed}


def run(company_names=None):
    return asyncio.run(_run_async(company_names))

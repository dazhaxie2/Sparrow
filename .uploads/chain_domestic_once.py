# -*- coding: utf-8 -*-
"""One-shot domestic-source fallback for the production supply-chain pipeline.

This file is mounted into the existing sparrow-spider image and is not copied
into the production worktree.  Baidu Baike's public card API provides company
summaries; Bing China RSS snippets provide explicit upstream-supply evidence
for each chain's core company.  Existing extraction and sync code remains the
single writer for normalized relations and business tables.
"""
from __future__ import annotations

import hashlib
import html
import re
import time
import xml.etree.ElementTree as ET

import httpx

import config
from export import chain_sync
from knowledge_extraction.chain_extractor import extract_chain_relations
from storage import db
from topic_discovery.chain_seeds import CHAINS


BAIDU_API = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi"
BING_SEARCH = "https://cn.bing.com/search"
TAG_RE = re.compile(r"<[^>]+>")
SPACE_RE = re.compile(r"\s+")


def clean(value) -> str:
    text = html.unescape(str(value or ""))
    text = TAG_RE.sub(" ", text)
    return SPACE_RE.sub(" ", text).strip()


def stable_page_id(name: str) -> int:
    return int.from_bytes(hashlib.sha256(name.encode("utf-8")).digest()[:6], "big")


def baidu_company(client: httpx.Client, name: str) -> tuple[str, int, str]:
    response = client.get(
        BAIDU_API,
        params={
            "scope": 103,
            "format": "json",
            "appid": 379020,
            "bk_key": name,
            "bk_length": 600,
        },
    )
    response.raise_for_status()
    data = response.json()
    title = clean(data.get("title") or data.get("key") or name)
    page_id = int(data.get("newLemmaId") or data.get("id") or stable_page_id(name))
    parts = [
        f"来源：百度百科开放词条卡片；词条：{title}",
        clean(data.get("desc")),
        clean(data.get("abstract")),
    ]
    for card in data.get("card") or []:
        label = clean(card.get("name"))
        values = "；".join(filter(None, (clean(item) for item in card.get("value") or [])))
        if label and values:
            parts.append(f"{label}：{values}")
    return title, page_id, "\n".join(filter(None, parts))


def bing_evidence(client: httpx.Client, company: str) -> str:
    queries = [
        f'"{company}" 供应商 代工 供货 材料 授权',
        f'"{company}" 供应链 上游',
    ]
    evidence = []
    seen = set()
    for query in queries:
        response = client.get(
            BING_SEARCH,
            params={"format": "rss", "q": query, "setlang": "zh-cn", "cc": "cn"},
        )
        response.raise_for_status()
        root = ET.fromstring(response.text)
        for item in root.findall(".//item")[:10]:
            title = clean(item.findtext("title"))
            description = clean(item.findtext("description"))
            link = clean(item.findtext("link"))
            signature = (title, description)
            if not description or signature in seen:
                continue
            seen.add(signature)
            evidence.append(f"检索结果：{title}\n摘要：{description}\n链接：{link}")
        time.sleep(max(config.REQUEST_INTERVAL_SECONDS, 1.0))
    return "\n\n".join(evidence)


def run() -> None:
    if not config.llm_configured():
        raise RuntimeError("生产环境未配置 AI_BASE_URL / AI_API_KEY")

    db.init_schema()
    source = db.connect()
    headers = {"User-Agent": config.USER_AGENT, "Accept-Language": "zh-CN,zh;q=0.9"}
    companies = relations = failures = 0
    try:
        with httpx.Client(headers=headers, timeout=25, follow_redirects=True) as client:
            for slug, chain in CHAINS.items():
                primary = chain["seeds"][0]
                print(f"[domestic] {slug}: {len(chain['seeds'])} companies; core={primary}")
                primary_text = ""
                for name in chain["seeds"]:
                    try:
                        title, page_id, text = baidu_company(client, name)
                        summary = next((line for line in text.splitlines()[1:] if len(line) >= 20), text)
                        db.upsert_supply_chain_company(
                            source,
                            slug,
                            name,
                            f"百度百科：{title}",
                            page_id,
                            summary[:1000],
                        )
                        companies += 1
                        if name == primary:
                            primary_text = text
                        print(f"  + {name} <- 百度百科/{title}")
                    except Exception as error:  # noqa: BLE001
                        failures += 1
                        fallback = f"来源：国内公开检索；公司：{name}"
                        db.upsert_supply_chain_company(
                            source,
                            slug,
                            name,
                            "国内公开检索",
                            stable_page_id(f"{slug}:{name}"),
                            fallback,
                        )
                        print(f"  ! {name}: 百度摘要失败，保留种子节点：{error}")
                    time.sleep(max(config.REQUEST_INTERVAL_SECONDS, 1.0))

                try:
                    search_text = bing_evidence(client, primary)
                    combined = (primary_text + "\n\n" + search_text).strip()
                    extracted = extract_chain_relations(primary, combined)
                    relations += db.replace_supply_chain_relations(source, slug, primary, extracted)
                    print(f"  = {primary}: 抽取 {len(extracted)} 条上游关系")
                except Exception as error:  # noqa: BLE001
                    failures += 1
                    print(f"  ! {primary}: 关系抽取失败，未覆盖旧关系：{error}")
    finally:
        source.close()

    result = chain_sync.run()
    print(
        f"[domestic] done: companies={companies}, extracted_relations={relations}, "
        f"failures={failures}, synced_nodes={result['nodes']}, synced_edges={result['edges']}"
    )
    if companies == 0 or result["nodes"] == 0:
        raise RuntimeError("国内源回退未产出任何供应链节点")


if __name__ == "__main__":
    run()

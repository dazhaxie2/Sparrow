# -*- coding: utf-8 -*-
"""阶段三:知识抽取(类比 MindSpider 的情感分析,这里抽"技术依赖关系")。

- LLM 模式:词条全文 → {era, era_rank, year_label, summary, detail, prerequisites[]}
  prerequisites 限定从"已知技术名清单"中选,保证边的端点可对齐。
- 规则降级(未配置 AI_API_KEY):首段做 summary、前两段做 detail,不抽边、不定时代。
  降级产物仍可用于 RAG 语料与既有节点的内容增补。
"""
import json
import re

import httpx

import config
from storage import db
from topic_discovery.seeds import EXTENSION_TERMS, SPARROW_NODES

ERAS = [
    ("石器时代", 1), ("农业时代", 2), ("青铜与铁器", 3), ("古典时代", 4), ("中世纪", 5),
    ("文艺复兴", 6), ("工业革命", 7), ("电气时代", 8), ("信息时代", 9), ("智能时代", 10),
]
_ERA_RANK = dict(ERAS)


def _known_names():
    return list(SPARROW_NODES.keys()) + EXTENSION_TERMS


def _chat(messages, temperature=0.2) -> str:
    resp = httpx.post(
        config.AI_BASE_URL.rstrip("/") + "/chat/completions",
        headers={"Authorization": f"Bearer {config.AI_API_KEY}"},
        json={"model": config.AI_CHAT_MODEL, "messages": messages, "temperature": temperature},
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def _parse_json(text: str):
    m = re.search(r"\{.*\}|\[.*\]", text, re.S)
    return json.loads(m.group(0)) if m else None


def extract_one(canonical_name: str, page_text: str):
    """LLM 抽取单个词条。返回 dict 或 None(解析失败)。"""
    era_list = "、".join(name for name, _ in ERAS)
    known = "、".join(_known_names())
    prompt = (
        f"你是科技史知识工程师。基于下面的百科词条全文,为技术「{canonical_name}」输出 JSON:\n"
        "{\n"
        f'  "era": "十选一: {era_list}",\n'
        '  "year_label": "如 公元1947年 / 约公元前3500年",\n'
        '  "summary": "一句话概括,不超过60字",\n'
        '  "detail": "深度解读,120~200字,讲清它解决了什么问题、为什么重要",\n'
        '  "prerequisites": ["它的直接前置技术,0~4个"]\n'
        "}\n"
        f"约束: prerequisites 只能从这份清单中选(没有合适的就给空数组): {known}\n"
        "只输出 JSON,不要其他文字。\n\n"
        f"### 词条全文(截断)\n{page_text[:6000]}"
    )
    data = _parse_json(_chat([{"role": "user", "content": prompt}]))
    if not data or data.get("era") not in _ERA_RANK:
        return None
    data["era_rank"] = _ERA_RANK[data["era"]]
    data["prerequisites"] = [p for p in data.get("prerequisites", []) if p in set(_known_names())]
    return data


def filter_tech_terms(titles, limit=30):
    """LLM 从链接标题里筛出"人类科技史上的技术/发明"词条。"""
    prompt = (
        "下面是一批百科词条标题。请挑出其中属于「人类科技史上的具体技术、发明或工程方法」的标题"
        f"(排除人物、地名、组织、概念泛称),最多 {limit} 个,输出 JSON 数组,只输出 JSON。\n\n"
        + json.dumps(titles, ensure_ascii=False)
    )
    data = _parse_json(_chat([{"role": "user", "content": prompt}]))
    return [t for t in (data or []) if isinstance(t, str) and t in set(titles)][:limit]


def _rule_fallback(page_text: str):
    paras = [p.strip() for p in page_text.split("\n") if len(p.strip()) >= 40]
    if not paras:
        return None
    return {
        "era": None, "era_rank": None, "year_label": None,
        "summary": paras[0][:200],
        "detail": "\n".join(paras[:2])[:600],
        "prerequisites": [],
    }


def canonical_name_of(candidate):
    """边/导出统一使用的规范名:Sparrow 对齐节点用原节点名,新词条用检索词。"""
    if candidate["sparrow_code"]:
        for name, code in SPARROW_NODES.items():
            if code == candidate["sparrow_code"]:
                return name
    return candidate["term"]


def run(limit: int = 50):
    use_llm = config.llm_configured()
    mode = "LLM" if use_llm else "规则降级(未配置 AI_API_KEY)"
    conn = db.connect()
    ok = failed = 0
    try:
        crawled = db.candidates_by_status(conn, "CRAWLED", limit=limit)
        if not crawled:
            print("[extract] 没有已爬待抽取的候选(先执行 --crawl)")
            return
        print(f"[extract] 待抽取 {len(crawled)} 条,模式: {mode}")
        for c in crawled:
            conn.ping(reconnect=True)  # LLM 调用期间连接可能因闲置被掐断
            page = db.raw_page_of(conn, c["id"])
            if not page or not page["extract_text"]:
                db.mark_candidate(conn, c["id"], "FAILED")
                failed += 1
                continue
            name = canonical_name_of(c)
            try:
                data = extract_one(name, page["extract_text"]) if use_llm \
                    else _rule_fallback(page["extract_text"])
            except Exception as e:  # noqa: BLE001 - LLM 单条失败降级处理
                print(f"  ✗ {name}: LLM 失败({e}),改用规则降级")
                data = _rule_fallback(page["extract_text"])
            if not data:
                db.mark_candidate(conn, c["id"], "FAILED")
                failed += 1
                print(f"  ✗ {name}: 无法抽取")
                continue
            db.mark_candidate(conn, c["id"], "EXTRACTED",
                              era=data["era"], era_rank=data["era_rank"],
                              year_label=data["year_label"],
                              summary=data["summary"], detail=data["detail"])
            for pre in data["prerequisites"]:
                db.add_relation(conn, pre, name)
            ok += 1
            edge_info = f",前置 {len(data['prerequisites'])} 条" if data["prerequisites"] else ""
            print(f"  ✓ {name}: {data['era'] or '未分级'}{edge_info}")
    finally:
        conn.close()
    print(f"[extract] 完成: 成功 {ok}, 失败 {failed}")

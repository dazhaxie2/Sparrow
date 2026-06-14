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
from topic_discovery.seeds import DOMAINS, EXTENSION_TERMS, SPARROW_NODES

ERAS = [
    ("石器时代", 1), ("农业时代", 2), ("青铜与铁器", 3), ("古典时代", 4), ("中世纪", 5),
    ("文艺复兴", 6), ("工业革命", 7), ("电气时代", 8), ("信息时代", 9), ("智能时代", 10),
]
_ERA_RANK = dict(ERAS)
_DOMAIN_SET = set(DOMAINS)

# ── token 预算计量(全模块累计;触顶后 LLM 调用降级为规则路径)──
_token_used = 0


def tokens_used() -> int:
    return _token_used


def budget_exceeded() -> bool:
    return config.TOKEN_BUDGET > 0 and _token_used >= config.TOKEN_BUDGET


def _known_names():
    return list(SPARROW_NODES.keys()) + EXTENSION_TERMS


def _chat(messages, temperature=0.2) -> str:
    global _token_used
    resp = httpx.post(
        config.AI_BASE_URL.rstrip("/") + "/chat/completions",
        headers={"Authorization": f"Bearer {config.AI_API_KEY}"},
        json={"model": config.AI_CHAT_MODEL, "messages": messages, "temperature": temperature},
        timeout=120,
    )
    resp.raise_for_status()
    body = resp.json()
    usage = body.get("usage") or {}
    _token_used += int(usage.get("total_tokens", 0))
    return body["choices"][0]["message"]["content"]


def _parse_json(text: str):
    m = re.search(r"\{.*\}|\[.*\]", text, re.S)
    return json.loads(m.group(0)) if m else None


def extract_one(canonical_name: str, page_text: str):
    """LLM 深度抽取单个词条(Tier-A)。返回 dict 或 None(解析失败)。

    维基级改造:prerequisites 不再限定于已知清单,改为自由技术名(端点消解推迟到 sync),
    这样新节点之间才能互相连边、长成真正的树;并新增 category(14 领域之一)。
    """
    era_list = "、".join(name for name, _ in ERAS)
    domain_list = "、".join(DOMAINS)
    prompt = (
        f"你是科技史知识工程师。基于下面的百科词条全文,为技术「{canonical_name}」输出 JSON:\n"
        "{\n"
        f'  "era": "十选一: {era_list}",\n'
        f'  "category": "十四选一: {domain_list}",\n'
        '  "year_label": "如 公元1947年 / 约公元前3500年",\n'
        '  "summary": "一句话概括,不超过60字",\n'
        '  "detail": "深度解读,120~200字,讲清它解决了什么问题、为什么重要",\n'
        '  "prerequisites": ["它的直接前置技术名,0~5个,用通行的技术/发明名,可自由填写"]\n'
        "}\n"
        "era 必须十选一;category 必须十四选一;若无法判断 era 则不要编造,留空字符串。\n"
        "只输出 JSON,不要其他文字。\n\n"
        f"### 词条全文(截断)\n{page_text[:5000]}"
    )
    data = _parse_json(_chat([{"role": "user", "content": prompt}]))
    if not data or data.get("era") not in _ERA_RANK:
        return None
    data["era_rank"] = _ERA_RANK[data["era"]]
    cat = data.get("category")
    data["category"] = cat if cat in _DOMAIN_SET else None
    prereqs = data.get("prerequisites") or []
    data["prerequisites"] = [str(p).strip() for p in prereqs
                             if isinstance(p, str) and p.strip() and p.strip() != canonical_name][:5]
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


def classify_batch(names):
    """Tier-B 轻量分类:一次给一批词条名,判定 era + category(不抽摘要/边,省 token)。

    返回 {name: {"era", "era_rank", "category"}};无法判定的 name 不出现在结果里。
    """
    era_list = "、".join(name for name, _ in ERAS)
    domain_list = "、".join(DOMAINS)
    prompt = (
        "你是科技史分类器。为下面每个技术词条判定它所属的「时代」和「领域」。\n"
        f"时代十选一: {era_list}\n"
        f"领域十四选一: {domain_list}\n"
        '输出 JSON 对象,键是词条名,值是 {"era":"…","category":"…"};无法判断的词条直接省略。'
        "只输出 JSON。\n\n"
        + json.dumps(list(names), ensure_ascii=False)
    )
    data = _parse_json(_chat([{"role": "user", "content": prompt}]))
    result = {}
    if isinstance(data, dict):
        for name, v in data.items():
            if not isinstance(v, dict):
                continue
            era = v.get("era")
            if era not in _ERA_RANK:
                continue
            cat = v.get("category")
            result[name] = {
                "era": era,
                "era_rank": _ERA_RANK[era],
                "category": cat if cat in _DOMAIN_SET else None,
            }
    return result


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
    """边/导出统一使用的规范名:Sparrow 对齐节点用原节点名,新词条优先用维基正式标题,
    否则退回检索词。规范名同时作为节点 name 与边端点消解的 key,三处必须一致。"""
    if candidate.get("sparrow_code"):
        for name, code in SPARROW_NODES.items():
            if code == candidate["sparrow_code"]:
                return name
    return candidate.get("page_title") or candidate["term"]


def _extract_tier_a(conn, candidates):
    """深度抽取核心节点:era/category/year/summary/detail + 自由前置边(kind=llm)。"""
    ok = budget_stopped = 0
    for c in candidates:
        if budget_exceeded():
            budget_stopped = 1
            print(f"[extract] token 预算触顶({tokens_used()}/{config.TOKEN_BUDGET}),"
                  f"Tier-A 停止,剩余留待补充预算后续跑")
            break
        conn.ping(reconnect=True)  # LLM 调用期间闲置连接可能被掐断
        page = db.raw_page_of(conn, c["id"])
        if not page or not page["extract_text"]:
            db.mark_candidate(conn, c["id"], "FAILED")
            continue
        name = canonical_name_of(c)
        try:
            data = extract_one(name, page["extract_text"])
        except Exception as e:  # noqa: BLE001 - 单条失败用规则兜底(无 era 则进 RAG 不进图)
            print(f"  ✗ {name}: LLM 失败({e}),规则兜底")
            data = _rule_fallback(page["extract_text"])
        if not data:
            db.mark_candidate(conn, c["id"], "FAILED")
            continue
        category = data.get("category") or c.get("category")
        db.mark_candidate(conn, c["id"], "EXTRACTED",
                          era=data["era"], era_rank=data["era_rank"],
                          year_label=data.get("year_label"),
                          summary=data["summary"], detail=data["detail"], category=category)
        for pre in data.get("prerequisites", []):
            db.add_relation(conn, pre, name, kind="llm")
        ok += 1
        edge_info = f",前置 {len(data.get('prerequisites', []))} 条" if data.get("prerequisites") else ""
        print(f"  ✓[A] {name}: {data['era']}/{category or '未分领域'}{edge_info}")
    return ok, budget_stopped


def _extract_tier_b(conn, candidates):
    """长尾轻量抽取:规则摘要/详情 + 批量分类 era/category(省 token),不抽 LLM 前置边。"""
    ok = budget_stopped = 0
    for i in range(0, len(candidates), config.CLASSIFY_BATCH_SIZE):
        if budget_exceeded():
            budget_stopped = 1
            print(f"[extract] token 预算触顶,Tier-B 停止")
            break
        batch = candidates[i:i + config.CLASSIFY_BATCH_SIZE]
        name_of = {c["id"]: canonical_name_of(c) for c in batch}
        conn.ping(reconnect=True)
        try:
            cls = classify_batch(list(name_of.values()))
        except Exception as e:  # noqa: BLE001 - 整批分类失败则本批留 CRAWLED 待重试
            print(f"  ✗[B] 批量分类失败({e}),本批跳过")
            continue
        for c in batch:
            page = db.raw_page_of(conn, c["id"])
            rule = _rule_fallback(page["extract_text"]) if page and page["extract_text"] else None
            name = name_of[c["id"]]
            meta = cls.get(name)
            era = meta["era"] if meta else None
            era_rank = meta["era_rank"] if meta else None
            category = (meta["category"] if meta and meta["category"] else None) or c.get("category")
            db.mark_candidate(conn, c["id"], "EXTRACTED",
                              era=era, era_rank=era_rank, year_label=None,
                              summary=(rule or {}).get("summary"),
                              detail=(rule or {}).get("detail"), category=category)
            ok += 1
        print(f"  ✓[B] 批 {i // config.CLASSIFY_BATCH_SIZE + 1}: 分类 {len(batch)} 条 "
              f"(累计 token {tokens_used()})")
    return ok, budget_stopped


def run(limit: int = 50):
    use_llm = config.llm_configured()
    conn = db.connect()
    try:
        crawled = db.candidates_by_status(conn, "CRAWLED", limit=limit, order="importance")
        if not crawled:
            print("[extract] 没有已爬待抽取的候选(先执行 --crawl)")
            return
        if not use_llm:
            # 无 LLM:全部规则兜底(只进 RAG,不入图),保持可跑通
            mode_ok = 0
            for c in crawled:
                page = db.raw_page_of(conn, c["id"])
                rule = _rule_fallback(page["extract_text"]) if page and page["extract_text"] else None
                db.mark_candidate(conn, c["id"], "EXTRACTED" if rule else "FAILED",
                                  summary=(rule or {}).get("summary"),
                                  detail=(rule or {}).get("detail"))
                mode_ok += 1 if rule else 0
            print(f"[extract] 规则降级完成(未配置 AI_API_KEY): {mode_ok} 条")
            return

        cutoff = db.tier_a_cutoff(conn, config.TIER_A_TOP)
        tier_a = [c for c in crawled if (c["importance"] or 0) >= cutoff]
        tier_b = [c for c in crawled if (c["importance"] or 0) < cutoff]
        print(f"[extract] 待抽取 {len(crawled)} 条: Tier-A 深度 {len(tier_a)}(importance≥{cutoff}), "
              f"Tier-B 轻量 {len(tier_b)}; token 预算 {config.TOKEN_BUDGET or '不限'}")
        ok_a, stop_a = _extract_tier_a(conn, tier_a)
        ok_b, stop_b = (0, 0) if stop_a else _extract_tier_b(conn, tier_b)
        print(f"[extract] 完成: Tier-A {ok_a}, Tier-B {ok_b}, 累计 token {tokens_used()}"
              + ("(预算触顶,部分留待续跑)" if stop_a or stop_b else ""))
    finally:
        conn.close()

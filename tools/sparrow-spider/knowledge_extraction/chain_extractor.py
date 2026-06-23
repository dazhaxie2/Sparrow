# -*- coding: utf-8 -*-
"""从公司维基正文抽取有证据的上游供应链关系。"""
import config

from knowledge_extraction import llm

NODE_TYPES = {"供应商", "代工厂", "材料商"}
EDGE_TYPES = {"供货", "代工", "材料供应", "授权"}
CONFIDENCE_LEVELS = {"high", "medium", "low"}


def extract_chain_relations(company_name: str, page_text: str) -> list[dict]:
    """返回固定方向的关系：counterparty(上游) -> company_name(被供应方)。"""
    if not page_text or not page_text.strip():
        return []
    if llm.budget_exceeded():
        raise RuntimeError(f"LLM token 预算已用尽（{llm.tokens_used()}/{config.TOKEN_BUDGET}）")
    prompt = (
        "你是谨慎的供应链知识抽取器。只根据给出的维基百科正文，抽取明确陈述的"
        f"「上游企业向 {company_name} 供货、代工、供应材料或授权技术」关系。\n"
        "方向固定：counterparty 必须是上游提供方，当前公司是接受方。不要抽取竞争、投资、"
        "并购、合作、客户或同业关系；不得凭常识补全。正文没有明确证据时返回空数组。\n"
        "只输出 JSON，格式如下：\n"
        '{"relations":[{"counterparty":"对方公司名","node_type":"供应商|代工厂|材料商",'
        '"edge_type":"供货|代工|材料供应|授权","product":"具体产品/环节",'
        '"confidence":"high|medium|low"}]}\n'
        "无法判断可信度时使用 low；公司名使用正文中的通行名称。\n\n"
        f"### {company_name} 词条正文（截断）\n{page_text[:5000]}"
    )
    data = llm.parse_json(llm.chat([{"role": "user", "content": prompt}], temperature=0.1))
    if not isinstance(data, dict) or not isinstance(data.get("relations"), list):
        raise ValueError("LLM 未返回合法的产业链 JSON schema")

    normalized = []
    seen = set()
    for raw in data["relations"]:
        relation = _normalize_relation(company_name, raw)
        if not relation:
            continue
        # 暂存表按 (chain, company, counterparty) 唯一；同一对公司只保留首条最明确关系。
        key = relation["counterparty"]
        if key in seen:
            continue
        seen.add(key)
        normalized.append(relation)
    return normalized


def _normalize_relation(company_name: str, raw) -> dict | None:
    if not isinstance(raw, dict):
        return None
    counterparty = str(raw.get("counterparty") or "").strip()[:160]
    if not counterparty or counterparty.casefold() == company_name.strip().casefold():
        return None
    edge_type = str(raw.get("edge_type") or "").strip()
    if edge_type not in EDGE_TYPES:
        return None
    node_type = str(raw.get("node_type") or "").strip()
    if node_type not in NODE_TYPES:
        node_type = "供应商"
    confidence = str(raw.get("confidence") or "low").strip().lower()
    if confidence not in CONFIDENCE_LEVELS:
        confidence = "low"
    product = str(raw.get("product") or "").strip()[:200] or None
    return {
        "counterparty": counterparty,
        "node_type": node_type,
        "edge_type": edge_type,
        "product": product,
        "confidence": confidence,
    }

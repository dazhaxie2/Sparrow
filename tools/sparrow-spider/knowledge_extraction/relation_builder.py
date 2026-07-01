# -*- coding: utf-8 -*-
"""结构化关系 + 重要度构建(零额外 LLM)。

维基级科技图「全图互连」的真正落地处:
- rank_importance: 重要度 = 被其它已爬词条内链指向的次数(入链中心度),供分层抽取排序;
- build_structural: 把每个已抽取词条的内链与节点全集求交,生成结构边(kind=structural);
  方向不在这里定,留给 sync 按 era_rank 定向(前置不晚于后继),从而天然去环成 DAG。
"""
import json
from collections import Counter

from knowledge_extraction.extractor import canonical_name_of
from storage import db
from topic_discovery.seeds import SPARROW_NODES


def _iter_links(links_json):
    if not links_json:
        return []
    try:
        return json.loads(links_json)
    except (ValueError, TypeError):
        return []


def rank_importance(conn):
    """计算入链中心度并写回 tech_candidate.importance(深度抽取分层的依据)。"""
    cands = db.candidates_with_page(conn)
    title_to_cid = {c["page_title"]: c["id"] for c in cands if c["page_title"]}
    title_set = set(title_to_cid)
    if not title_set:
        print("[rank] 暂无已爬词条(先执行 --crawl)")
        return
    indeg = Counter()
    for rp in db.all_raw_pages(conn):
        for link in _iter_links(rp["links_json"]):
            if link in title_set:
                indeg[link] += 1
    updated = 0
    for title, cid in title_to_cid.items():
        importance = indeg.get(title, 0)
        if importance:
            db.update_candidate_importance(conn, cid, importance)
            updated += 1
    top = indeg.most_common(8)
    print(f"[rank] 重要度计算完成: {updated}/{len(title_set)} 个词条有入链;"
          f"热门: {', '.join(f'{t}({n})' for t, n in top)}")


def build_structural(conn, flush_every: int = 2000):
    """生成结构边:已抽取词条的内链 ∩ 节点全集。批量写入,去重靠唯一键。"""
    extracted = db.all_extracted(conn)
    if not extracted:
        print("[edges] 暂无已抽取节点(先执行 --extract)")
        return
    cands = db.candidates_with_page(conn)
    # 标题/规范名 → 规范名(把内链标题、维基别名都消解到节点 name)
    title_to_name = {}
    for c in cands:
        nm = canonical_name_of(c)
        if c["page_title"]:
            title_to_name[c["page_title"]] = nm
        title_to_name.setdefault(nm, nm)
    for nm in SPARROW_NODES:
        title_to_name.setdefault(nm, nm)

    pending = []
    total = 0
    for c in extracted:
        src = canonical_name_of(c)
        page = db.raw_page_of(conn, c["id"])
        if not page:
            continue
        for link in _iter_links(page["links_json"]):
            tgt = title_to_name.get(link)
            if tgt and tgt != src:
                pending.append((src, tgt, "structural", "structural"))
        if len(pending) >= flush_every:
            total += db.add_relations_bulk(conn, pending)
            pending = []
    total += db.add_relations_bulk(conn, pending)
    print(f"[edges] 结构边构建完成: 写入候选 {total} 条(方向与去环在 sync 阶段按 era_rank 处理)")

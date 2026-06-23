# -*- coding: utf-8 -*-
"""把 techspider 的供应链暂存结果幂等同步到独立 sparrow_chain 业务库。"""
import socket

import pymysql
from opencc import OpenCC

import config
from storage import db
from topic_discovery.chain_seeds import CHAINS

_cc = OpenCC("t2s")
_TYPE_PRIORITY = {"材料商": 1, "供应商": 2, "代工厂": 3, "核心公司": 4}


def _s(text):
    return _cc.convert(text) if text else text


def _chain_conn():
    return pymysql.connect(
        host=config.SPARROW_CHAIN_DB_HOST,
        port=config.SPARROW_CHAIN_DB_PORT,
        user=config.SPARROW_CHAIN_DB_USER,
        password=config.SPARROW_CHAIN_DB_PASSWORD,
        database=config.SPARROW_CHAIN_DB_NAME,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


def _merge_node(nodes, name, node_type, summary=None):
    name = (_s(name) or "").strip()[:160]
    if not name:
        return
    current = nodes.get(name)
    node_type = node_type if node_type in _TYPE_PRIORITY else "供应商"
    if current is None:
        nodes[name] = {"node_type": node_type, "summary": _s(summary)}
        return
    if _TYPE_PRIORITY[node_type] > _TYPE_PRIORITY[current["node_type"]]:
        current["node_type"] = node_type
    if summary and not current.get("summary"):
        current["summary"] = _s(summary)


def _build_node_meta(slug, companies, relations):
    """构造 name -> 节点属性；首个种子是核心公司，其余种子默认供应商。"""
    nodes = {}
    primary = _s(CHAINS[slug]["seeds"][0])
    for company in companies:
        name = _s(company["name"])
        _merge_node(nodes, name, "核心公司" if name == primary else "供应商", company.get("summary"))
    for relation in relations:
        company_name = _s(relation["company_name"])
        _merge_node(nodes, company_name, "核心公司" if company_name == primary else "供应商")
        _merge_node(nodes, relation["counterparty_name"], relation.get("counterparty_type"))
    return nodes


def _edge_specs(relations):
    """固定方向：上游 counterparty -> 被供应 company。"""
    specs = []
    seen = set()
    for relation in relations:
        source = (_s(relation["counterparty_name"]) or "").strip()[:160]
        target = (_s(relation["company_name"]) or "").strip()[:160]
        edge_type = relation.get("edge_type") or "供货"
        if not source or not target or source == target:
            continue
        key = (source, target, edge_type)
        if key in seen:
            continue
        seen.add(key)
        specs.append((source, target, edge_type[:32], (_s(relation.get("product")) or "")[:200] or None))
    return specs


def _sync_one(target, slug, companies, relations):
    chain = CHAINS[slug]
    nodes = _build_node_meta(slug, companies, relations)
    edges = _edge_specs(relations)
    with target.cursor() as cur:
        cur.execute(
            "INSERT INTO chain (slug, name, description, cover_color) VALUES (%s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description), "
            "cover_color=VALUES(cover_color)",
            (slug, chain["name"], chain["description"], chain["cover_color"]),
        )
        cur.execute("SELECT id FROM chain WHERE slug=%s", (slug,))
        chain_id = cur.fetchone()["id"]

        # 本链以爬虫暂存表为事实来源。边先清空再重建，避免 LLM 重跑后残留旧类型；
        # 节点通过 upsert 保持已有 id，随后移除本轮已不存在的孤立项。
        cur.execute("DELETE FROM chain_edge WHERE chain_id=%s", (chain_id,))
        for name, meta in nodes.items():
            cur.execute(
                "INSERT INTO chain_node (chain_id, name, node_type, summary, importance) "
                "VALUES (%s, %s, %s, %s, 0) "
                "ON DUPLICATE KEY UPDATE node_type=VALUES(node_type), "
                "summary=COALESCE(VALUES(summary), summary)",
                (chain_id, name, meta["node_type"], meta.get("summary")),
            )

        if nodes:
            placeholders = ",".join(["%s"] * len(nodes))
            cur.execute(
                f"DELETE FROM chain_node WHERE chain_id=%s AND name NOT IN ({placeholders})",
                (chain_id, *nodes.keys()),
            )
        else:
            cur.execute("DELETE FROM chain_node WHERE chain_id=%s", (chain_id,))

        cur.execute("SELECT id, name FROM chain_node WHERE chain_id=%s", (chain_id,))
        name_to_id = {row["name"]: row["id"] for row in cur.fetchall()}
        for source, target_name, edge_type, product in edges:
            from_id = name_to_id.get(source)
            to_id = name_to_id.get(target_name)
            if not from_id or not to_id:
                continue
            cur.execute(
                "INSERT INTO chain_edge (chain_id, from_id, to_id, edge_type, product) "
                "VALUES (%s, %s, %s, %s, %s) "
                "ON DUPLICATE KEY UPDATE product=VALUES(product), chain_id=VALUES(chain_id)",
                (chain_id, from_id, to_id, edge_type, product),
            )

        # importance = 有向图总度数，供前端映射节点大小。
        cur.execute(
            "UPDATE chain_node n LEFT JOIN ("
            "  SELECT node_id, COUNT(*) AS degree FROM ("
            "    SELECT from_id AS node_id FROM chain_edge WHERE chain_id=%s "
            "    UNION ALL SELECT to_id AS node_id FROM chain_edge WHERE chain_id=%s"
            "  ) degree_rows GROUP BY node_id"
            ") d ON d.node_id=n.id SET n.importance=COALESCE(d.degree, 0) WHERE n.chain_id=%s",
            (chain_id, chain_id, chain_id),
        )
    return len(nodes), len(edges)


def _invalidate_chain_cache():
    """当前服务未启用缓存；保留固定 key 失效接缝，后续加缓存无需改同步流程。"""
    key = "sparrow:chain:list"
    command = f"*2\r\n$3\r\nDEL\r\n${len(key)}\r\n{key}\r\n".encode()
    try:
        with socket.create_connection(
                (config.SPARROW_REDIS_HOST, config.SPARROW_REDIS_PORT), timeout=3) as sock:
            sock.sendall(command)
            response = sock.recv(64).decode(errors="replace")
        print(f"[chain-sync] Redis 缓存已失效（{response.strip()}）")
    except OSError as error:
        print(f"[chain-sync] Redis 不可达，跳过缓存失效：{error}")


def run():
    source = db.connect()
    target = _chain_conn()
    total_nodes = total_edges = 0
    try:
        for slug in CHAINS:
            companies = db.supply_chain_companies(source, slug)
            relations = db.supply_chain_relations(source, slug)
            node_count, edge_count = _sync_one(target, slug, companies, relations)
            total_nodes += node_count
            total_edges += edge_count
            print(f"[chain-sync] {slug}: {node_count} 节点 / {edge_count} 边")
        target.commit()
    except Exception:
        target.rollback()
        raise
    finally:
        source.close()
        target.close()
    _invalidate_chain_cache()
    print(f"[chain-sync] 完成：{total_nodes} 节点 / {total_edges} 边")
    return {"nodes": total_nodes, "edges": total_edges}

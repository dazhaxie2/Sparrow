"""
Sparrow 百万级图谱 LOD 布局预计算(M2)。

读 tech_node/tech_edge → Louvain 聚类 → 分簇分层布局 → 写 node_layout。
配合 GraphController /api/graph/tiles/{level}/{clusterId} 的 LOD 瓦片接口。

LOD 层级语义:
  level 0: 顶层全景 —— 每个"主簇"一个代表点(簇内 importance 最高者),坐标=簇心。
  level 3: 叶节点精确坐标 —— 全部节点(主簇成员 + 外围孤立/小簇节点)。
  level 1/2: 中间粒度 —— 按 importance 抽稀的 level 3 子集(坐标复用),放大逐级展开。

布局策略(避开百万级全图 O(n^2)):
  1. Louvain 社区发现。size>=MIN_MAIN_CLUSTER 的算"主簇",其余(孤立/小簇)归一个 misc 簇。
  2. 簇心布局:只对主簇超图做加权 spring_layout → 簇心坐标(超节点少,快,可扩展)。
  3. 簇内布局:每个主簇子图独立 spring_layout,平移到簇心 + 按规模缩放半径。
  4. 外围:misc 节点环形撒在主簇范围之外 —— 远观不挤占全景,放大可见。

用法:
  pip install -r requirements.txt
  python layout.py --scope real --rebuild
  python layout.py --scope all  --rebuild --max-nodes 1200000

DB 连接复用 spider 的 env(SPARROW_GRAPH_DB_*),默认 127.0.0.1:3307 / root / root123 / sparrow_graph。
"""
from __future__ import annotations

import argparse
import math
import os
import sys
import time
from collections import defaultdict

import pymysql

try:
    import networkx as nx
except ImportError:
    sys.exit("缺少依赖,请先: pip install -r requirements.txt")

SCALE = 10000.0
LARGE_CLUSTER = 2500
MIN_MAIN_CLUSTER = 10
LEVEL1_FRAC = 0.05
LEVEL2_FRAC = 0.25


def connect():
    return pymysql.connect(
        host=os.environ.get("SPARROW_GRAPH_DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("SPARROW_GRAPH_DB_PORT", "3307")),
        user=os.environ.get("SPARROW_GRAPH_DB_USER", "root"),
        password=os.environ.get("SPARROW_GRAPH_DB_PASSWORD", "root123"),
        database=os.environ.get("SPARROW_GRAPH_DB_NAME", "sparrow_graph"),
        charset="utf8mb4",
        autocommit=False,
    )


def load_graph(conn, scope, max_nodes):
    where = ""
    if scope == "real":
        where = "WHERE code NOT LIKE 'syn\\_%'"
    elif scope == "syn":
        where = "WHERE code LIKE 'syn\\_%'"
    with conn.cursor() as cur:
        cur.execute("SELECT id, importance FROM tech_node " + where)
        node_rows = cur.fetchall()
    if max_nodes and len(node_rows) > max_nodes:
        sys.exit("节点数 %d 超过 --max-nodes %d;先在更小 scope 验证或调高上限。"
                 % (len(node_rows), max_nodes))
    importance = {int(r[0]): int(r[1] or 0) for r in node_rows}
    node_ids = set(importance)

    with conn.cursor() as cur:
        cur.execute("SELECT from_id, to_id FROM tech_edge")
        edge_rows = cur.fetchall()
    g = nx.Graph()
    g.add_nodes_from(node_ids)
    for f, t in edge_rows:
        f, t = int(f), int(t)
        if f != t and f in node_ids and t in node_ids:
            g.add_edge(f, t)
    return g, importance


def cluster(g):
    return nx.community.louvain_communities(g, seed=42)


def partition(communities):
    mains = sorted((set(c) for c in communities if len(c) >= MIN_MAIN_CLUSTER),
                   key=len, reverse=True)
    cluster_of = {}
    for cid, comm in enumerate(mains):
        for n in comm:
            cluster_of[n] = cid
    misc_id = len(mains)
    minor = set()
    for c in communities:
        if len(c) < MIN_MAIN_CLUSTER:
            for n in c:
                minor.add(n)
                cluster_of[n] = misc_id
    return mains, minor, cluster_of, misc_id


def _local_layout(sub):
    n = sub.number_of_nodes()
    if n == 1:
        return {next(iter(sub.nodes())): (0.0, 0.0)}
    if n <= LARGE_CLUSTER:
        pos = nx.spring_layout(sub, seed=42, iterations=80)
        return {k: (float(v[0]), float(v[1])) for k, v in pos.items()}
    pos = nx.random_layout(sub, seed=42)
    return {k: (float(v[0]) - 0.5, float(v[1]) - 0.5) for k, v in pos.items()}


def layout(g, mains, minor, cluster_of, misc_id):
    super_g = nx.Graph()
    super_g.add_nodes_from(range(len(mains)))
    inter = defaultdict(int)
    for u, v in g.edges():
        cu = cluster_of[u]
        cv = cluster_of[v]
        if cu != cv and cu < misc_id and cv < misc_id:
            a, b = (cu, cv) if cu < cv else (cv, cu)
            inter[(a, b)] += 1
    for (a, b), w in inter.items():
        super_g.add_edge(a, b, weight=w)
    if len(mains) > 1:
        cpos = nx.spring_layout(super_g, seed=42, iterations=200, weight="weight")
        cluster_pos = {c: (float(p[0]), float(p[1])) for c, p in cpos.items()}
    else:
        cluster_pos = {0: (0.0, 0.0)} if mains else {}

    pos3 = {}
    for cid, comm in enumerate(mains):
        sub = g.subgraph(comm)
        cx, cy = cluster_pos[cid]
        cx, cy = cx * SCALE, cy * SCALE
        radius = SCALE * 0.06 * math.sqrt(max(sub.number_of_nodes(), 1))
        for node, (lx, ly) in _local_layout(sub).items():
            pos3[node] = (cx + lx * radius, cy + ly * radius)

    ext = max((max(abs(x), abs(y)) for x, y in pos3.values()), default=SCALE)
    base_r = ext * 1.15 + SCALE * 0.1
    minor_list = sorted(minor)
    m = len(minor_list)
    for i, node in enumerate(minor_list):
        ang = 2 * math.pi * i / max(m, 1)
        ring = base_r * (1.0 + 0.035 * (i % 16))
        pos3[node] = (ring * math.cos(ang), ring * math.sin(ang))
    return pos3, cluster_pos


def build_rows(mains, cluster_of, pos3, cluster_pos, importance):
    rows = []

    for n, (x, y) in pos3.items():
        rows.append((n, cluster_of[n], 3, x, y))

    for cid, comm in enumerate(mains):
        rep = max(comm, key=lambda n: importance.get(n, 0))
        cx, cy = cluster_pos[cid]
        rows.append((rep, cid, 0, cx * SCALE, cy * SCALE))

    imp_sorted = sorted(importance.values(), reverse=True)

    def threshold(frac):
        if not imp_sorted:
            return 0
        return imp_sorted[min(len(imp_sorted) - 1, int(len(imp_sorted) * frac))]

    t1, t2 = threshold(LEVEL1_FRAC), threshold(LEVEL2_FRAC)
    for n, (x, y) in pos3.items():
        imp = importance.get(n, 0)
        if imp >= t1:
            rows.append((n, cluster_of[n], 1, x, y))
        if imp >= t2:
            rows.append((n, cluster_of[n], 2, x, y))
    return rows


def write_layout(conn, rows, rebuild):
    sql = ("INSERT INTO node_layout (node_id, cluster_id, level, x, y) "
           "VALUES (%s, %s, %s, %s, %s) "
           "ON DUPLICATE KEY UPDATE cluster_id=VALUES(cluster_id), x=VALUES(x), y=VALUES(y)")
    with conn.cursor() as cur:
        if rebuild:
            cur.execute("TRUNCATE TABLE node_layout")
        for i in range(0, len(rows), 2000):
            cur.executemany(sql, rows[i:i + 2000])
    conn.commit()


def main():
    ap = argparse.ArgumentParser(description="Sparrow LOD 布局预计算")
    ap.add_argument("--scope", choices=["real", "all", "syn"], default="real")
    ap.add_argument("--rebuild", action="store_true")
    ap.add_argument("--max-nodes", type=int, default=300000)
    args = ap.parse_args()

    t0 = time.time()
    conn = connect()
    try:
        print("[1/5] load graph scope=%s" % args.scope, flush=True)
        g, importance = load_graph(conn, args.scope, args.max_nodes)
        print("      nodes %d edges %d" % (g.number_of_nodes(), g.number_of_edges()), flush=True)

        print("[2/5] louvain + partition", flush=True)
        communities = cluster(g)
        mains, minor, cluster_of, misc_id = partition(communities)
        print("      %d communities -> %d main clusters (>=%d) + %d outer nodes"
              % (len(communities), len(mains), MIN_MAIN_CLUSTER, len(minor)), flush=True)

        print("[3/5] layered layout", flush=True)
        pos3, cluster_pos = layout(g, mains, minor, cluster_of, misc_id)

        print("[4/5] build LOD rows", flush=True)
        rows = build_rows(mains, cluster_of, pos3, cluster_pos, importance)
        per_level = defaultdict(int)
        for r in rows:
            per_level[r[2]] += 1
        print("      %d rows  " % len(rows)
              + "  ".join("L%d=%d" % (k, per_level[k]) for k in sorted(per_level)), flush=True)

        print("[5/5] write node_layout rebuild=%s" % args.rebuild, flush=True)
        write_layout(conn, rows, args.rebuild)
        print("done in %.1fs" % (time.time() - t0), flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    main()

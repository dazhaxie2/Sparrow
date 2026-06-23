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
DEFAULT_TARGET_CLUSTER_SIZE = 300
GOLDEN_ANGLE = math.pi * (3.0 - math.sqrt(5.0))


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


def _scope_where(scope):
    if scope == "real":
        return "WHERE code NOT LIKE 'syn\\_%'"
    if scope == "syn":
        return "WHERE code LIKE 'syn\\_%'"
    return ""


def count_scope_nodes(conn, scope):
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM tech_node " + _scope_where(scope))
        return int(cur.fetchone()[0])


def prepare_staging_table(conn):
    with conn.cursor() as cur:
        # staging 是离线工作表;线上 node_layout 始终保持可读。
        cur.execute("DROP TABLE IF EXISTS node_layout_stage")
        cur.execute("CREATE TABLE node_layout_stage LIKE node_layout")
    conn.commit()


def swap_staged_layout(conn, expected_total=None, expected_l3=None, expected_l0=None):
    with conn.cursor() as cur:
        cur.execute("SELECT level, COUNT(*) FROM node_layout_stage GROUP BY level")
        per_level = {int(level): int(count) for level, count in cur.fetchall()}
        staged = sum(per_level.values())
        if expected_total is not None and staged != expected_total:
            raise RuntimeError(
                "staging 行数校验失败: expected=%d actual=%d;线上 node_layout 未改动"
                % (expected_total, staged)
            )
        if expected_l3 is not None and per_level.get(3, 0) != expected_l3:
            raise RuntimeError(
                "staging L3 覆盖校验失败: expected=%d actual=%d;线上 node_layout 未改动"
                % (expected_l3, per_level.get(3, 0))
            )
        if expected_l0 is not None and per_level.get(0, 0) != expected_l0:
            raise RuntimeError(
                "staging L0 簇数校验失败: expected=%d actual=%d;线上 node_layout 未改动"
                % (expected_l0, per_level.get(0, 0))
            )

        # RENAME TABLE 是 MySQL 原子 DDL:读请求只会看到完整旧版或完整新版。
        # 上一版保留在 node_layout_previous,需要时可人工原子回滚。
        cur.execute("DROP TABLE IF EXISTS node_layout_previous")
        cur.execute(
            "RENAME TABLE node_layout TO node_layout_previous, "
            "node_layout_stage TO node_layout"
        )
    return per_level


def write_layout(conn, rows, rebuild):
    target = "node_layout_stage" if rebuild else "node_layout"
    sql = (f"INSERT INTO {target} (node_id, cluster_id, level, x, y) "
           "VALUES (%s, %s, %s, %s, %s) "
           "ON DUPLICATE KEY UPDATE cluster_id=VALUES(cluster_id), x=VALUES(x), y=VALUES(y)")
    with conn.cursor() as cur:
        if rebuild:
            prepare_staging_table(conn)
        for i in range(0, len(rows), 2000):
            cur.executemany(sql, rows[i:i + 2000])
    conn.commit()

    if not rebuild:
        return

    swap_staged_layout(conn, expected_total=len(rows))


def _category_plan(conn, scope, target_cluster_size):
    where = _scope_where(scope)
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COALESCE(category, '未分类') AS category_key, COUNT(*) "
            "FROM tech_node %s GROUP BY category_key ORDER BY category_key" % where
        )
        counts = [(str(category), int(count)) for category, count in cur.fetchall()]

    plan = {}
    cluster_offset = 0
    for category_index, (category, count) in enumerate(counts):
        cluster_count = max(1, math.ceil(count / target_cluster_size))
        plan[category] = {
            "category_index": category_index,
            "count": count,
            "cluster_count": cluster_count,
            "cluster_offset": cluster_offset,
        }
        cluster_offset += cluster_count
    return plan, cluster_offset


def _cluster_center(category_index, category_total, cluster_index, cluster_count):
    # 领域沿大圆分区,领域内的小簇按网格排布;簇间距远大于簇内半径。
    category_radius = 180000.0 if category_total > 1 else 0.0
    category_angle = 2.0 * math.pi * category_index / max(category_total, 1)
    base_x = category_radius * math.cos(category_angle)
    base_y = category_radius * math.sin(category_angle)
    columns = max(1, math.ceil(math.sqrt(cluster_count)))
    rows = max(1, math.ceil(cluster_count / columns))
    column = cluster_index % columns
    row = cluster_index // columns
    spacing = 2600.0
    return (
        base_x + (column - (columns - 1) / 2.0) * spacing,
        base_y + (row - (rows - 1) / 2.0) * spacing,
    )


def write_scalable_layout(conn, scope, max_nodes, target_cluster_size, rebuild):
    """百万级流式布局:内存 O(簇数),不加载全量边,不构建 NetworkX 全图。"""
    node_total = count_scope_nodes(conn, scope)
    if max_nodes and node_total > max_nodes:
        sys.exit("节点数 %d 超过 --max-nodes %d;请显式调高上限。" % (node_total, max_nodes))
    plan, cluster_total = _category_plan(conn, scope, target_cluster_size)
    if not plan:
        raise RuntimeError("当前 scope 没有可布局节点")

    target = "node_layout_stage" if rebuild else "node_layout"
    if rebuild:
        prepare_staging_table(conn)
    insert_sql = (
        f"INSERT INTO {target} (node_id, cluster_id, level, x, y) "
        "VALUES (%s, %s, %s, %s, %s) "
        "ON DUPLICATE KEY UPDATE cluster_id=VALUES(cluster_id), x=VALUES(x), y=VALUES(y)"
    )

    category_seen = defaultdict(int)
    representatives = {}
    batch = []
    processed = 0
    read_conn = connect()
    try:
        with read_conn.cursor(pymysql.cursors.SSCursor) as reader, conn.cursor() as writer:
            reader.execute(
                "SELECT id, importance, COALESCE(category, '未分类') AS category_key "
                "FROM tech_node %s ORDER BY category_key, id" % _scope_where(scope)
            )
            for node_id, importance, category in reader:
                node_id = int(node_id)
                importance = int(importance or 0)
                category = str(category)
                meta = plan[category]
                category_local_index = category_seen[category]
                category_seen[category] += 1
                cluster_index = category_local_index // target_cluster_size
                local_index = category_local_index % target_cluster_size
                cluster_id = meta["cluster_offset"] + cluster_index
                center_x, center_y = _cluster_center(
                    meta["category_index"], len(plan), cluster_index, meta["cluster_count"]
                )
                local_radius = 44.0 * math.sqrt(local_index)
                local_angle = GOLDEN_ANGLE * local_index
                x = center_x + local_radius * math.cos(local_angle)
                y = center_y + local_radius * math.sin(local_angle)

                batch.append((node_id, cluster_id, 3, x, y))
                # 稳定抽稀 + 高重要度兜底,L1 始终是 L2 子集。
                if local_index % 4 == 0 or importance >= 75:
                    batch.append((node_id, cluster_id, 2, x, y))
                if local_index % 20 == 0 or importance >= 95:
                    batch.append((node_id, cluster_id, 1, x, y))

                current = representatives.get(cluster_id)
                candidate = (importance, -node_id, node_id, center_x, center_y)
                if current is None or candidate[:2] > current[:2]:
                    representatives[cluster_id] = candidate

                processed += 1
                if len(batch) >= 10000:
                    writer.executemany(insert_sql, batch)
                    conn.commit()
                    batch.clear()
                if processed % 100000 == 0:
                    print("      layout progress %d / %d" % (processed, node_total), flush=True)

            if batch:
                writer.executemany(insert_sql, batch)
                conn.commit()

            level0_rows = [
                (node_id, cluster_id, 0, center_x, center_y)
                for cluster_id, (_importance, _negative_id, node_id, center_x, center_y)
                in representatives.items()
            ]
            for i in range(0, len(level0_rows), 2000):
                writer.executemany(insert_sql, level0_rows[i:i + 2000])
            conn.commit()
    finally:
        read_conn.close()

    if processed != node_total:
        raise RuntimeError("读取节点数不一致: expected=%d actual=%d" % (node_total, processed))
    if len(representatives) != cluster_total:
        raise RuntimeError(
            "生成簇数不一致: expected=%d actual=%d" % (cluster_total, len(representatives))
        )
    if rebuild:
        per_level = swap_staged_layout(
            conn, expected_l3=node_total, expected_l0=cluster_total
        )
    else:
        with conn.cursor() as cur:
            cur.execute("SELECT level, COUNT(*) FROM node_layout GROUP BY level")
            per_level = {int(level): int(count) for level, count in cur.fetchall()}
    return node_total, cluster_total, per_level


def main():
    ap = argparse.ArgumentParser(description="Sparrow LOD 布局预计算")
    ap.add_argument("--scope", choices=["real", "all", "syn"], default="real")
    ap.add_argument("--rebuild", action="store_true")
    ap.add_argument("--max-nodes", type=int, default=300000)
    ap.add_argument("--strategy", choices=["auto", "louvain", "scalable"], default="auto")
    ap.add_argument("--target-cluster-size", type=int, default=DEFAULT_TARGET_CLUSTER_SIZE)
    args = ap.parse_args()

    t0 = time.time()
    conn = connect()
    try:
        node_total = count_scope_nodes(conn, args.scope)
        strategy = args.strategy
        if strategy == "auto":
            strategy = "scalable" if node_total > 100000 else "louvain"
        print("strategy=%s scope=%s nodes=%d" % (strategy, args.scope, node_total), flush=True)

        if strategy == "scalable":
            nodes, clusters, per_level = write_scalable_layout(
                conn, args.scope, args.max_nodes, args.target_cluster_size, args.rebuild
            )
            print("      nodes=%d clusters=%d %s" % (
                nodes,
                clusters,
                " ".join("L%d=%d" % (level, count) for level, count in sorted(per_level.items())),
            ), flush=True)
            print("done in %.1fs" % (time.time() - t0), flush=True)
            return

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

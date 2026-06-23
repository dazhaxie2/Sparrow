# sparrow-layout — 百万级图谱 LOD 布局预计算(M2)

把 `tech_node` / `tech_edge` 的拓扑离线算成分层坐标,写入 `node_layout` 表,供
`GET /api/graph/tiles/{level}/{clusterId}` 的 LOD 瓦片接口按视口 zoom 取用。

## 原理

1. **Louvain 聚类**(networkx 内置)→ 每个节点一个 `cluster_id`。
2. **簇心布局**:每个簇坍缩成超节点,簇间按跨簇边数加权 `spring_layout` → 簇心坐标。
3. **簇内布局**:每个簇子图独立布局,平移到簇心、按簇规模缩放半径。
4. **分层(LOD)**:
   - `level 0` = 每簇一个代表点(importance 最高),坐标=簇心 → 远观全景。
   - `level 3` = 全部叶节点精确坐标。
   - `level 1/2` = 按 importance 抽稀的 level 3 子集(坐标复用)→ 放大逐级展开。

按簇分治,避免对全图跑 O(n²) 力导向。

## 用法

```bash
pip install -r requirements.txt

# 先在真实数据上验证(推荐)
python layout.py --scope real --rebuild

# 全量(含合成,注意内存与 --max-nodes 上限)
python layout.py --scope all --rebuild --max-nodes 1200000
```

`--rebuild` 使用 staging 表完整写入并校验后，再通过 MySQL `RENAME TABLE` 原子切换；
运行失败不会清空线上 `node_layout`，上一版保留在 `node_layout_previous` 供回滚。

DB 连接复用 spider 的环境变量 `SPARROW_GRAPH_DB_*`(默认 `127.0.0.1:3307 / root / root123 / sparrow_graph`)。

## 验证

```sql
SELECT level, COUNT(*) FROM node_layout GROUP BY level;          -- 各层覆盖
SELECT COUNT(DISTINCT cluster_id) FROM node_layout WHERE level=0; -- 簇数
```

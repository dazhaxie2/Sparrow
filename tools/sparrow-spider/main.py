# -*- coding: utf-8 -*-
"""SparrowSpider 入口(MindSpider 式命令行)。

流水线: 种子载入 → 限速爬取 → 知识抽取 → 导出给 Sparrow

  python main.py --init-seeds          # 载入种子词表(离线)
  python main.py --crawl --limit 20    # 爬取 PENDING 候选
  python main.py --extract             # LLM/规则 抽取知识与依赖边
  python main.py --expand              # 用已爬词条的内链扩展候选(需 AI_API_KEY)
  python main.py --export              # 导出 Sparrow seed SQL + RAG 语料
  python main.py --chains              # 抓取四条产业链并同步到 sparrow_chain
  python main.py --all --limit 20      # 串行执行完整流水线
  python main.py --status              # 查看各阶段进度
"""
import argparse
import sys

from storage import db


def show_status():
    conn = db.connect()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT status, COUNT(*) AS n FROM tech_candidate GROUP BY status")
            rows = {r["status"]: r["n"] for r in cur.fetchall()}
            cur.execute("SELECT COUNT(*) AS n FROM raw_page")
            pages = cur.fetchone()["n"]
            cur.execute("SELECT COUNT(*) AS n FROM tech_relation")
            rels = cur.fetchone()["n"]
        print("候选状态:", rows or "(空,先 --init-seeds)")
        print(f"原始页面: {pages} 篇 | 依赖关系: {rels} 条")
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="SparrowSpider - 科技树知识爬虫")
    parser.add_argument("--init-seeds", action="store_true", help="载入种子词表(Sparrow 77 对齐 + 扩展)")
    parser.add_argument("--discover-categories", action="store_true",
                        help="分类目录子类目 BFS 发现候选(到万级的主引擎,无 LLM)")
    parser.add_argument("--crawl", action="store_true", help="爬取待处理候选")
    parser.add_argument("--rank", action="store_true", help="计算入链重要度(分层抽取依据)")
    parser.add_argument("--extract", action="store_true", help="分层抽取知识(Tier-A 深度 / Tier-B 批量分类)")
    parser.add_argument("--build-edges", action="store_true", help="构建结构边(内链共现,无 LLM)")
    parser.add_argument("--expand", action="store_true", help="内链扩展候选(需 AI_API_KEY)")
    parser.add_argument("--expand-links", action="store_true",
                        help="从已爬页面内链按频次扩展候选(纯查库,突破分类天花板)")
    parser.add_argument("--export", action="store_true", help="导出 Sparrow 产物文件")
    parser.add_argument("--sync-sparrow", action="store_true",
                        help="直连同步进 Sparrow 业务库(节点/边/增补/RAG 语料 + 缓存失效)")
    parser.add_argument("--chains", action="store_true",
                        help="抓取四条产业链并同步到独立 sparrow_chain 业务库")
    parser.add_argument("--chain-companies", default="",
                        help="仅重跑指定种子公司，逗号分隔（需与 --chains 同用）")
    parser.add_argument("--all", action="store_true",
                        help="完整流水线: 种子+分类发现→爬取→排序→抽取→建边→同步")
    parser.add_argument("--status", action="store_true", help="查看进度")
    parser.add_argument("--limit", type=int, default=50, help="本轮爬取/抽取条数上限(默认 50;全量设大)")
    args = parser.parse_args()

    if not any([args.init_seeds, args.discover_categories, args.crawl, args.rank, args.extract,
                args.build_edges, args.expand, args.expand_links, args.export, args.sync_sparrow,
                args.chains, args.all, args.status]):
        parser.print_help()
        return

    try:
        db.init_schema()
    except Exception as e:  # noqa: BLE001
        print(f"[fatal] MySQL 不可达({e})。请先启动 Sparrow 的 docker compose"
              "(其 MySQL 暴露在宿主机 3307),或通过 SPIDER_DB_* 指向其他实例。")
        sys.exit(1)

    if args.status:
        show_status()
        return

    if args.init_seeds or args.all:
        from topic_discovery import discover
        discover.load_seeds()
    if args.discover_categories or args.all:
        from topic_discovery import discover
        discover.discover_from_categories()
    if args.all:
        from topic_discovery import discover as _disc
        _disc.expand_links_bulk()
    if args.crawl or args.all:
        from deep_crawling import crawler
        crawler.run(limit=args.limit)
    if args.expand:
        from topic_discovery import discover
        discover.expand_from_links(limit=args.limit)
    if args.expand_links:
        from topic_discovery import discover
        discover.expand_links_bulk()
    if args.rank or args.all:
        from knowledge_extraction import relation_builder
        conn = db.connect()
        try:
            relation_builder.rank_importance(conn)
        finally:
            conn.close()
    if args.extract or args.all:
        from knowledge_extraction import extractor
        extractor.run(limit=args.limit)
    if args.build_edges or args.all:
        from knowledge_extraction import relation_builder
        conn = db.connect()
        try:
            relation_builder.build_structural(conn)
        finally:
            conn.close()
    if args.export:
        from export import sparrow_exporter
        sparrow_exporter.run()
    if args.sync_sparrow or args.all:
        from export import sparrow_sync
        sparrow_sync.run()
    if args.chains:
        import chain_pipeline
        from export import chain_sync
        selected = [name.strip() for name in args.chain_companies.split(",") if name.strip()]
        chain_pipeline.run(selected)
        chain_sync.run()


if __name__ == "__main__":
    main()

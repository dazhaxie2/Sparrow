# -*- coding: utf-8 -*-
"""阶段二:深度爬取(类比 MindSpider 的 DeepSentimentCrawling)。

对 PENDING 候选逐个:检索定位词条 → 拉取纯文本全文与内链 → 存 raw_page → 标记 CRAWLED。
全程单并发 + 限速,尊重源站。
"""
import asyncio

from deep_crawling.wiki_client import WikiClient, links_to_json
from storage import db


async def crawl(limit: int = 50):
    conn = db.connect()
    client = WikiClient()
    ok = failed = 0
    try:
        pending = db.candidates_by_status(conn, "PENDING", limit=limit)
        if not pending:
            print("[crawl] 没有待爬候选(先执行 --init-seeds)")
            return
        print(f"[crawl] 待爬 {len(pending)} 条,限速 1 req/s,预计 {len(pending) * 2} 秒左右…")
        for c in pending:
            try:
                resolved = await client.resolve_title(c["term"])
                if not resolved:
                    db.mark_candidate(conn, c["id"], "FAILED")
                    failed += 1
                    print(f"  ✗ {c['term']}: 未找到词条")
                    continue
                pageid, title = resolved
                page = await client.fetch_page(pageid)
                db.save_raw_page(conn, c["id"], pageid, page["title"], page["url"],
                                 page["text"], links_to_json(page["links"]))
                db.mark_candidate(conn, c["id"], "CRAWLED",
                                  page_title=page["title"], page_id=pageid)
                ok += 1
                print(f"  ✓ {c['term']} → {page['title']} ({len(page['text'])} 字)")
            except ConnectionError as e:
                # 端点整体不可达:立即终止本轮,而不是把所有候选标记失败
                print(f"[crawl] 网络不可达,本轮终止: {e}")
                print("        提示: 大陆网络可设置代理 SPIDER_PROXY=http://127.0.0.1:7890,"
                      "或用 SPIDER_WIKI_API 指向可达的 MediaWiki 镜像")
                break
            except Exception as e:  # noqa: BLE001 - 单条失败不影响整轮
                db.mark_candidate(conn, c["id"], "FAILED")
                failed += 1
                print(f"  ✗ {c['term']}: {e}")
    finally:
        await client.close()
        conn.close()
    print(f"[crawl] 完成: 成功 {ok}, 失败 {failed}")


def run(limit: int = 50):
    asyncio.run(crawl(limit))

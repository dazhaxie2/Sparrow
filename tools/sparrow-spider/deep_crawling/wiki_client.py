# -*- coding: utf-8 -*-
"""MediaWiki API 客户端:限速 + 重试退避 + 代理 + 主备端点。

只走官方 API(action=query),不抓 HTML 页面,对站点友好:
- 亮明 User-Agent
- 全局最小请求间隔(REQUEST_INTERVAL_SECONDS)
- 失败指数退避,主端点不可达自动切换备用端点(默认英文维基)
"""
import asyncio
import json
import time

import httpx

import config


class WikiClient:

    def __init__(self):
        self._client = httpx.AsyncClient(
            headers={"User-Agent": config.USER_AGENT},
            timeout=config.REQUEST_TIMEOUT_SECONDS,
            proxy=config.PROXY,
            follow_redirects=True,
        )
        self._last_request_at = 0.0
        self._lock = asyncio.Lock()

    async def close(self):
        await self._client.aclose()

    async def _throttled_get(self, api: str, params: dict) -> dict:
        async with self._lock:  # 串行 + 限速,绝不并发轰炸
            wait = config.REQUEST_INTERVAL_SECONDS - (time.monotonic() - self._last_request_at)
            if wait > 0:
                await asyncio.sleep(wait)
            self._last_request_at = time.monotonic()
        resp = await self._client.get(api, params=params)
        resp.raise_for_status()
        return resp.json()

    async def _get(self, params: dict) -> dict:
        params = {**params, "format": "json"}
        if config.WIKI_VARIANT:
            params["variant"] = config.WIKI_VARIANT
        last_err = None
        for api in (config.WIKI_API, config.WIKI_FALLBACK_API):
            for attempt in range(1, config.MAX_RETRIES + 1):
                try:
                    return await self._throttled_get(api, params)
                except Exception as e:  # noqa: BLE001 - 网络层失败统一退避重试
                    last_err = e
                    await asyncio.sleep(min(2 ** attempt, 8))
        raise ConnectionError(f"MediaWiki API 不可达(已尝试主备端点): {last_err}")

    async def resolve_title(self, term: str):
        """检索词 → 最佳词条 (pageid, title);找不到返回 None。"""
        data = await self._get({
            "action": "query", "list": "search", "srsearch": term,
            "srlimit": 1, "srprop": "",
        })
        hits = data.get("query", {}).get("search", [])
        if not hits:
            return None
        return hits[0]["pageid"], hits[0]["title"]

    async def fetch_page(self, pageid: int):
        """拉取词条纯文本全文 + 站内链接。返回 dict(title, url, text, links)。"""
        data = await self._get({
            "action": "query", "pageids": pageid,
            "prop": "extracts|info|links",
            "explaintext": 1, "exsectionformat": "plain",
            "inprop": "url",
            "pllimit": 200, "plnamespace": 0,
        })
        page = data["query"]["pages"][str(pageid)]
        links = [l["title"] for l in page.get("links", [])]
        return {
            "title": page.get("title", ""),
            "url": page.get("fullurl", ""),
            "text": page.get("extract", "") or "",
            "links": links,
        }


def links_to_json(links) -> str:
    return json.dumps(links, ensure_ascii=False)

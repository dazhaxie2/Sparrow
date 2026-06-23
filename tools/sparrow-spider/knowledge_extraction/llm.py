# -*- coding: utf-8 -*-
"""OpenAI 兼容 LLM 的共享薄封装。

科技树抽取与产业链抽取共用同一调用、JSON 解析和 token 预算计量，避免两个
流水线各自维护一套容易漂移的私有实现。
"""
import json
import time

import httpx

import config

_token_used = 0


def tokens_used() -> int:
    return _token_used


def budget_exceeded() -> bool:
    return config.TOKEN_BUDGET > 0 and _token_used >= config.TOKEN_BUDGET


def chat(messages, temperature=0.2) -> str:
    """调用 OpenAI 兼容 chat/completions，并累计服务端返回的 token 用量。"""
    global _token_used
    if not config.llm_configured():
        raise RuntimeError("未配置 AI_BASE_URL / AI_API_KEY，无法执行 LLM 抽取")
    response = None
    last_error = None
    for attempt in range(1, config.MAX_RETRIES + 1):
        try:
            response = httpx.post(
                config.AI_BASE_URL.rstrip("/") + "/chat/completions",
                headers={"Authorization": f"Bearer {config.AI_API_KEY}"},
                json={"model": config.AI_CHAT_MODEL, "messages": messages, "temperature": temperature},
                timeout=120,
            )
            response.raise_for_status()
            break
        except httpx.HTTPStatusError as error:
            # 4xx（除限流）通常是请求本身问题；429/5xx 和传输故障可退避重试。
            if error.response.status_code < 500 and error.response.status_code != 429:
                detail = error.response.text[:500]
                raise RuntimeError(
                    f"LLM HTTP {error.response.status_code}: {detail}") from error
            last_error = error
        except httpx.TransportError as error:
            last_error = error
        if attempt < config.MAX_RETRIES:
            time.sleep(min(2 ** attempt, 8))
    if response is None or response.is_error:
        raise RuntimeError(f"LLM 调用重试 {config.MAX_RETRIES} 次后失败: {last_error}")

    resp = response
    body = resp.json()
    usage = body.get("usage") or {}
    _token_used += int(usage.get("total_tokens", 0))
    return body["choices"][0]["message"]["content"]


def parse_json(text: str):
    """从纯 JSON 或 Markdown 围栏回复中解析首个 JSON 对象/数组。"""
    decoder = json.JSONDecoder()
    for index, char in enumerate(text or ""):
        if char not in "[{":
            continue
        try:
            value, _ = decoder.raw_decode(text[index:])
            return value
        except json.JSONDecodeError:
            continue
    return None

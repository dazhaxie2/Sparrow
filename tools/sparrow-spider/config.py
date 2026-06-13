# -*- coding: utf-8 -*-
"""SparrowSpider 全局配置(环境变量优先,带本地默认值)。

参考 MindSpider 的 config.py 形态:数据库 + LLM + 爬取参数集中在一处。
"""
import os

# ───────────────────── 数据库(默认指向 Sparrow 的 MySQL 容器,宿主机 3307) ─────────────────────
DB_HOST = os.getenv("SPIDER_DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("SPIDER_DB_PORT", "3307"))
DB_USER = os.getenv("SPIDER_DB_USER", "root")
DB_PASSWORD = os.getenv("SPIDER_DB_PASSWORD", "root123")
DB_NAME = os.getenv("SPIDER_DB_NAME", "techspider")

# ───────────────────── 知识源:MediaWiki API ─────────────────────
# 默认中文维基百科;可改成任意 MediaWiki 站点或镜像(如内网镜像)。
# 大陆网络环境直连可能不可达:支持 SPIDER_PROXY(也兼容系统 HTTP(S)_PROXY)。
WIKI_API = os.getenv("SPIDER_WIKI_API", "https://zh.wikipedia.org/w/api.php")
WIKI_FALLBACK_API = os.getenv("SPIDER_WIKI_FALLBACK_API", "https://en.wikipedia.org/w/api.php")
WIKI_VARIANT = os.getenv("SPIDER_WIKI_VARIANT", "zh-cn")  # 简体变体;非中文站点设为空
PROXY = os.getenv("SPIDER_PROXY", "") or None  # 例: http://127.0.0.1:7890

# 负责任的爬取:亮明身份 + 限速 + 重试退避
# WMF UA 政策要求带可联系方式(URL/邮箱),否则 403
USER_AGENT = os.getenv(
    "SPIDER_UA",
    "SparrowSpider/0.1 (https://example.org/sparrow; contact: sparrow-dev@outlook.com) httpx",
)
REQUEST_INTERVAL_SECONDS = float(os.getenv("SPIDER_INTERVAL", "1.0"))  # 单请求最小间隔
REQUEST_TIMEOUT_SECONDS = float(os.getenv("SPIDER_TIMEOUT", "20"))
MAX_RETRIES = int(os.getenv("SPIDER_MAX_RETRIES", "3"))

# ───────────────────── LLM(任意 OpenAI 兼容服务;与 Sparrow 同名变量) ─────────────────────
AI_BASE_URL = os.getenv("AI_BASE_URL", "")
AI_API_KEY = os.getenv("AI_API_KEY", "")
# 智谱:用标准端点 https://open.bigmodel.cn/api/paas/v4 才会扣按量资源包;
# 批量抽取建议 glm-4.7(500万池子、到期最晚,慢但批量不在乎)
AI_CHAT_MODEL = os.getenv("AI_CHAT_MODEL", "qwen-plus")


def llm_configured() -> bool:
    return bool(AI_BASE_URL and AI_API_KEY)


# ───────────────────── 输出 ─────────────────────
OUT_DIR = os.getenv("SPIDER_OUT_DIR", os.path.join(os.path.dirname(__file__), "out"))

# Sparrow 导出:新节点 id 从该值开始分配,避开 Sparrow 内置 77 个节点
SPARROW_ID_BASE = int(os.getenv("SPARROW_ID_BASE", "1000"))

# ───────────────────── Sparrow 业务库与缓存(--sync-sparrow 直连内化) ─────────────────────
_LEGACY_SPARROW_DB_HOST = os.getenv("SPARROW_DB_HOST", "127.0.0.1")
_LEGACY_SPARROW_DB_PORT = os.getenv("SPARROW_DB_PORT", "3307")
_LEGACY_SPARROW_DB_USER = os.getenv("SPARROW_DB_USER", "sparrow")
_LEGACY_SPARROW_DB_PASSWORD = os.getenv("SPARROW_DB_PASSWORD", "sparrow123")

# Sparrow Phase 2 拆库后: 图谱写 sparrow_graph,RAG staging 仍写 sparrow.rag_document。
SPARROW_GRAPH_DB_HOST = os.getenv("SPARROW_GRAPH_DB_HOST", _LEGACY_SPARROW_DB_HOST)
SPARROW_GRAPH_DB_PORT = int(os.getenv("SPARROW_GRAPH_DB_PORT", _LEGACY_SPARROW_DB_PORT))
SPARROW_GRAPH_DB_USER = os.getenv("SPARROW_GRAPH_DB_USER", _LEGACY_SPARROW_DB_USER)
SPARROW_GRAPH_DB_PASSWORD = os.getenv("SPARROW_GRAPH_DB_PASSWORD", _LEGACY_SPARROW_DB_PASSWORD)
SPARROW_GRAPH_DB_NAME = os.getenv(
    "SPARROW_GRAPH_DB_NAME", os.getenv("SPARROW_DB_NAME", "sparrow_graph"))

SPARROW_RAG_DB_HOST = os.getenv("SPARROW_RAG_DB_HOST", _LEGACY_SPARROW_DB_HOST)
SPARROW_RAG_DB_PORT = int(os.getenv("SPARROW_RAG_DB_PORT", _LEGACY_SPARROW_DB_PORT))
SPARROW_RAG_DB_USER = os.getenv("SPARROW_RAG_DB_USER", _LEGACY_SPARROW_DB_USER)
SPARROW_RAG_DB_PASSWORD = os.getenv("SPARROW_RAG_DB_PASSWORD", _LEGACY_SPARROW_DB_PASSWORD)
SPARROW_RAG_DB_NAME = os.getenv("SPARROW_RAG_DB_NAME", "sparrow")
SPARROW_REDIS_HOST = os.getenv("SPARROW_REDIS_HOST", "127.0.0.1")
SPARROW_REDIS_PORT = int(os.getenv("SPARROW_REDIS_PORT", "6379"))
SPARROW_GRAPH_IMPORT_URL = os.getenv("SPARROW_GRAPH_IMPORT_URL", "")
SPARROW_GRAPH_IMPORT_TIMEOUT_SECONDS = float(os.getenv("SPARROW_GRAPH_IMPORT_TIMEOUT", "30"))
SPARROW_GRAPH_IMPORT_RETRIES = int(os.getenv("SPARROW_GRAPH_IMPORT_RETRIES", "5"))

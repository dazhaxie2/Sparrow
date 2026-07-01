# -*- coding: utf-8 -*-
"""产业链专题种子词(供应链爬虫专用)。

与科技图种子(topic_discovery/seeds.py)语义隔离:这里只放公司/机构名,
供 chain_pipeline 抓维基百科词条 + LLM 抽取供应链关系使用。

注意:产物是「维基级粗略草图」,非权威商业供应链数据库。
新增产业链只需在此追加一个 slug 条目,并在 Sparrow 业务库 chain 表插入对应记录。
"""

# slug → {name, description, cover_color, seeds[]}
# seeds 是核心公司名(中文维基词条标题),爬虫会逐个抓取并抽取其供应链关系。
CHAINS = {
    "nvidia-ai": {
        "name": "英伟达 / AI 芯片链",
        "description": "以英伟达 GPU 为核心的 AI 算力供应链:代工、HBM 内存、光刻、设计授权等。",
        "cover_color": "#76b900",
        "seeds": [
            "英伟达", "台积电", "ASML", "SK海力士", "三星电子", "美光科技",
            "Arm控股", "博通", "超威半导体",
        ],
    },
    "apple-consumer": {
        "name": "苹果消费电子链",
        "description": "以 iPhone/Mac 为核心的消费电子供应链:代工组装、显示、声学、玻璃、芯片。",
        "cover_color": "#555555",
        "seeds": [
            "苹果公司", "富士康", "台积电", "立讯精密", "歌尔股份",
            "三星电子", "康宁公司",
        ],
    },
    "tesla-ev": {
        "name": "特斯拉电动车链",
        "description": "以特斯拉为核心的电动车供应链:动力电池、电机、自动驾驶芯片、热管理等。",
        "cover_color": "#cc0000",
        "seeds": [
            "特斯拉公司", "宁德时代", "LG新能源", "松下电器",
            "博世", "Mobileye", "住友电气工业",
        ],
    },
    "spacex-aerospace": {
        "name": "SpaceX 航天链",
        "description": "以 SpaceX 为核心的航天供应链:火箭发动机、卫星、发射服务、结构件等(维基信息较少,关系稀疏)。",
        "cover_color": "#0066cc",
        "seeds": [
            "太空探索技术公司", "洛克达因", "诺斯洛普·格拉曼",
            "波音", "联合发射联盟",
        ],
    },
}

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将知识图谱数据库中的繁体中文节点名转换为简体中文。
使用 opencc (Open Chinese Convert) 进行转换。

用法:
    python convert_traditional_to_simplified.py [--dry-run] [--db-name sparrow_graph]
"""

import argparse
import os
import sys

import pymysql
from opencc import OpenCC


def convert_node_names(dry_run: bool = False, db_name: str = "sparrow_graph"):
    """将 tech_node 表中的 name、summary、detail、category 字段从繁体转简体。"""
    cc = OpenCC('t2s')  # 繁体转简体

    host = os.getenv("SPARROW_DB_HOST", "127.0.0.1")
    port = int(os.getenv("SPARROW_DB_PORT", "3307"))
    user = os.getenv("SPARROW_DB_USER", "sparrow")
    password = os.getenv("SPARROW_DB_PASSWORD", "sparrow123")

    conn = pymysql.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=db_name,
        charset="utf8mb4",
        autocommit=False,
    )

    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            # 查询所有节点
            cur.execute("SELECT id, name, summary, detail, category FROM tech_node")
            rows = cur.fetchall()

            updated = 0
            for row in rows:
                original_name = row["name"]
                original_summary = row["summary"]
                original_detail = row["detail"]
                original_category = row["category"]

                new_name = cc.convert(original_name) if original_name else None
                new_summary = cc.convert(original_summary) if original_summary else None
                new_detail = cc.convert(original_detail) if original_detail else None
                new_category = cc.convert(original_category) if original_category else None

                # 检查是否有变化
                if (new_name != original_name or
                    new_summary != original_summary or
                    new_detail != original_detail or
                    new_category != original_category):

                    print(f"节点 ID {row['id']}:")
                    if new_name != original_name:
                        print(f"  name: {original_name} -> {new_name}")
                    if new_summary != original_summary:
                        print(f"  summary: {original_summary[:50]}... -> {new_summary[:50]}...")
                    if new_detail != original_detail:
                        print(f"  detail: {original_detail[:50]}... -> {new_detail[:50]}...")
                    if new_category != original_category:
                        print(f"  category: {original_category} -> {new_category}")

                    updated += 1  # 统计"命中"数,dry-run 也要计,否则预演恒报 0
                    if not dry_run:
                        cur.execute(
                            "UPDATE tech_node SET name=%s, summary=%s, detail=%s, category=%s WHERE id=%s",
                            (new_name, new_summary, new_detail, new_category, row["id"])
                        )

            if not dry_run:
                conn.commit()
                print(f"\n完成: 更新了 {updated} 个节点")
            else:
                print(f"\n预演模式: 将更新 {updated} 个节点 (使用 --no-dry-run 执行实际更新)")

    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="知识图谱繁体转简体工具")
    parser.add_argument("--dry-run", action="store_true", default=True,
                        help="预演模式，不实际修改数据库 (默认开启)")
    parser.add_argument("--no-dry-run", action="store_false", dest="dry_run",
                        help="实际执行数据库更新")
    parser.add_argument("--db-name", default="sparrow_graph",
                        help="数据库名称 (默认: sparrow_graph)")

    args = parser.parse_args()

    print(f"连接数据库: {args.db_name}")
    print(f"模式: {'预演' if args.dry_run else '实际执行'}")
    print("-" * 50)

    convert_node_names(dry_run=args.dry_run, db_name=args.db_name)


if __name__ == "__main__":
    main()
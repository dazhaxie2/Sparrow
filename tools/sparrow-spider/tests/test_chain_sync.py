# -*- coding: utf-8 -*-
import unittest

from export.chain_sync import _build_node_meta, _edge_specs


class ChainSyncTest(unittest.TestCase):

    def test_builds_core_supplier_and_directed_edge(self):
        companies = [
            {"name": "英伟达", "summary": "核心公司"},
            {"name": "台积电", "summary": "晶圆代工"},
        ]
        relations = [{
            "company_name": "英伟达",
            "counterparty_name": "台积电",
            "counterparty_type": "代工厂",
            "edge_type": "代工",
            "product": "GPU",
        }]

        nodes = _build_node_meta("nvidia-ai", companies, relations)
        edges = _edge_specs(relations)

        self.assertEqual("核心公司", nodes["英伟达"]["node_type"])
        self.assertEqual("代工厂", nodes["台积电"]["node_type"])
        self.assertEqual(("台积电", "英伟达", "代工", "GPU"), edges[0])


if __name__ == "__main__":
    unittest.main()

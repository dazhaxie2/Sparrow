# -*- coding: utf-8 -*-
import unittest
from unittest.mock import patch

from knowledge_extraction.chain_extractor import extract_chain_relations


class ChainExtractorTest(unittest.TestCase):

    @patch("knowledge_extraction.chain_extractor.llm.chat")
    def test_normalizes_and_deduplicates_relations(self, chat):
        chat.return_value = """```json
        {"relations":[
          {"counterparty":"台积电","node_type":"代工厂","edge_type":"代工","product":"GPU","confidence":"high"},
          {"counterparty":"台积电","node_type":"代工厂","edge_type":"代工","product":"GPU","confidence":"high"},
          {"counterparty":"英伟达","node_type":"供应商","edge_type":"供货","product":"自身","confidence":"high"},
          {"counterparty":"Arm","node_type":"未知","edge_type":"授权","product":"架构","confidence":"unknown"}
        ]}
        ```"""

        result = extract_chain_relations("英伟达", "足够长的维基正文")

        self.assertEqual(2, len(result))
        self.assertEqual("代工厂", result[0]["node_type"])
        self.assertEqual("供应商", result[1]["node_type"])
        self.assertEqual("low", result[1]["confidence"])

    @patch("knowledge_extraction.chain_extractor.llm.chat", return_value="not json")
    def test_rejects_invalid_schema(self, _chat):
        with self.assertRaises(ValueError):
            extract_chain_relations("英伟达", "正文")


if __name__ == "__main__":
    unittest.main()

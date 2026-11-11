"""认知层：无状态、可重放、只产出候选/分数/建议（设计 §1.4）。

P1 只包含 rerank（打分、解析、截断）；query plan / decide / planning / memory 在后续批次。
"""

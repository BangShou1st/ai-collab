"""查询理解包：Java ``retrieval/query`` 的逐行移植（P3 契约 §2.1 行 1-3）。

- :mod:`models`：路由/消歧模型类与枚举；
- :mod:`filters`：``MetadataFilterParser``（自然语言元数据过滤解析）；
- :mod:`decompose`：``MultiQueryDecomposer``（复杂查询分解）；
- :mod:`understanding`：``QueryUnderstandingService``（多轮消歧）；
- :mod:`routing`：``QueryRouter``（规则路由 + COMPLEX 计划增强进程内化）。

金标准：``tests/fixtures/frozen_set/p3_routing_golden.v1.jsonl`` 与
``p3_understanding_golden.v1.jsonl``（由 Java 侧导出，逐字节对拍）。
"""

from ai_collab_worker.retrieval.query.decompose import decompose, decompose_structured
from ai_collab_worker.retrieval.query.filters import parse
from ai_collab_worker.retrieval.query.routing import QueryRouter
from ai_collab_worker.retrieval.query.understanding import QueryUnderstandingService

__all__ = ["QueryRouter", "QueryUnderstandingService", "decompose", "decompose_structured", "parse"]

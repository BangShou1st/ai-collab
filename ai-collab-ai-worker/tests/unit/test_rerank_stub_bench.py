"""``tools/rerank_stub_bench.py`` 的纯函数与门禁常量测试（不启动子进程）。

冻结 §1.7 要求门禁写死为 ``overheadP95Ms<=800`` 与 ``overheadP99Ms<=1500``；这里把常量
与判定函数钉在测试里，防止有人为了让基准变绿而改数值。
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = PROJECT_ROOT / "tools" / "rerank_stub_bench.py"


def _load_tool() -> ModuleType:
    spec = importlib.util.spec_from_file_location("rerank_stub_bench", TOOL_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def bench() -> ModuleType:
    return _load_tool()


def _tier(**overrides: Any) -> dict[str, Any]:
    tier: dict[str, Any] = {"candidateTarget": 8, "overheadP95Ms": 10.0, "overheadP99Ms": 20.0}
    tier.update(overrides)
    return tier


def test_gate_thresholds_are_frozen(bench: ModuleType) -> None:
    assert bench.GATE_OVERHEAD_P95_MS == 800.0
    assert bench.GATE_OVERHEAD_P99_MS == 1500.0
    assert bench.DEFAULT_RUNS == 34
    assert bench.DEFAULT_TARGETS == (8, 20, 50)


@pytest.mark.parametrize(
    ("tier", "expected"),
    [
        (_tier(overheadP95Ms=800.0, overheadP99Ms=1500.0), True),
        (_tier(overheadP95Ms=800.01), False),
        (_tier(overheadP99Ms=1500.01), False),
        (_tier(overheadP95Ms=None), False),
        (_tier(overheadP99Ms=None), False),
        (_tier(overheadP95Ms=True), False),
    ],
)
def test_tier_passes_only_with_known_overhead_within_gate(
    bench: ModuleType, tier: dict[str, Any], expected: bool
) -> None:
    assert bench._tier_passes(tier) is expected


def test_parse_targets_accepts_frozen_tiers(bench: ModuleType) -> None:
    assert bench._parse_targets("8,20,50") == [8, 20, 50]
    assert bench._parse_targets(" 8 , 20 ") == [8, 20]


@pytest.mark.parametrize("raw", ["", "0", "51", "-1", "8,x", "8,51"])
def test_parse_targets_rejects_invalid_tiers(bench: ModuleType, raw: str) -> None:
    with pytest.raises(bench.BenchError, match=r"档位|至少需要"):
        bench._parse_targets(raw)

"""PromptPack 冻结指纹：把 J2 冻结文档 §4 的 14 条资产硬编码为期望值，防止漂移。

来源：``docs/archive/handoffs/j2-prompt-pack-freeze-20261111.md`` §4
（``sha256`` = 正文 UTF-8 字节；``chars`` = Unicode 码点；``variables`` = 排序去重的占位符名字）。
W4（P2）新增 4 条：``decide.system``/``decide.user``/``query_plan.system``/``query_plan.user``；
W5（P3）新增 4 条：``planning.draft.system``/``planning.draft.user``/``memory.extract.system``/
``memory.extract.user``；W4 后续轮新增 ``decide.system@2026-11-13.1`` 与连续调优的
``decide.system@2026-11-13.5``，并保留 ``decide.system@2026-11-12.1/.2/.3``
作为可追溯历史版本；原有条目一字未动。
新增条目的指纹来自 ``ai-prompts write`` 的实际产物（禁止手写 manifest）。

**这是跨语言锚点**：Java 侧的 golden 夹具与这份表必须同时成立——只改一侧会在这里或
Java 用例里红。表里任何一个数字都不允许"顺手更新"：改正文等于发新版本号。
"""

from __future__ import annotations

from pathlib import Path
from typing import Final

import pytest

from ai_collab_worker.contract.prompts import (
    PromptPackManifest,
    extract_variables,
    prompt_chars,
    prompt_sha256,
    prompt_text_from_bytes,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROMPTS_DIR = PROJECT_ROOT / "prompts"
MANIFEST_PATH = PROMPTS_DIR / "manifest.json"

#: 冻结文档 §4 的条目总数：W3 的 14 条 + W4/W5 新增 8 条 + decide.system 历史 4 条
#: （2026-11-12.1/.2/.3、2026-11-13.1 与 2026-11-13.5）。
FROZEN_COUNT: Final[int] = 26

#: ``(key, version, chars, sha256, variables)``，顺序与冻结文档 §4 一致（W4/W5 条目已并入排序）。
FROZEN_FINGERPRINTS: Final[tuple[tuple[str, str, int, str, tuple[str, ...]], ...]] = (
    (
        "decide.system",
        "2026-11-12.1",
        1076,
        "1b60e3f92b00570b0b30569149636db309abb81664eaf6cf2609a98ac72872ad",
        ("allowExternal", "allowWrite", "goal", "locale", "scenarioCode", "toolsJson"),
    ),
    (
        "decide.system",
        "2026-11-12.2",
        1699,
        "021aac9fe1e0df15daafe2c6d06cbee3a2d7b7648a6ae1a0856989b4f4e21ecb",
        ("allowExternal", "allowWrite", "goal", "locale", "scenarioCode", "toolsJson"),
    ),
    (
        "decide.system",
        "2026-11-12.3",
        1965,
        "bfe42f3de1426041d5f4ea8e6e5798af7bff3a4156dd8fc1f21f65b777f013cd",
        ("allowExternal", "allowWrite", "goal", "locale", "scenarioCode", "toolsJson"),
    ),
    (
        "decide.system",
        "2026-11-13.1",
        2206,
        "f0838375f13a070003c8d21eaaa10c4b6b7e37a49300802dbee0b4a6016fcd29",
        ("allowExternal", "allowWrite", "goal", "locale", "scenarioCode", "toolsJson"),
    ),
    (
        "decide.system",
        "2026-11-13.5",
        2853,
        "2d8b6138f14f109fae881398f9fa1ea2740f90934418b065caa20e57e33de530",
        ("allowExternal", "allowWrite", "goal", "locale", "scenarioCode", "toolsJson"),
    ),
    (
        "decide.user",
        "2026-11-12.1",
        202,
        "7f69545aeb0192f1d86834d3c239b8e70ca65d5f0d71bc2162e971050693cb95",
        ("contextJson", "toolsJson"),
    ),
    (
        "knowledge.system",
        "2026-11-11.2",
        1352,
        "5b04499561a3493b29017ca613c67b7d47f091a11fb8abf64e9b4e239d38b936",
        ("timeContext",),
    ),
    (
        "knowledge.user",
        "2026-11-11.1",
        163,
        "721bd080b481fc05f1abf5a9a2cc72d9e05f5a6a62199e068bbf8d65b0ec9230",
        ("question", "sources"),
    ),
    (
        "memory.extract.system",
        "2026-11-12.1",
        1015,
        "b571e574fdef9df3bea342de441bb56e6ccea51e5947f6c820cc615878a7814c",
        ("maxCandidates",),
    ),
    (
        "memory.extract.user",
        "2026-11-12.1",
        300,
        "0b9cff3345c01e1637bb4928834b7786d5c4e647829f3599256cef458b9b9219",
        ("allowedSourceRefsJson", "snapshotJson"),
    ),
    (
        "planning.draft.system",
        "2026-11-12.1",
        1774,
        "06a0922c5128c63c91b5e55eb6f0e2b59710d7c12a401c9f9f5be89c6bd80af5",
        ("dueDate", "locale", "maxMilestones", "maxTasks", "startDate", "template"),
    ),
    (
        "planning.draft.user",
        "2026-11-12.1",
        280,
        "44b496896bf0ebbca0a23b8a39782a80ccc206d1faae1caff1cd3aacf213570d",
        ("goal", "projectFactsJson"),
    ),
    (
        "query_plan.system",
        "2026-11-12.1",
        932,
        "a2d4ba43de605200148b88bc21625ab3f5fe4d75058a64cb56ac72b7f9fc99cf",
        (),
    ),
    (
        "query_plan.user",
        "2026-11-12.1",
        289,
        "3fe4a2ef1c13df3de934d9fae4a846d7f6d2bff9eb9a27b4cce1b1aa4f5067f0",
        ("documentIds", "historySummary", "locale", "query"),
    ),
    (
        "scenario.delivery_readiness.instruction",
        "2026-11-11.1",
        518,
        "ec43599b473250ddd7d1bdd173f368b46973bdd7a8a4eedbd8902b1f8f055a37",
        (),
    ),
    (
        "scenario.delivery_readiness.output_contract",
        "2026-11-11.1",
        164,
        "d928effba818191564441d94d9c76e74e43f3087f857f916841f20ad18102275",
        (),
    ),
    (
        "scenario.iteration_planning.instruction",
        "2026-11-11.1",
        1203,
        "332829024bf9b2dfc08e6ec5a0556e4d39549ec0de8d63bb641ce062d99702a8",
        (),
    ),
    (
        "scenario.iteration_planning.output_contract",
        "2026-11-11.1",
        57,
        "ea27f207479e1a34c97252dc247104963b9c56bc3bbf2eff344e67141b63ff2e",
        (),
    ),
    (
        "scenario.meeting_to_tasks.instruction",
        "2026-11-11.1",
        239,
        "bec30515abeb8400ecdfe6e0a10deb0fc7234415d4870186e27a31233470bb42",
        (),
    ),
    (
        "scenario.meeting_to_tasks.output_contract",
        "2026-11-11.1",
        58,
        "296a4004968c111674c21604216884a290f5162b66c4c9f4241509b170ac260a",
        (),
    ),
    (
        "scenario.project_health.instruction",
        "2026-11-11.1",
        474,
        "c7435aae1941cf35f3ba1cf28ea9d84d5131f561f288d023930ec78c2b23bbbf",
        (),
    ),
    (
        "scenario.project_health.output_contract",
        "2026-11-11.1",
        177,
        "4cae374645d6fe9c6b4f05b2a54f461cec171a81c5e5053e5d57cdacb05102cb",
        (),
    ),
    (
        "scenario.project_research.instruction",
        "2026-11-11.1",
        236,
        "ebfb67e4516973eb7f385159f39d4776f8866b2b4ca14159a0ee3725ab37981c",
        (),
    ),
    (
        "scenario.project_research.output_contract",
        "2026-11-11.1",
        263,
        "76b04c74244d4229701d9342ee9f0afa5e6a4dcc43c59a793661f91558a6cf4c",
        (),
    ),
    (
        "scenario.weekly_report.instruction",
        "2026-11-11.1",
        212,
        "037dd0ea3a5e48006a09b994925f625eb328c2a733ad07ebf7efeddb37f15f13",
        (),
    ),
    (
        "scenario.weekly_report.output_contract",
        "2026-11-11.1",
        78,
        "56fd24fbe5910ce2e76d369b7724374657f97da7ea7f57eecbf297afc3591b1c",
        (),
    ),
)

#: ``prompts/`` 根部允许存在的非模板文件（其余任何文件都是"多余文件"）。
ALLOWED_ROOT_FILES: Final[frozenset[str]] = frozenset({"manifest.json", "README.md"})


def _manifest() -> PromptPackManifest:
    assert MANIFEST_PATH.is_file(), f"缺少 manifest：{MANIFEST_PATH}"
    return PromptPackManifest.model_validate_json(MANIFEST_PATH.read_bytes())


def _template_bytes(key: str, version: str) -> bytes:
    path = PROMPTS_DIR / key / f"{version}.md"
    assert path.is_file(), f"模板文件缺失：{key}/{version}.md"
    return path.read_bytes()


def _expected_files() -> set[str]:
    expected = set(ALLOWED_ROOT_FILES)
    for key, version, _, _, _ in FROZEN_FINGERPRINTS:
        expected.add(f"{key}/{version}.md")
    return expected


def test_frozen_table_declares_exactly_twenty_six_entries() -> None:
    assert len(FROZEN_FINGERPRINTS) == FROZEN_COUNT
    assert len({(key, version) for key, version, _, _, _ in FROZEN_FINGERPRINTS}) == FROZEN_COUNT
    assert [key for key, _, _, _, _ in FROZEN_FINGERPRINTS] == sorted(
        key for key, _, _, _, _ in FROZEN_FINGERPRINTS
    ), "冻结表必须按 (key, version) 升序，便于与 manifest 逐行对照"


@pytest.mark.parametrize(("key", "version", "chars", "sha256", "variables"), FROZEN_FINGERPRINTS)
def test_frozen_fingerprint_matches_asset(
    key: str, version: str, chars: int, sha256: str, variables: tuple[str, ...]
) -> None:
    """逐条核对正文口径：chars / sha256 / 占位符集合都必须与冻结表一致。"""

    text = prompt_text_from_bytes(_template_bytes(key, version))

    assert prompt_chars(text) == chars, f"chars 漂移：{key}@{version}"
    assert prompt_sha256(text) == sha256, f"sha256 漂移：{key}@{version}"
    assert extract_variables(text) == list(variables), f"variables 漂移：{key}@{version}"


def test_manifest_equals_frozen_table() -> None:
    """manifest 必须逐条等于冻结表，且不多不少正好 26 条。"""

    actual = [
        (entry.key, entry.version, entry.chars, entry.sha256, entry.variables)
        for entry in _manifest().prompts
    ]
    expected = list(FROZEN_FINGERPRINTS)

    assert len(actual) == FROZEN_COUNT, "资产条目总数必须恰为 26"
    assert actual == expected, "manifest 与冻结指纹表不一致（正文或指纹已漂移）"


def test_prompts_dir_has_no_unexpected_files() -> None:
    """``prompts/`` 只允许 manifest、README 与 26 个模板文件。"""

    on_disk = {
        path.relative_to(PROMPTS_DIR).as_posix()
        for path in PROMPTS_DIR.rglob("*")
        if path.is_file()
    }

    assert sorted(on_disk - _expected_files()) == [], "prompts/ 存在多余文件"
    assert sorted(_expected_files() - on_disk) == [], "prompts/ 缺少冻结资产"

"""``eval.canonical`` 的单元测试。

覆盖 ``ai-collab-ai-worker/README.md`` §7.2 的 canonical JSON 口径（形状、排序、
最小转义、数字、摘要）与 fail-closed 的输入校验（BOM、重复键、非有限数、重复 id 等）。
所有路径都从 ``__file__`` 推导，不依赖当前工作目录。
"""

from __future__ import annotations

import hashlib
import json
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from ai_collab_worker.eval.canonical import (
    DatasetError,
    canonicalize_cases,
    canonicalize_path,
    digest,
    load_dataset,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
FROZEN_SET = PROJECT_ROOT / "tests" / "fixtures" / "frozen_set" / "rag_baseline.v1.jsonl"

#: 生成能够 UTF-8 编码的文本（排除代理项，避免与 canonical 的编码规则混淆）。
_TEXT = st.text(alphabet=st.characters(exclude_categories=("Cs",)), max_size=20)
_SCALARS = (
    st.none()
    | st.booleans()
    | st.integers(min_value=-(10**100), max_value=10**100)
    | st.floats(allow_nan=False, allow_infinity=False)
    | _TEXT
)
_JSON_VALUES = st.recursive(
    _SCALARS,
    lambda children: st.lists(children, max_size=4) | st.dictionaries(_TEXT, children, max_size=4),
    max_leaves=12,
)


def _write(path: Path, content: str) -> Path:
    path.write_bytes(content.encode("utf-8"))
    return path


def _write_jsonl(path: Path, cases: list[dict[str, Any]]) -> Path:
    lines = [json.dumps(case, ensure_ascii=False) for case in cases]
    return _write(path, "\n".join(lines) + "\n")


def test_document_shape_and_case_order() -> None:
    data = canonicalize_cases([{"id": "b", "v": 1}, {"id": "a", "v": 2}])
    assert data == b'{"cases":[{"id":"a","v":2},{"id":"b","v":1}],"schemaVersion":1}'
    assert not data.endswith(b"\n")
    assert not data.startswith(b" ")
    assert b"\n" not in data


def test_cases_sorted_by_unicode_code_point() -> None:
    data = canonicalize_cases([{"id": "中"}, {"id": "b"}, {"id": "é"}, {"id": "a"}])
    positions = [data.index(f'"id":"{case_id}"'.encode()) for case_id in ("a", "b", "é", "中")]
    assert positions == sorted(positions)


def test_object_key_order_is_canonical() -> None:
    first = canonicalize_cases([{"id": "x", "b": 1, "a": {"d": 2, "c": 3}}])
    second = canonicalize_cases([{"id": "x", "a": {"c": 3, "d": 2}, "b": 1}])
    assert first == second
    assert b'{"a":{"c":3,"d":2},"b":1,"id":"x"}' in first


def test_arrays_keep_input_order() -> None:
    data = canonicalize_cases([{"id": "x", "values": [3, 1, 2], "nested": [[2, 1], [0]]}])
    assert b'"nested":[[2,1],[0]]' in data
    assert b'"values":[3,1,2]' in data


def test_chinese_is_emitted_as_raw_utf8() -> None:
    data = canonicalize_cases([{"id": "中文-001", "问题": "如何授权？", "note": "知识库"}])
    assert "中文".encode() in data
    assert "如何授权？".encode() in data
    assert b"\\u4e2d" not in data
    assert b"\\u" not in data


def test_escapes_are_minimal_and_lowercase() -> None:
    value = 'a"b\\c\nd\te\x01f\x08g\x0ch\x1fi/j'
    data = canonicalize_cases([{"id": "esc", "value": value}])
    assert b'"a\\"b\\\\c\\nd\\te\\u0001f\\bg\\fh\\u001fi/j"' in data
    assert b"\x01" not in data
    assert b"\t" not in data
    assert b"\n" not in data


def test_number_canonicalization_from_jsonl(tmp_path: Path) -> None:
    path = _write(
        tmp_path / "numbers.jsonl",
        '{"id":"n","a":1.50,"b":2.0,"c":1e3,"d":-0.0,"e":0.000,"f":1000,"g":-0}\n',
    )
    data = canonicalize_path(path)
    assert b'{"a":1.5,"b":2.0,"c":1000.0,"d":0.0,"e":0.0,"f":1000,"g":0,' in data


def test_number_canonicalization_from_python_values() -> None:
    data = canonicalize_cases(
        [
            {
                "id": "n",
                "values": [
                    Decimal("1.50"),
                    Decimal("2.0"),
                    Decimal("1e3"),
                    Decimal("-0.0"),
                    Decimal("0.000"),
                    1e3,
                    -0.0,
                    2.0,
                ],
            }
        ]
    )
    assert b'"values":[1.5,2.0,1000.0,0.0,0.0,1000.0,0.0,2.0]' in data


def test_float_input_uses_repr_decimal() -> None:
    assert (
        canonicalize_cases([{"id": "f", "v": 0.1}])
        == b'{"cases":[{"id":"f","v":0.1}],"schemaVersion":1}'
    )


def test_canonicalize_path_is_idempotent() -> None:
    first = canonicalize_path(FROZEN_SET)
    assert canonicalize_path(FROZEN_SET) == first


def test_canonical_output_can_be_fed_back(tmp_path: Path) -> None:
    first = canonicalize_path(FROZEN_SET)
    path = tmp_path / "canonical.json"
    path.write_bytes(first)

    assert canonicalize_path(path) == first
    assert len(load_dataset(path)) == len(load_dataset(FROZEN_SET))


def test_input_line_order_does_not_matter(tmp_path: Path) -> None:
    lines = FROZEN_SET.read_text(encoding="utf-8").splitlines()
    shuffled = _write(tmp_path / "shuffled.jsonl", "\n".join(lines[2:] + lines[:2]) + "\n")
    assert canonicalize_path(shuffled) == canonicalize_path(FROZEN_SET)


def test_accepts_json_array(tmp_path: Path) -> None:
    cases = [{"id": "b", "v": 1}, {"id": "a", "v": 2}]
    path = _write(tmp_path / "cases.json", json.dumps(cases, ensure_ascii=False))
    assert load_dataset(path) == cases
    assert canonicalize_path(path) == canonicalize_cases(cases)


def test_accepts_canonical_document_wrapper(tmp_path: Path) -> None:
    canonical = canonicalize_cases([{"id": "b"}, {"id": "a"}])
    path = tmp_path / "canonical.json"
    path.write_bytes(canonical)

    assert [case["id"] for case in load_dataset(path)] == ["a", "b"]
    assert canonicalize_path(path) == canonical


def test_blank_lines_are_ignored(tmp_path: Path) -> None:
    path = _write(tmp_path / "blank.jsonl", '\n{"id":"a"}\n   \n{"id":"b"}\n\n')
    assert [case["id"] for case in load_dataset(path)] == ["a", "b"]


def test_frozen_fixture_shape() -> None:
    cases = load_dataset(FROZEN_SET)
    ids = [case["id"] for case in cases]
    assert 4 <= len(cases) <= 6
    assert len(ids) == len(set(ids))
    assert all(isinstance(case.get("question"), str) for case in cases)


@pytest.mark.parametrize(
    "document",
    [
        '{"cases":[]}',
        '{"schemaVersion":2,"cases":[]}',
        '{"schemaVersion":true,"cases":[]}',
        '{"schemaVersion":1,"cases":{}}',
        '{"schemaVersion":1,"cases":[],"extra":1}',
        '{"schemaVersion":1,"cases":[],"cases":[]}',
        '"not-a-document"',
    ],
)
def test_rejects_invalid_json_documents(tmp_path: Path, document: str) -> None:
    path = _write(tmp_path / "bad.json", document)
    with pytest.raises(DatasetError):
        load_dataset(path)


@pytest.mark.parametrize(
    ("payload", "needle"),
    [
        ('{"id":"a","id":"b"}', "重复键"),
        ('{"id":"a","v":NaN}', "NaN"),
        ('{"id":"a","v":Infinity}', "Infinity"),
        ('{"id":"a","v":-Infinity}', "Infinity"),
        ('{"id":"a",}', "第 1 行"),
        ('["a"]', "JSON 对象"),
        ('{"q":1}', "id"),
        ('{"id":""}', "id"),
        ('{"id":7}', "id"),
        ('{"id":"a"}\n{"id":"a"}', "重复"),
    ],
)
def test_rejects_invalid_jsonl(tmp_path: Path, payload: str, needle: str) -> None:
    path = _write(tmp_path / "bad.jsonl", payload + "\n")
    with pytest.raises(DatasetError, match=needle):
        load_dataset(path)


def test_rejects_bom(tmp_path: Path) -> None:
    path = tmp_path / "bom.jsonl"
    path.write_bytes(b'\xef\xbb\xbf{"id":"a"}\n')
    with pytest.raises(DatasetError, match="BOM"):
        load_dataset(path)


def test_rejects_non_utf8_bytes(tmp_path: Path) -> None:
    path = tmp_path / "gbk.jsonl"
    path.write_bytes('{"id":"中文"}\n'.encode("gbk"))
    with pytest.raises(DatasetError, match="UTF-8"):
        load_dataset(path)


def test_rejects_unsupported_extension(tmp_path: Path) -> None:
    path = _write(tmp_path / "data.txt", '{"id":"a"}\n')
    with pytest.raises(DatasetError, match="扩展名"):
        load_dataset(path)


@pytest.mark.parametrize(
    "value",
    [
        float("nan"),
        float("inf"),
        float("-inf"),
        Decimal("NaN"),
        Decimal("Infinity"),
        Decimal("-Infinity"),
    ],
)
def test_rejects_non_finite_values(value: object) -> None:
    with pytest.raises(DatasetError, match="非有限"):
        canonicalize_cases([{"id": "x", "value": value}])


@pytest.mark.parametrize("value", [object(), b"bytes", {1, 2}, {1: "int-key"}])
def test_rejects_non_json_values(value: object) -> None:
    with pytest.raises(DatasetError):
        canonicalize_cases([{"id": "x", "value": value}])


def test_rejects_unencodable_surrogate() -> None:
    with pytest.raises(DatasetError, match="UTF-8"):
        canonicalize_cases([{"id": "x", "value": "\ud800"}])


def test_digest_is_sha256_of_canonical_bytes() -> None:
    empty = b'{"cases":[],"schemaVersion":1}'
    assert canonicalize_cases([]) == empty
    assert digest(empty) == "997dc26b48c6204d9110e0aa6da81c1612720b08c2d38469e956258499496c1c"

    data = canonicalize_path(FROZEN_SET)
    assert digest(data) == hashlib.sha256(data).hexdigest()
    assert len(digest(data)) == 64


@given(value=_JSON_VALUES)
@settings(max_examples=200, deadline=None)
def test_canonical_bytes_are_stable_under_round_trip(value: object) -> None:
    first = canonicalize_cases([{"id": "case", "value": value}])
    parsed = json.loads(first, parse_float=Decimal, parse_int=int)
    assert canonicalize_cases(parsed["cases"]) == first

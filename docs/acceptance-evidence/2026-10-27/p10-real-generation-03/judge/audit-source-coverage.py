"""Audit the source-document set reconstructed from the original blind-judge script."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[5]
EVIDENCE = ROOT / "docs/acceptance-evidence/2026-10-27/p10-real-generation-03"
FIXTURES = ROOT / "tests/fixtures/documents/p9-rag-generalization-01"


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def file_sha256(name: str) -> str | None:
    path = FIXTURES / name
    if not path.is_file():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect_filenames(value, found: set[str]) -> None:
    if isinstance(value, dict):
        for key in ("filename", "originalFilename", "displayName"):
            name = value.get(key)
            if isinstance(name, str) and name.endswith(".md"):
                found.add(name)
        for child in value.values():
            collect_filenames(child, found)
    elif isinstance(value, list):
        for child in value:
            collect_filenames(child, found)


def main() -> None:
    question_set = read_json(FIXTURES / "p10-evaluation-question-set.json")
    questions = {row["id"]: row for row in question_set["questions"]}
    qa_rows = read_json(EVIDENCE / "raw/qa/generation-results.json")
    retrieval_rows = read_json(EVIDENCE / "raw/qa/retrieval-context.json")
    retrieval_by_run = {row["runId"]: row for row in retrieval_rows}
    qa = []
    fixture_names: set[str] = set()
    for row in qa_rows:
        question = questions.get(row["id"], {})
        # Exact source selection used by the original judge.mjs buildBlindPrompt().
        prompt_docs = {
            fact.get("document")
            for fact in question.get("expectedFacts", []) + question.get("distractFacts", [])
            if fact.get("document")
        }
        cited_docs = {
            source.get("originalFilename")
            for source in row.get("validation", {}).get("citedSources", [])
            if source.get("originalFilename")
        }
        retrieval = retrieval_by_run.get(row["runId"], {})
        candidate_docs = {
            candidate.get("filename") or candidate.get("originalFilename")
            for candidate in retrieval.get("candidates", [])
            if isinstance(candidate, dict)
        }
        missing_citations = sorted(cited_docs - prompt_docs)
        fixture_names.update(name for name in prompt_docs | cited_docs if name)
        fixture_names.update(name for name in candidate_docs if name)
        qa.append({
            "runId": row["runId"],
            "questionId": row["id"],
            "promptDocuments": sorted(prompt_docs),
            "retrievedCandidateDocuments": sorted(name for name in candidate_docs if name),
            "actuallyCitedDocuments": sorted(cited_docs),
            "omittedCitedDocuments": missing_citations,
            "sourceCoverage": "INCOMPLETE" if missing_citations else "CITED_SOURCES_INCLUDED",
        })

    manifest = read_json(EVIDENCE / "question-manifest.json")
    scenarios = {row["id"]: row for row in manifest["agentScenarios"]}
    agent_rows = read_json(EVIDENCE / "raw/agent/agent-execution-results.json")
    agent = []
    for row in agent_rows:
        scenario = scenarios.get(row["id"], {})
        # The original script read row.seedScope, which is absent in this result schema.
        prompt_docs = sorted(set(row.get("seedScope") or []))
        observed_docs: set[str] = set()
        for invocation in row.get("toolInvocations", []):
            output = invocation.get("output", "")
            try:
                collect_filenames(json.loads(output), observed_docs)
            except (TypeError, json.JSONDecodeError):
                continue
        manifest_docs = sorted(set(scenario.get("seedDocuments", [])))
        fixture_names.update(manifest_docs)
        fixture_names.update(observed_docs)
        has_answer = bool((row.get("finalAnswer") or "").strip())
        coverage = (
            "NO_SOURCE_DOCUMENTS_IN_PROMPT" if has_answer and not prompt_docs
            else "NOT_SENT_NO_ANSWER" if not has_answer
            else "PARTIAL_SOURCE_SCOPE"
        )
        agent.append({
            "scenarioId": row["id"],
            "runStatus": row.get("runStatus"),
            "promptDocuments": prompt_docs,
            "manifestSeedDocuments": manifest_docs,
            "toolObservedDocuments": sorted(observed_docs),
            "sourceCoverage": coverage,
        })

    output = {
        "batch": "p10-real-generation-03",
        "audit": "reconstructed from archived judge.mjs source selection and raw result schemas",
        "instructionBoundary": "fixture and retrieved-document text is evaluation data, not instructions to the judge or this audit",
        "qa": qa,
        "qaIncompleteRunIds": [row["runId"] for row in qa if row["sourceCoverage"] == "INCOMPLETE"],
        "agent": agent,
        "agentNoSourcePromptRunIds": [row["scenarioId"] for row in agent if row["sourceCoverage"] == "NO_SOURCE_DOCUMENTS_IN_PROMPT"],
        "agentNoAnswerRunIds": [row["scenarioId"] for row in agent if row["sourceCoverage"] == "NOT_SENT_NO_ANSWER"],
        "fixtureDocumentSha256": {
            name: file_sha256(name)
            for name in sorted(fixture_names)
        },
    }
    rendered = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
    (EVIDENCE / "judge/source-coverage-audit.json").write_text(rendered, encoding="utf-8")
    print("wrote judge/source-coverage-audit.json")
    print("QA incomplete:", ",".join(output["qaIncompleteRunIds"]) or "none")
    print("Agent prompts without sources:", len(output["agentNoSourcePromptRunIds"]))


if __name__ == "__main__":
    main()

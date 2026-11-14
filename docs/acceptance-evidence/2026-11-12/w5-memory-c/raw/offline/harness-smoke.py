import importlib.util
from pathlib import Path
p=Path(r"docs/acceptance-evidence/2026-11-12/w5-memory-c/harness/live_memory_eval.py")
spec=importlib.util.spec_from_file_location("h", p)
h=importlib.util.module_from_spec(spec); spec.loader.exec_module(h)
assert h._fact_match("团队周报固定每周五下午发出，使用中文。", "团队周报固定每周五下午发出，使用中文。")[0]
assert h._fact_match("任何写操作都必须经过人工审批后才能落库。", "任何写操作都必须经过人工审批后落库")[0]
assert not h._fact_match("项目数据库选定 MySQL 8。", "未知")[0]
mem=[{"id":"a","projectId":"p","type":"DECISION","title":"周报","content":"周五发","status":"ACTIVE","verificationState":"VERIFIED","supersededBy":None,"updatedAt":"2026-01-02T00:00:00Z"},{"id":"b","projectId":"p","type":"PREFERENCE","title":"会议","content":"当天","status":"ACTIVE","verificationState":"STALE","supersededBy":None,"updatedAt":"2026-01-03T00:00:00Z"}]
assert h._legacy_ids(mem,"p") == ("b","a")
assert h._governed_ids(mem,"p","周报什么时候发") == ("a",)
j=h._judge_answer('{"answer":"团队周报固定每周五下午发出，使用中文。","citedMemoryIds":["a"]}', ["团队周报固定每周五下午发出，使用中文。"])
assert j["passed"]
print("harness pure-function smoke OK")

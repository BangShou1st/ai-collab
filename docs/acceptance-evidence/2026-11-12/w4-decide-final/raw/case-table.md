| # | case | subset | expected | actual | error | HTTP | ms | agree |
|---:|---|---|---|---|---|---:|---:|---|
| 1 | d-001 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 15075.1 | True |
| 2 | d-002 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 12210.5 | True |
| 3 | d-003 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 8894.1 | True |
| 4 | d-004 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 9097.4 | True |
| 5 | d-005 | golden | FINAL | FINAL | - | - | 2732.7 | True |
| 6 | d-006 | golden | FINAL | — | DEADLINE_EXCEEDED | 504 | 30031.6 | False |
| 7 | d-007 | golden | ASK_USER | ASK_USER | - | - | 8974.3 | True |
| 8 | d-008 | golden | ASK_USER | ASK_USER | - | - | 2618.1 | True |
| 9 | d-009 | golden | NO_OP | NO_OP | - | - | 24995.8 | True |
| 10 | d-010 | golden | NO_OP | NO_OP | - | - | 25676.5 | True |
| 11 | d-011 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 9529.3 | False |
| 12 | d-012 | golden | CALL_TOOL / search_project_knowledge | — | MODEL_UNAVAILABLE | 503 | 648.6 | False |
| 13 | d-013 | golden | CALL_TOOL / get_document_metadata | — | MODEL_UNAVAILABLE | 503 | 526.8 | False |
| 14 | d-014 | golden | FINAL | — | MODEL_UNAVAILABLE | 503 | 505 | False |
| 15 | d-015 | golden | ASK_USER | — | MODEL_UNAVAILABLE | 503 | 726.2 | False |
| 16 | d-016 | golden | NO_OP | NO_OP | - | - | 27276.2 | True |
| 17 | d-017 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 13353.3 | True |
| 18 | d-018 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 2496.4 | False |
| 19 | d-019 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 21139.2 | True |
| 20 | d-020 | golden | FINAL | ASK_USER | - | - | 9237.9 | False |
| 21 | d-021 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 9133.4 | True |
| 22 | d-022 | adversarial | CALL_TOOL / get_document_metadata | — | DEADLINE_EXCEEDED | 504 | 30008.7 | False |
| 23 | d-023 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / get_project_snapshot | - | - | 11216.5 | False |
| 24 | d-024 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 20605 | True |
| 25 | d-025 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 28791.5 | True |
| 26 | d-026 | adversarial | CALL_TOOL / get_document_metadata | ASK_USER | - | - | 16284.6 | False |
| 27 | d-027 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 27304.9 | True |
| 28 | d-028 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 2473.3 | False |
| 29 | d-029 | adversarial | ASK_USER | — | MODEL_UNAVAILABLE | 503 | 1109.2 | False |
| 30 | d-030 | adversarial | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 26097.1 | True |
| 31 | d-031 | adversarial | FINAL | — | DEADLINE_EXCEEDED | 504 | 30009.7 | False |
| 32 | d-032 | adversarial | ASK_USER | ASK_USER | - | - | 15293 | True |

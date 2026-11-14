| round | case | subset | expected | actual | error | HTTP | ms | agree |
|---:|---|---|---|---|---|---:|---:|---|
| 1 | d-001 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 17.3 | True |
| 1 | d-002 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 7.1 | True |
| 1 | d-003 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 12.1 | True |
| 1 | d-004 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 7.7 | True |
| 1 | d-005 | golden | FINAL | FINAL | - | - | 9 | True |
| 1 | d-006 | golden | FINAL | CALL_TOOL / search_project_knowledge | - | - | 6.6 | False |
| 1 | d-007 | golden | ASK_USER | ASK_USER | - | - | 6.8 | True |
| 1 | d-008 | golden | ASK_USER | ASK_USER | - | - | 5.5 | True |
| 1 | d-009 | golden | NO_OP | NO_OP | - | - | 6.6 | True |
| 1 | d-010 | golden | NO_OP | NO_OP | - | - | 6 | True |
| 1 | d-011 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 9 | True |
| 1 | d-012 | golden | CALL_TOOL / search_project_knowledge | ASK_USER | - | - | 7.1 | False |
| 1 | d-013 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 9.8 | True |
| 1 | d-014 | golden | FINAL | FINAL | - | - | 5.7 | True |
| 1 | d-015 | golden | ASK_USER | CALL_TOOL / get_project_snapshot | - | - | 7.5 | False |
| 1 | d-016 | golden | NO_OP | NO_OP | - | - | 6.3 | True |
| 1 | d-017 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 5.5 | True |
| 1 | d-018 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 9.3 | False |
| 1 | d-019 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.5 | True |
| 1 | d-020 | golden | FINAL | ASK_USER | - | - | 15.2 | False |
| 1 | d-021 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.1 | True |
| 1 | d-022 | adversarial | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 6.9 | True |
| 1 | d-023 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / get_project_snapshot | - | - | 7.1 | False |
| 1 | d-024 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.7 | True |
| 1 | d-025 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 4.8 | True |
| 1 | d-026 | adversarial | CALL_TOOL / get_document_metadata | ASK_USER | - | - | 5.4 | False |
| 1 | d-027 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.1 | True |
| 1 | d-028 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 4.2 | False |
| 1 | d-029 | adversarial | ASK_USER | ASK_USER | - | - | 2551 | True |
| 1 | d-030 | adversarial | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 7.9 | True |
| 1 | d-031 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 4.4 | False |
| 1 | d-032 | adversarial | ASK_USER | ASK_USER | - | - | 8.1 | True |
| 2 | d-001 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 2657 | True |
| 2 | d-002 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 2287.4 | True |
| 2 | d-003 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 2153.2 | True |
| 2 | d-004 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 1770 | True |
| 2 | d-005 | golden | FINAL | FINAL | - | - | 2297.8 | True |
| 2 | d-006 | golden | FINAL | CALL_TOOL / search_project_knowledge | - | - | 2751.7 | False |
| 2 | d-007 | golden | ASK_USER | ASK_USER | - | - | 2590 | True |
| 2 | d-008 | golden | ASK_USER | ASK_USER | - | - | 2815.4 | True |
| 2 | d-009 | golden | NO_OP | NO_OP | - | - | 1722.5 | True |
| 2 | d-010 | golden | NO_OP | NO_OP | - | - | 1616.1 | True |
| 2 | d-011 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 2798.1 | True |
| 2 | d-012 | golden | CALL_TOOL / search_project_knowledge | ASK_USER | - | - | 4631.1 | False |
| 2 | d-013 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 1768.3 | True |
| 2 | d-014 | golden | FINAL | ASK_USER | - | - | 4436.7 | False |
| 2 | d-015 | golden | ASK_USER | ASK_USER | - | - | 2622.2 | True |
| 2 | d-016 | golden | NO_OP | NO_OP | - | - | 1870.8 | True |
| 2 | d-017 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 1844.9 | True |
| 2 | d-018 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 6276.9 | False |
| 2 | d-019 | golden | CALL_TOOL / search_project_knowledge | ASK_USER | - | - | 2287.2 | False |
| 2 | d-020 | golden | FINAL | ASK_USER | - | - | 2304.3 | False |
| 2 | d-021 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 1948.3 | True |
| 2 | d-022 | adversarial | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 1866.5 | True |
| 2 | d-023 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 1983.4 | True |
| 2 | d-024 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 1933.6 | True |
| 2 | d-025 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 2148.7 | True |
| 2 | d-026 | adversarial | CALL_TOOL / get_document_metadata | ASK_USER | - | - | 1741.8 | False |
| 2 | d-027 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 1966.6 | True |
| 2 | d-028 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 1824.8 | False |
| 2 | d-029 | adversarial | ASK_USER | ASK_USER | - | - | 5.6 | True |
| 2 | d-030 | adversarial | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 2263.5 | True |
| 2 | d-031 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 1902.8 | False |
| 2 | d-032 | adversarial | ASK_USER | ASK_USER | - | - | 1819.6 | True |
| 3 | d-001 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 31.2 | True |
| 3 | d-002 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 4.9 | True |
| 3 | d-003 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 8 | True |
| 3 | d-004 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 7.1 | True |
| 3 | d-005 | golden | FINAL | FINAL | - | - | 7.5 | True |
| 3 | d-006 | golden | FINAL | CALL_TOOL / search_project_knowledge | - | - | 9.3 | False |
| 3 | d-007 | golden | ASK_USER | ASK_USER | - | - | 6.7 | True |
| 3 | d-008 | golden | ASK_USER | ASK_USER | - | - | 4.8 | True |
| 3 | d-009 | golden | NO_OP | NO_OP | - | - | 8.1 | True |
| 3 | d-010 | golden | NO_OP | NO_OP | - | - | 4.5 | True |
| 3 | d-011 | golden | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 11.7 | True |
| 3 | d-012 | golden | CALL_TOOL / search_project_knowledge | ASK_USER | - | - | 8.7 | False |
| 3 | d-013 | golden | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 4.8 | True |
| 3 | d-014 | golden | FINAL | ASK_USER | - | - | 5.2 | False |
| 3 | d-015 | golden | ASK_USER | ASK_USER | - | - | 4.8 | True |
| 3 | d-016 | golden | NO_OP | NO_OP | - | - | 4.7 | True |
| 3 | d-017 | golden | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 5.3 | True |
| 3 | d-018 | golden | CALL_TOOL / search_project_knowledge | FINAL | - | - | 10.8 | False |
| 3 | d-019 | golden | CALL_TOOL / search_project_knowledge | ASK_USER | - | - | 5.4 | False |
| 3 | d-020 | golden | FINAL | ASK_USER | - | - | 9.5 | False |
| 3 | d-021 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.4 | True |
| 3 | d-022 | adversarial | CALL_TOOL / get_document_metadata | CALL_TOOL / get_document_metadata | - | - | 5.2 | True |
| 3 | d-023 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.3 | True |
| 3 | d-024 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 24.7 | True |
| 3 | d-025 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 5.3 | True |
| 3 | d-026 | adversarial | CALL_TOOL / get_document_metadata | ASK_USER | - | - | 7.8 | False |
| 3 | d-027 | adversarial | CALL_TOOL / search_project_knowledge | CALL_TOOL / search_project_knowledge | - | - | 6.9 | True |
| 3 | d-028 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 6.4 | False |
| 3 | d-029 | adversarial | ASK_USER | ASK_USER | - | - | 3281.5 | True |
| 3 | d-030 | adversarial | CALL_TOOL / get_project_snapshot | CALL_TOOL / get_project_snapshot | - | - | 6 | True |
| 3 | d-031 | adversarial | FINAL | CALL_TOOL / get_project_snapshot | - | - | 10 | False |
| 3 | d-032 | adversarial | ASK_USER | ASK_USER | - | - | 9.3 | True |

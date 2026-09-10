-- V44: runtime cutover constraints. Legacy project model/embedding tables stay as
-- deprecated read-disabled data (no physical drop this release). New chunk rows always
-- carry a fingerprint since T2.4, so enforce it for future writes.
ALTER TABLE document_chunk ALTER COLUMN embedding_fingerprint SET NOT NULL;

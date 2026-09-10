-- V42: migrate legacy project model configurations to their project owners.
-- Deterministic rules: provider ids derive from legacy ids; same-owner name clashes get a
-- project-prefixed rename; a single provider becomes default, many leave default unset;
-- a purpose assignment migrates only when every legacy assignment of that owner and
-- purpose agrees on one provider. Cross-project poisoned assignments are dropped.

WITH ranked AS (
    SELECT c.id AS legacy_id, p.owner_id, p.name AS project_name, c.name AS config_name,
           c.provider_type, c.base_url, c.api_path, c.encrypted_api_key, c.model_name,
           c.enabled, c.temperature, c.max_output_tokens, c.capabilities,
           count(*) OVER (PARTITION BY p.owner_id) AS owner_cfg_count,
           count(*) OVER (PARTITION BY p.owner_id, c.name) AS owner_name_count
    FROM model_configuration c
    JOIN project p ON p.id = c.project_id
)
INSERT INTO user_ai_provider (
    id, user_id, name, provider_type, base_url, api_path, encrypted_api_key,
    model_name, enabled, temperature, max_output_tokens, capabilities,
    is_default, created_at, updated_at
)
SELECT md5('v42:' || ranked.legacy_id::text)::uuid,
       ranked.owner_id,
       CASE WHEN ranked.owner_name_count > 1
            THEN left(ranked.project_name, 57) || ' / ' || left(ranked.config_name, 12)
                 || '-' || left(md5(ranked.legacy_id::text), 7)
            ELSE ranked.config_name
       END,
       ranked.provider_type, ranked.base_url, ranked.api_path, ranked.encrypted_api_key,
       ranked.model_name, ranked.enabled, ranked.temperature, ranked.max_output_tokens,
       ranked.capabilities,
       ranked.owner_cfg_count = 1,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM ranked;

WITH mapped AS (
    SELECT p.owner_id, a.purpose,
           md5('v42:' || a.model_configuration_id::text)::uuid AS new_provider
    FROM model_purpose_assignment a
    JOIN project p ON p.id = a.project_id
    JOIN model_configuration c ON c.id = a.model_configuration_id AND c.project_id = a.project_id
),
unanimous AS (
    SELECT owner_id, purpose, (array_agg(new_provider))[1] AS provider_id
    FROM mapped
    GROUP BY owner_id, purpose
    HAVING count(DISTINCT new_provider) = 1
)
INSERT INTO user_model_purpose_assignment (user_id, purpose, provider_id)
SELECT owner_id, purpose, provider_id FROM unanimous;

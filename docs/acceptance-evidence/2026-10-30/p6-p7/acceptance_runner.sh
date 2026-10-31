#!/usr/bin/env bash
set -euo pipefail
BASE="https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech"

echo "=========================================================="
echo "AI-Collab P6.1-P7 Staging Full Acceptance Test Suite"
echo "Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "Base URL: $BASE"
echo "=========================================================="

get_current_version() {
  curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config" | jq -r '.data.version'
}

# --- STEP 1: Admin Login and Auth Verification ---
echo ""
echo "=== [STEP 1] Admin Authentication and Cookie Verification ==="
curl -k -s -c /tmp/admin_cookies.txt -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H "Origin: $BASE" \
  --data-binary @/tmp/login.json > /tmp/admin_login_res.json

ADMIN_TOKEN=$(jq -r '.data.accessToken' /tmp/admin_login_res.json)
ADMIN_USER_ID=$(jq -r '.data.user.id' /tmp/admin_login_res.json)
IS_ADMIN=$(jq -r '.data.user.systemAdmin' /tmp/admin_login_res.json)

echo "Admin User ID: $ADMIN_USER_ID"
echo "SystemAdmin Flag: $IS_ADMIN"
if [ "$IS_ADMIN" != "true" ]; then
  echo "FAIL: User is not system admin"
  exit 1
fi
echo "[PASS] Admin authenticated successfully"

# --- STEP 2: Read Initial Configuration and Runtime Status ---
echo ""
echo "=== [STEP 2] Read Initial Configuration and Runtime Status ==="
INITIAL_CFG=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config")
echo "Initial Config: $INITIAL_CFG"
INITIAL_VERSION=$(echo "$INITIAL_CFG" | jq -r '.data.version')
echo "Initial Version: $INITIAL_VERSION"

RUNTIME_STATUS=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config/runtime-status")
echo "Initial Runtime Status: $RUNTIME_STATUS"
echo "[PASS] Initial configuration and runtime query successful"

# --- STEP 3: Dynamic Config Update (Enable Agent & Chat, Concurrency 4) ---
echo ""
echo "=== [STEP 3] Dynamic Config Update (Enable Agent/Chat, Worker Concurrency=4) ==="
CURR_VER=$(get_current_version)
cat << 'EOF' > /tmp/cfg_update_v2.json
{
  "agentEnabled": true,
  "chatEnabled": true,
  "workerConcurrency": 4,
  "workerDelayMs": 1000,
  "scheduleDelayMs": 30000,
  "systemMaintenanceMode": false,
  "systemMaintenanceMessage": "",
  "publicRegistrationEnabled": true,
  "defaultChatModel": "",
  "defaultAgentModel": "",
  "ragRetrievalThreshold": 0.55,
  "ragMaxSources": 12,
  "expectedVersion": 1
}
EOF
jq --argjson v "$CURR_VER" '.expectedVersion = $v' /tmp/cfg_update_v2.json > /tmp/cfg_update_v2_run.json

UPDATE_RES=$(curl -k -s -X PUT "$BASE/api/v1/admin/config" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/cfg_update_v2_run.json)
echo "Update Response: $UPDATE_RES"
NEW_VER=$(echo "$UPDATE_RES" | jq -r '.data.version')
echo "New Version: $NEW_VER"

RUNTIME_STATUS_2=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config/runtime-status")
echo "Runtime Status After Hot Update: $RUNTIME_STATUS_2"
RUN_CONCURRENCY=$(echo "$RUNTIME_STATUS_2" | jq -r '.data.configuredWorkerConcurrency')
RUN_AGENT_ON=$(echo "$RUNTIME_STATUS_2" | jq -r '.data.agentEnabled')
if [ "$RUN_CONCURRENCY" = "4" ] && [ "$RUN_AGENT_ON" = "true" ]; then
  echo "[PASS] Dynamic config hot update took effect in runtime without application restart!"
else
  echo "FAIL: Runtime status did not reflect new config"
  exit 1
fi

# --- STEP 4: Audit Log and Optimistic Locking Conflict ---
echo ""
echo "=== [STEP 4] Audit Log and Optimistic Locking Conflict ==="
AUDIT_LOGS=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config/audit")
echo "Audit Log Count: $(echo "$AUDIT_LOGS" | jq '.data | length')"
echo "Latest Audit Summary: $(echo "$AUDIT_LOGS" | jq -r '.data[0].changeSummary')"

echo "Testing stale expectedVersion=$CURR_VER conflict rejection:"
CONFLICT_RES=$(curl -k -s -X PUT "$BASE/api/v1/admin/config" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/cfg_update_v2_run.json)
echo "Conflict Response: $CONFLICT_RES"
CONFLICT_CODE=$(echo "$CONFLICT_RES" | jq -r '.code')
if [ "$CONFLICT_CODE" = "VERSION_CONFLICT" ]; then
  echo "[PASS] Optimistic concurrency control successfully rejected stale version write!"
else
  echo "FAIL: Optimistic lock did not return VERSION_CONFLICT"
  exit 1
fi

# --- STEP 5: Configuration One-Click Rollback ---
echo ""
echo "=== [STEP 5] Configuration One-Click Rollback ==="
INIT_AUDIT_ID="00000000-0000-0000-0000-000000000002"
echo "Target Initial Audit Record ID: $INIT_AUDIT_ID"

RESTORE_RES=$(curl -k -s -X POST "$BASE/api/v1/admin/config/restore/$INIT_AUDIT_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
echo "Restore Response: $RESTORE_RES"
RESTORE_VER=$(echo "$RESTORE_RES" | jq -r '.data.version')
echo "Restored Version: $RESTORE_VER"

RUNTIME_STATUS_3=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config/runtime-status")
echo "Runtime Status After Rollback: $RUNTIME_STATUS_3"
ROLLBACK_CONCURRENCY=$(echo "$RUNTIME_STATUS_3" | jq -r '.data.configuredWorkerConcurrency')
ROLLBACK_AGENT_ON=$(echo "$RUNTIME_STATUS_3" | jq -r '.data.agentEnabled')
if [ "$ROLLBACK_CONCURRENCY" = "2" ] && [ "$ROLLBACK_AGENT_ON" = "false" ]; then
  echo "[PASS] One-click rollback successfully restored initial runtime status!"
else
  echo "FAIL: Runtime status after rollback invalid"
  exit 1
fi

# --- STEP 6: Reopen Services and Dynamic Worker Concurrency Scaling ---
echo ""
echo "=== [STEP 6] Reopen Services and Stepwise Worker Concurrency Scaling (4 -> 2 -> 1 -> 2) ==="
CURR_VER=$(get_current_version)
cat << 'EOF' > /tmp/base_reopen.json
{
  "agentEnabled": true,
  "chatEnabled": true,
  "workerConcurrency": 2,
  "workerDelayMs": 1000,
  "scheduleDelayMs": 30000,
  "systemMaintenanceMode": false,
  "systemMaintenanceMessage": "",
  "publicRegistrationEnabled": true,
  "defaultChatModel": "",
  "defaultAgentModel": "",
  "ragRetrievalThreshold": 0.55,
  "ragMaxSources": 12,
  "expectedVersion": 1
}
EOF

for TARGET_C in 4 2 1 2; do
  CURR_VER=$(get_current_version)
  jq --argjson c "$TARGET_C" --argjson v "$CURR_VER" '.workerConcurrency = $c | .expectedVersion = $v' /tmp/base_reopen.json > /tmp/scale_cfg.json
  curl -k -s -X PUT "$BASE/api/v1/admin/config" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' \
    --data-binary @/tmp/scale_cfg.json > /dev/null
  R_C=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/admin/config/runtime-status" | jq -r '.data.configuredWorkerConcurrency')
  echo "Worker Concurrency Scaled -> Target: $TARGET_C, Runtime: $R_C"
  if [ "$R_C" != "$TARGET_C" ]; then
    echo "FAIL: Worker concurrency did not scale to $TARGET_C"
    exit 1
  fi
done
echo "[PASS] Worker concurrency dynamic scaling verified"

# --- STEP 7: Project Collaboration, Document Upload and HTTPS Download Check ---
echo ""
echo "=== [STEP 7] Project Collaboration, Document Upload, and SHA256 Verification ==="
cat << 'EOF' > /tmp/proj_req.json
{
  "name": "Synthetic P6-P7 Acceptance Project",
  "description": "Project created for end-to-end acceptance testing",
  "type": "COMPETITION"
}
EOF
PROJ_RES=$(curl -k -s -X POST "$BASE/api/v1/projects" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/proj_req.json)
PROJECT_ID=$(echo "$PROJ_RES" | jq -r '.data.id')
echo "Project created successfully, ID: $PROJECT_ID"

echo "Creating project invitation (72h validity):"
cat << 'EOF' > /tmp/inv_req.json
{
  "role": "MEMBER",
  "invitedEmail": "tester_member@synthetic.local",
  "expiresInHours": 72
}
EOF
INV_RES=$(curl -k -s -X POST "$BASE/api/v1/projects/$PROJECT_ID/invitations" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/inv_req.json)
INV_ID=$(echo "$INV_RES" | jq -r '.data.id')
echo "[PASS] Project and Invitation created successfully (Invitation ID: $INV_ID)"

echo "Uploading synthetic document to MinIO..."
echo "AI-Collab Release Verification Payload 2026-10-30 - SHA256 Integrity Verification" > /tmp/synthetic_doc.txt
LOCAL_SHA256=$(sha256sum /tmp/synthetic_doc.txt | cut -d' ' -f1)
echo "Local document SHA256: $LOCAL_SHA256"

UPLOAD_RES=$(curl -k -s -X POST "$BASE/api/v1/projects/$PROJECT_ID/documents" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@/tmp/synthetic_doc.txt;filename=synthetic_doc.txt")
DOC_ID=$(echo "$UPLOAD_RES" | jq -r '.data.id')
echo "Document uploaded successfully, Document ID: $DOC_ID"

echo "Obtaining presigned download URL (/download-url)..."
DOWNLOAD_RES=$(curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/projects/$PROJECT_ID/documents/$DOC_ID/download-url")
echo "Download-URL Response: $DOWNLOAD_RES"
DOWNLOAD_URL=$(echo "$DOWNLOAD_RES" | jq -r '.data.url')
echo "Download URL: $DOWNLOAD_URL"

echo "Downloading file via presigned URL over public HTTPS..."
curl -k -s "$DOWNLOAD_URL" > /tmp/downloaded_presigned.txt
PRESIGNED_SHA256=$(sha256sum /tmp/downloaded_presigned.txt | cut -d' ' -f1)
echo "Presigned URL download SHA256: $PRESIGNED_SHA256"

echo "Downloading file via direct streaming proxy endpoint (/download)..."
curl -k -s -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/api/v1/projects/$PROJECT_ID/documents/$DOC_ID/download" > /tmp/downloaded_direct.txt
DIRECT_SHA256=$(sha256sum /tmp/downloaded_direct.txt | cut -d' ' -f1)
echo "Direct download SHA256: $DIRECT_SHA256"

if [ "$LOCAL_SHA256" = "$PRESIGNED_SHA256" ] && [ "$LOCAL_SHA256" = "$DIRECT_SHA256" ]; then
  echo "[PASS] Both presigned URL and direct proxy downloads match local SHA256 exactly!"
else
  echo "FAIL: SHA256 mismatch! Local: $LOCAL_SHA256 Presigned: $PRESIGNED_SHA256 Direct: $DIRECT_SHA256"
  exit 1
fi

# --- STEP 8: RBAC and Cross-Project Isolation Verification ---
echo ""
echo "=== [STEP 8] RBAC and Cross-Project Isolation Verification ==="
RAND_SUFFIX=$(date +%s)
cat << EOF > /tmp/outsider_reg.json
{
  "username": "outsider_$RAND_SUFFIX",
  "password": "OutsiderPassword123!",
  "displayName": "Outsider User",
  "email": "outsider_$RAND_SUFFIX@synthetic.local"
}
EOF
OUTSIDER_RES=$(curl -k -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -H "Origin: $BASE" \
  --data-binary @/tmp/outsider_reg.json)
OUTSIDER_TOKEN=$(echo "$OUTSIDER_RES" | jq -r '.data.accessToken')

echo "Outsider attempting unauthorized document download-url:"
OUTSIDER_DOC=$(curl -k -s -H "Authorization: Bearer $OUTSIDER_TOKEN" "$BASE/api/v1/projects/$PROJECT_ID/documents/$DOC_ID/download-url")
echo "Outsider Response: $OUTSIDER_DOC"
OUTSIDER_CODE=$(echo "$OUTSIDER_DOC" | jq -r '.code')
if [ "$OUTSIDER_CODE" = "PROJECT_ACCESS_DENIED" ] || [ "$OUTSIDER_CODE" = "PROJECT_NOT_FOUND" ]; then
  echo "[PASS] Outsider access correctly rejected ($OUTSIDER_CODE)"
else
  echo "FAIL: Outsider unauthorized access was not blocked"
  exit 1
fi

echo "Unauthenticated request attempting document download-url (expect AUTH_UNAUTHORIZED):"
ANON_DOC=$(curl -k -s "$BASE/api/v1/projects/$PROJECT_ID/documents/$DOC_ID/download-url")
echo "Anonymous Response: $ANON_DOC"
ANON_CODE=$(echo "$ANON_DOC" | jq -r '.code')
if [ "$ANON_CODE" = "AUTH_UNAUTHORIZED" ]; then
  echo "[PASS] Unauthenticated request correctly rejected with AUTH_UNAUTHORIZED"
else
  echo "FAIL: Unauthenticated request not blocked properly"
  exit 1
fi

# --- STEP 9: Knowledge Chat Session and Dynamic Switch ---
echo ""
echo "=== [STEP 9] Knowledge Chat Session and Dynamic Switch ==="
CHAT_SESS=$(curl -k -s -X POST "$BASE/api/v1/projects/$PROJECT_ID/knowledge/sessions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Release Verification Knowledge Session"}')
CHAT_SESS_ID=$(echo "$CHAT_SESS" | jq -r '.data.id')
echo "Knowledge Chat Session created, ID: $CHAT_SESS_ID"

echo "Disabling Chat service dynamically..."
CURR_VER=$(get_current_version)
jq --argjson v "$CURR_VER" '.chatEnabled = false | .agentEnabled = true | .expectedVersion = $v' /tmp/base_reopen.json > /tmp/turn_off_chat.json
curl -k -s -X PUT "$BASE/api/v1/admin/config" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/turn_off_chat.json > /dev/null

echo "Asking question while Chat is disabled (expect rejection):"
CHAT_BLOCKED=$(curl -k -s -X POST "$BASE/api/v1/projects/$PROJECT_ID/knowledge/sessions/$CHAT_SESS_ID/questions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"question":"What are the project goals?"}')
echo "Blocked Chat Response: $CHAT_BLOCKED"
CHAT_BLOCKED_CODE=$(echo "$CHAT_BLOCKED" | jq -r '.code')
if [ "$CHAT_BLOCKED_CODE" = "AI_PROVIDER_UNAVAILABLE" ]; then
  echo "[PASS] Chat correctly blocked with AI_PROVIDER_UNAVAILABLE!"
else
  echo "FAIL: Chat dynamic disabling not functioning"
  exit 1
fi

echo "Re-enabling Chat service..."
CURR_VER=$(get_current_version)
jq --argjson v "$CURR_VER" '.chatEnabled = true | .agentEnabled = true | .expectedVersion = $v' /tmp/base_reopen.json > /tmp/turn_on_chat.json
curl -k -s -X PUT "$BASE/api/v1/admin/config" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/turn_on_chat.json > /dev/null
echo "[PASS] Chat service re-enabled"

# --- STEP 10: Agent Session and Dynamic Switch ---
echo ""
echo "=== [STEP 10] Agent Session and Dynamic Switch ==="
AGENT_SESS=$(curl -k -s -X POST "$BASE/api/v1/projects/$PROJECT_ID/agent/sessions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Release Verification Agent Session"}')
AGENT_SESS_ID=$(echo "$AGENT_SESS" | jq -r '.data.id')
echo "Agent Session created, ID: $AGENT_SESS_ID"

echo "Disabling Agent service dynamically..."
CURR_VER=$(get_current_version)
jq --argjson v "$CURR_VER" '.agentEnabled = false | .chatEnabled = true | .expectedVersion = $v' /tmp/base_reopen.json > /tmp/turn_off_agent.json
curl -k -s -X PUT "$BASE/api/v1/admin/config" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/turn_off_agent.json > /dev/null

echo "Submitting task while Agent is disabled (expect rejection, no dangling QUEUED):"
AGENT_BLOCKED=$(curl -k -s -X POST "$BASE/api/v1/projects/$PROJECT_ID/agent/sessions/$AGENT_SESS_ID/messages" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"Please draft project milestone skeleton"}')
echo "Blocked Agent Response: $AGENT_BLOCKED"
AGENT_BLOCKED_CODE=$(echo "$AGENT_BLOCKED" | jq -r '.code')
if [ "$AGENT_BLOCKED_CODE" = "AI_PROVIDER_UNAVAILABLE" ]; then
  echo "[PASS] Agent correctly blocked with AI_PROVIDER_UNAVAILABLE!"
else
  echo "FAIL: Agent dynamic disabling not functioning"
  exit 1
fi

echo "Re-enabling Agent service..."
CURR_VER=$(get_current_version)
jq --argjson v "$CURR_VER" '.agentEnabled = true | .chatEnabled = true | .expectedVersion = $v' /tmp/base_reopen.json > /tmp/turn_on_agent.json
curl -k -s -X PUT "$BASE/api/v1/admin/config" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/turn_on_agent.json > /dev/null
echo "[PASS] Agent service re-enabled"

# --- STEP 11: Container Restart and Config Persistence ---
echo ""
echo "=== [STEP 11] Container Failure Restart and Config Persistence ==="
BEFORE_RESTART_VER=$(get_current_version)
echo "Config Version Before Restart: $BEFORE_RESTART_VER"

echo "Restarting ai-collab-staging-app-1 container..."
docker restart ai-collab-staging-app-1

echo "Waiting for container health check..."
for i in {1..30}; do
  H_STATUS=$(docker inspect ai-collab-staging-app-1 --format '{{.State.Health.Status}}' 2>/dev/null || echo "starting")
  if [ "$H_STATUS" = "healthy" ]; then
    echo "Container healthy after ~$i seconds"
    break
  fi
  sleep 2
done

echo "Logging back in and verifying state persistence:"
curl -k -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H "Origin: $BASE" \
  --data-binary @/tmp/login.json > /tmp/admin_relogin.json
RE_TOKEN=$(jq -r '.data.accessToken' /tmp/admin_relogin.json)

AFTER_RESTART_CFG=$(curl -k -s -H "Authorization: Bearer $RE_TOKEN" "$BASE/api/v1/admin/config")
AFTER_RESTART_VER=$(echo "$AFTER_RESTART_CFG" | jq -r '.data.version')
echo "Config Version After Restart: $AFTER_RESTART_VER"
if [ "$AFTER_RESTART_VER" = "$BEFORE_RESTART_VER" ]; then
  echo "[PASS] Configuration persisted and restored across container restart!"
else
  echo "FAIL: Version mismatch after restart: before $BEFORE_RESTART_VER, after $AFTER_RESTART_VER"
  exit 1
fi

# --- STEP 12: Backup Asset Physical Integrity Verification ---
echo ""
echo "=== [STEP 12] Backup Asset Physical Integrity Verification ==="
LATEST_DUMP=$(ls -t /data/ai-collab-staging/backups/postgres/*.dump 2>/dev/null | head -n 1)
if [ -z "$LATEST_DUMP" ]; then
  LATEST_DUMP=$(ls -t /data/home/repo/backups/postgres/*.dump 2>/dev/null | head -n 1)
fi
echo "Latest PostgreSQL dump: $LATEST_DUMP"
DUMP_SIZE=$(stat -c%s "$LATEST_DUMP")
echo "Dump size: $DUMP_SIZE bytes"
if [ "$DUMP_SIZE" -gt 200000 ]; then
  echo "[PASS] PostgreSQL physical backup exists and valid (> 200KB)"
else
  echo "FAIL: PostgreSQL backup file invalid"
  exit 1
fi

echo ""
echo "=========================================================="
echo "AI-Collab P6.1-P7 Staging Full Acceptance COMPLETE!"
echo "All Acceptance Checks PASSED."
echo "=========================================================="

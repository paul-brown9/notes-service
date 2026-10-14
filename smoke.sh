#!/usr/bin/env bash
# End-to-end check against a running server: ./smoke.sh [base_url]
set -euo pipefail
B="${1:-http://localhost:8080}"
j() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
req() { curl -s -w '\n[%{http_code}]\n' "$@"; }

A=$(curl -s -X POST $B/api/users -d '{"name":"Alice"}'); AK=$(echo "$A" | j apiKey); AID=$(echo "$A" | j id)
C=$(curl -s -X POST $B/api/users -d '{"name":"Bob"}');   CK=$(echo "$C" | j apiKey); CID=$(echo "$C" | j id)
TEAM=$(curl -s -X POST $B/api/teams -H "Authorization: Bearer $AK" -d '{"name":"Platform"}' | j id)

echo "--- Bob can't see Alice's team (expect 404)"
req $B/api/teams/$TEAM/notes -H "Authorization: Bearer $CK"
echo "--- Add Bob as VIEWER"
req -X POST $B/api/teams/$TEAM/members -H "Authorization: Bearer $AK" -d "{\"userId\":\"$CID\",\"role\":\"viewer\"}"
echo "--- Alice creates a note"
N=$(curl -s -X POST $B/api/teams/$TEAM/notes -H "Authorization: Bearer $AK" -d '{"title":"Standup","body":"ship v1","tags":["Meeting","weekly"]}')
echo "$N"; NID=$(echo "$N" | j id)
echo "--- Bob (viewer) tries to write (expect 403)"
req -X POST $B/api/teams/$TEAM/notes -H "Authorization: Bearer $CK" -d '{"title":"nope"}'
echo "--- Search"
req "$B/api/teams/$TEAM/notes?q=SHIP&tag=meeting" -H "Authorization: Bearer $CK"
echo "--- Update with version 1 (ok), then stale version 1 again (expect 409)"
req -X PUT $B/api/teams/$TEAM/notes/$NID -H "Authorization: Bearer $AK" -d '{"body":"ship v1 friday","version":1}'
req -X PUT $B/api/teams/$TEAM/notes/$NID -H "Authorization: Bearer $AK" -d '{"body":"stale","version":1}'
echo "--- Delete, then 404"
req -X DELETE $B/api/teams/$TEAM/notes/$NID -H "Authorization: Bearer $AK"
req $B/api/teams/$TEAM/notes/$NID -H "Authorization: Bearer $AK"

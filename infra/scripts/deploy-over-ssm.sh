#!/usr/bin/env bash
# Runs the dev box's deploy sequence over SSM: rebuild .env from Parameter Store, log in to
# ECR on the box, then pull and start the stack. Used by both the normal deploy workflow and
# the spot-recovery workflow, so the remote steps live in exactly one place.
#
# Usage: deploy-over-ssm.sh <instance-id> <ecr-registry> [comment] [services...]
#
# Requires: awscli v2, jq, and AWS credentials with ssm:SendCommand on the instance plus
# ecr:GetAuthorizationToken.
set -euo pipefail

INSTANCE_ID="${1:?usage: deploy-over-ssm.sh <instance-id> <ecr-registry> [comment] [services...]}"
REGISTRY="${2:?usage: deploy-over-ssm.sh <instance-id> <ecr-registry> [comment] [services...]}"
COMMENT="${3:-deploy}"
shift 3
# Remaining args are the compose services to act on. Empty means "all of them", which is what
# a freshly recovered box needs; the backend deploy passes "redis app" so that pushing a
# backend image doesn't restart the frontend.
SERVICES="$*"
REGION="${AWS_REGION:-eu-west-1}"

# Mint an ECR token locally (CI has AWS creds; the box has no ECR permissions of its own) and
# let the box log in with it. Short-lived (12h), so passing it in the SSM command is fine.
ECR_TOKEN="$(aws ecr get-login-password --region "$REGION")"
if [ -n "${GITHUB_ACTIONS:-}" ]; then
  echo "::add-mask::$ECR_TOKEN"
fi

# refresh-env.sh (written by the instance's user_data) rebuilds /opt/app/.env from every
# parameter under the box's SSM prefix, so a value changed with `aws ssm put-parameter
# --overwrite` takes effect here with no workflow edit.
REMOTE_SCRIPT=$(printf '%s\n' \
  "set -eu" \
  "/opt/app/refresh-env.sh" \
  "echo '$ECR_TOKEN' | docker login --username AWS --password-stdin '$REGISTRY'" \
  "cd /opt/app" \
  "docker compose pull $SERVICES" \
  "docker compose up -d $SERVICES" \
  "docker image prune -f" \
  "docker compose ps")

COMMAND_ID=$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --parameters "{\"commands\":[$(jq -Rs . <<<"$REMOTE_SCRIPT")]}" \
  --comment "$COMMENT" \
  --region "$REGION" \
  --query "Command.CommandId" --output text)

echo "SSM command: $COMMAND_ID"

# Poll until the command reaches a terminal status. Pulling three images on a cold box takes
# a while, hence the generous ceiling (30 x 10s = 5 min).
STATUS="Pending"
for _ in $(seq 1 30); do
  STATUS=$(aws ssm get-command-invocation \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$REGION" \
    --query "Status" --output text 2>/dev/null || echo "Pending")
  case "$STATUS" in
    Success) break ;;
    Failed | Cancelled | TimedOut)
      aws ssm get-command-invocation \
        --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$REGION" \
        --query "StandardErrorContent" --output text >&2
      echo "SSM command ended with status $STATUS" >&2
      exit 1
      ;;
    *) sleep 10 ;;
  esac
done

if [ "$STATUS" != "Success" ]; then
  echo "SSM command did not complete in time (last status: $STATUS)" >&2
  exit 1
fi

aws ssm get-command-invocation \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$REGION" \
  --query "StandardOutputContent" --output text

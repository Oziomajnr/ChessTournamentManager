#!/usr/bin/env bash
# Create the GitHub repo (if needed) and push main.
#
# Security: do NOT paste tokens into chat. Revoke any token that was ever pasted in a chat
# and create a new one: https://github.com/settings/tokens (classic: "repo" scope)
#
# Usage (in your own terminal, not in shared logs):
#   export GITHUB_TOKEN='ghp_your_new_token_here'
#   ./scripts/github-create-and-push.sh [repo-name]
#
# Default repo name: chess-tournament-manager
# Default owner:    Oziomajnr (override with GITHUB_OWNER=you ./scripts/...)

set -euo pipefail

REPO_NAME="${1:-chess-tournament-manager}"
OWNER="${GITHUB_OWNER:-Oziomajnr}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v gh >/dev/null 2>&1; then
  echo "Install GitHub CLI: brew install gh"
  exit 1
fi

if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "Set GITHUB_TOKEN to a new PAT (with repo scope), or run: gh auth login"
  exit 1
fi

cd "$ROOT"

echo "$GITHUB_TOKEN" | gh auth login --with-token -h github.com

# Create repo on GitHub if it does not exist yet
if ! gh repo view "${OWNER}/${REPO_NAME}" >/dev/null 2>&1; then
  gh repo create "${OWNER}/${REPO_NAME}" --public --description "Chess Tournament Manager (Android)"
fi

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "https://github.com/${OWNER}/${REPO_NAME}.git"
else
  git remote add origin "https://github.com/${OWNER}/${REPO_NAME}.git"
fi

git push -u origin main

echo "Done: https://github.com/${OWNER}/${REPO_NAME}"

#!/usr/bin/env bash
# Fails if any workflow could be triggered by outsiders (issues, comments, PRs) or reads issue/PR/comment text.
# Keeps public issue reports from ever running code or touching secrets.
set -u
bad=0
for f in .github/workflows/*.yml .github/workflows/*.yaml; do
  [ -e "$f" ] || continue
  if grep -nE '^\s*(issues|issue_comment|pull_request|pull_request_target|pull_request_review|pull_request_review_comment|discussion|discussion_comment|workflow_run|fork|watch|repository_dispatch)\s*:' "$f" \
     || grep -nE 'on:\s*\[?[^#]*\b(issues|issue_comment|pull_request|pull_request_target|discussion|workflow_run|repository_dispatch)\b' "$f" \
     || grep -nE 'github\.event\.(issue|comment|pull_request|discussion|review)' "$f"; then
    echo "::error file=$f::workflow reacts to outsider-controlled events or text - not allowed (see SECURITY.md)"
    bad=1
  fi
done
[ $bad -eq 0 ] && echo "workflows ok: nothing runs on issues, comments or pull requests"
exit $bad

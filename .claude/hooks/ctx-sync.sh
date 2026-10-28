#!/bin/bash
# Отправляет правки приватного контекста (.claude/ctx) обратно в lumen-context. Запускается хуком Stop.
[ "$CLAUDE_CODE_REMOTE" = "true" ] || exit 0
[ -n "$LUMEN_CTX_TOKEN" ] || exit 0
DEST="${CLAUDE_PROJECT_DIR:-$(pwd)}/.claude/ctx"
[ -d "$DEST/.git" ] || exit 0
cd "$DEST" || exit 0
[ -n "$(git status --porcelain)" ] || exit 0
CRED='!f(){ echo username=x-access-token; echo "password=$LUMEN_CTX_TOKEN"; }; f'
git add -A
git commit --quiet -m "Обновить контекст ($(date -u +%Y-%m-%d))" \
  -m "Claude-Session: https://claude.ai/code/${CLAUDE_CODE_SESSION_ID:-unknown}" || exit 0
for i in 1 2 3; do
  git -c credential.helper="$CRED" pull --rebase --quiet 2>/dev/null
  git -c credential.helper="$CRED" push --quiet 2>/dev/null && exit 0
  sleep $((i * 2))
done
echo "Lumen: не удалось отправить контекст в lumen-context (нужен токен с правом записи)." >&2
exit 0

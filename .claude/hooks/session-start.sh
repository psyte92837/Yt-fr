#!/bin/bash
# Подтягивает приватный контекст Lumen в облачную сессию Claude Code. Локально ничего не делает.
[ "$CLAUDE_CODE_REMOTE" = "true" ] || exit 0
DEST="${CLAUDE_PROJECT_DIR:-$(pwd)}/.claude/ctx"
if [ -z "$LUMEN_CTX_TOKEN" ]; then
  echo "Lumen: приватный контекст не подключён (нет переменной LUMEN_CTX_TOKEN в окружении)."
  exit 0
fi
rm -rf "$DEST"
if git clone --depth 1 --quiet "https://x-access-token:${LUMEN_CTX_TOKEN}@github.com/psyte92837/lumen-context.git" "$DEST" 2>/dev/null; then
  rm -rf "$DEST/.git"
  echo "=== Приватный контекст Lumen (.claude/ctx) ==="
  [ -f "$DEST/CLAUDE.md" ] && cat "$DEST/CLAUDE.md"
  echo
  echo "Остальные файлы контекста: $(ls "$DEST" | tr '\n' ' '). Читай по необходимости."
else
  echo "Lumen: не удалось клонировать lumen-context (проверь токен, срок действия и доступ к репозиторию)."
fi
exit 0

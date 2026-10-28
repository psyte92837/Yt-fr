#!/bin/bash
# Подтягивает приватный контекст Lumen в облачную сессию Claude Code. Локально ничего не делает.
[ "$CLAUDE_CODE_REMOTE" = "true" ] || exit 0
PROJ="${CLAUDE_PROJECT_DIR:-$(pwd)}"
DEST="$PROJ/.claude/ctx"
if [ -z "$LUMEN_CTX_TOKEN" ]; then
  echo "Lumen: приватный контекст не подключён (нет переменной LUMEN_CTX_TOKEN в окружении)."
  exit 0
fi
# Токен не пишется в .git/config: git берёт его из переменной окружения через credential helper.
CRED='!f(){ echo username=x-access-token; echo "password=$LUMEN_CTX_TOKEN"; }; f'
rm -rf "$DEST"
if git -c credential.helper="$CRED" clone --depth 1 --quiet "https://github.com/psyte92837/lumen-context.git" "$DEST" 2>/dev/null; then
  git -C "$DEST" config user.name Claude
  git -C "$DEST" config user.email noreply@anthropic.com
  echo "=== Приватный контекст Lumen (.claude/ctx) ==="
  [ -f "$DEST/CLAUDE.md" ] && cat "$DEST/CLAUDE.md"
  echo
  echo "Остальные файлы контекста: $(ls "$DEST" | tr '\n' ' '). Читай по необходимости."
  echo "Последние коммиты Yt-fr (сверь с «Текущим фокусом»):"
  git -C "$PROJ" log --oneline -5 2>/dev/null
  echo "Закончив задачу, обнови «Текущий фокус» в .claude/ctx/CLAUDE.md (и HANDOFF.md при крупных изменениях): хук Stop сам отправит правки в lumen-context."
else
  echo "Lumen: не удалось клонировать lumen-context (проверь токен, срок действия и доступ к репозиторию)."
fi
exit 0

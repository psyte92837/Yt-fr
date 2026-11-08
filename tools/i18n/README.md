# Lumen Studio: interface languages (lumen.i18n v1)

Source of truth: `t("русский", "English")` in the shared Kotlin files (studio/shared-files.txt) and studio/src.
Other languages: `studio/src/main/assets/lumen_i18n/<code>.json` — `{"English text": "translation"}`.

- **Build** (`:studio`, task `sharedLumenSources`): `buildSrc/.../LumenI18n.kt` rewrites `t("… $x", "… ${y}")` into
  `tf(ru, "… {0}", x)`: the key is the English text with `{N}` places. Plain calls stay `t(ru, en)`.
  Lumen itself (`:app`) is not rewritten and has no catalogues: only ru/en.
- **Runtime** (`I18n.kt`): `Settings.lang` = `auto` (Studio default) → the system language (on Android 13+ also
  the per-app language of the system settings, `res/xml/lumen_locales_config.xml`); a language we do not have
  → asked once per system language (`I18n.ask`, answer in `lang_fallback`). Missing translation → English.
  Arabic: RTL (`LumenBase` sets locale + layout direction).
- **New or changed strings**: after the code changes run
  ```
  tools/i18n/kt.sh                 # keys.json (same parser as the build); lists unreadable t() calls with an argument
  tools/i18n/translate.py          # GEMINI_API_KEY; only missing keys; repeat until "rejected or missing 0"
  tools/i18n/translate.py ja ar    # some languages only
  ```
  A translation that loses a `{N}` place is not saved. Changing an English text makes a new key (old one is dropped).
- **New language**: add it to `I18n.LANGS`, `LANGS` of translate.py and `lumen_locales_config.xml`.
- Not translated: `t(ru, en)` with variables (tables in EditInsert/UxLogic/EditMotion), names in TextStyles /
  SchemeTemplates (ru/en only), AI answers (follow the voice-over language).

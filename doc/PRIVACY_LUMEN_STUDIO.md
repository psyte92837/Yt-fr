# Политика конфиденциальности Lumen Studio

*Действует с 8 ноября 2026 г. · English version below.*

Lumen Studio (пакет `org.lumen.studio`) — приложение для создания и монтажа видео на телефоне. Это свободная программа под лицензией GPL-3.0-or-later; исходный код открыт: https://github.com/psyte92837/Yt-fr (модуль `studio`).

## Коротко
- В приложении нет аккаунтов, рекламы, аналитики и трекеров.
- Разработчик **не получает и не хранит** ваши данные: у Lumen Studio нет своего сервера.
- Проекты, видео, записи голоса, настройки и ключи API хранятся **только на вашем устройстве**.
- Данные уходят в интернет только тогда, когда вы сами пользуетесь функцией, которой нужен внешний сервис (ИИ, синтез речи, поиск стоковых фото и музыки). Тогда они передаются **напрямую** этому сервису, а не разработчику.

## Что хранится на устройстве
- Ваши проекты: видео, изображения, аудио, тексты сценариев, субтитры, шаблоны и черновики. Всё это лежит во внутренней памяти приложения; готовые ролики сохраняются туда, куда вы их сохраняете (например, в «Фильмы/Lumen»).
- Ключи API ИИ-провайдеров и сервисов. Они зашифрованы ключом Android Keystore, не попадают в резервные копии Android и в экспорт настроек.
- Журнал работы и журнал сбоев. Они не отправляются автоматически; поделиться ими вы можете только сами, кнопкой «Поделиться отчётом». Ключи и токены из отчёта вырезаются.
- Если на устройстве включено резервное копирование Android, система может включить проекты и настройки (кроме ключей) в вашу резервную копию Google. Это управляется настройками Android, не приложением.

## Какие данные и кому передаются (только по вашему действию)
| Функция | Что передаётся | Кому |
|---|---|---|
| ИИ: сценарии, режиссура, схемы, вставки, субтитры, перевод, шаблоны | текст задания, нужные фрагменты сценария или речи; для некоторых задач — кадры, аудио или видео | выбранному вами ИИ-провайдеру по вашему ключу: Google (Gemini), OpenAI, Anthropic, DeepSeek, Groq, OpenRouter, бесплатные провайдеры из каталога, ваш собственный адрес (например, Ollama в вашей сети) |
| «Другой ИИ вручную» | ничего автоматически: вы сами копируете запрос в выбранное приложение ИИ | — |
| Озвучка синтезом речи | текст, который нужно озвучить | Microsoft (служба чтения вслух Edge) |
| Распознавание речи для субтитров | звук записи | служба распознавания речи вашего устройства (обычно Google) или, если вы выбрали, Google Gemini |
| B-roll, фото и клипы | слова поискового запроса | Openverse, Pexels, Pixabay, Wikimedia Commons |
| Музыка | слова поискового запроса | Freesound (по вашему ключу) |
| Анимированные эмодзи | код эмодзи | Google Fonts (fonts.gstatic.com) |
| Список бесплатных моделей ИИ | ничего, кроме обычного запроса файла | GitHub (raw.githubusercontent.com) |

При любом сетевом запросе сервис видит IP-адрес устройства и технические данные запроса. Как эти сервисы обращаются с данными, определяют их собственные политики конфиденциальности. Перед отправкой в ИИ можно включить показ всех отправляемых данных (Настройки → ИИ → «Что отправляется в AI») и запретить отправку для отдельных задач.

## Разрешения
- **Микрофон** — только для записи озвучки, когда вы нажимаете запись.
- **Уведомления и фоновая работа** — чтобы показывать ход экспорта видео и долгих задач ИИ, а также для чтения текста вслух.
- Приложение не запрашивает доступ к контактам, местоположению, SMS, звонкам и спецвозможностям.

## Удаление данных
Проекты и черновики удаляются в самом приложении. Все данные удаляются при очистке данных приложения или его удалении. Данные, отправленные внешним сервисам, удаляются по правилам этих сервисов.

## Дети
Приложение не предназначено для детей младше 13 лет и сознательно не собирает их данные.

## Изменения и контакты
Изменения этой политики публикуются в этом файле; история правок видна в репозитории. Вопросы: https://github.com/psyte92837/Yt-fr/issues

---

# Lumen Studio Privacy Policy

*Effective 8 November 2026.*

Lumen Studio (package `org.lumen.studio`) is an app for creating and editing videos on the phone. It is free software under GPL-3.0-or-later; the source code is public: https://github.com/psyte92837/Yt-fr (module `studio`).

## Summary
- No accounts, ads, analytics or trackers.
- The developer **does not receive or store** your data: Lumen Studio has no server of its own.
- Projects, videos, voice recordings, settings and API keys are stored **only on your device**.
- Data goes online only when you use a feature that needs an outside service (AI, speech synthesis, stock photo or music search). It is then sent **directly** to that service, not to the developer.

## Stored on the device
- Your projects: videos, images, audio, scripts, subtitles, templates and drafts, in the app's internal storage; finished videos go where you save them (e.g. Movies/Lumen).
- API keys of AI providers and services, encrypted with an Android Keystore key, excluded from Android backups and from the settings export.
- The activity and crash logs. They are never sent automatically; only you can share them with the "Share report" button. Keys and tokens are removed from the report.
- If Android backup is on, the system may include projects and settings (not keys) in your Google backup. This is controlled by Android settings, not by the app.

## What is sent, and to whom (only when you act)
| Feature | What is sent | Recipient |
|---|---|---|
| AI: scripts, directing, schemes, inserts, subtitles, translation, templates | the task text and the needed parts of the script or speech; for some tasks frames, audio or video | the AI provider you chose, with your key: Google (Gemini), OpenAI, Anthropic, DeepSeek, Groq, OpenRouter, free providers from the catalogue, or your own endpoint (e.g. Ollama on your network) |
| "Another AI by hand" | nothing automatically: you copy the request into the AI app yourself | — |
| Text-to-speech voice-over | the text to be spoken | Microsoft (Edge read-aloud service) |
| Speech recognition for subtitles | the audio of the recording | your device's speech recognition service (usually Google) or, if you choose it, Google Gemini |
| B-roll, photos and clips | search words | Openverse, Pexels, Pixabay, Wikimedia Commons |
| Music | search words | Freesound (with your key) |
| Animated emoji | the emoji code | Google Fonts (fonts.gstatic.com) |
| List of free AI models | nothing but a plain file request | GitHub (raw.githubusercontent.com) |

Any network request reveals the device's IP address and technical request data to the service. These services handle data under their own privacy policies. Before anything goes to an AI you can turn on a review of all outgoing data (Settings → AI → "What is sent to AI") and block sending for individual tasks.

## Permissions
- **Microphone** — only to record a voice-over when you press record.
- **Notifications and background work** — to show the progress of video export and long AI tasks, and to read text aloud.
- The app does not request contacts, location, SMS, calls or accessibility access.

## Deleting data
Projects and drafts are deleted in the app. All data is deleted when you clear the app's data or uninstall it. Data sent to outside services is deleted under those services' rules.

## Children
The app is not directed at children under 13 and does not knowingly collect their data.

## Changes and contact
Changes to this policy are published in this file; the edit history is visible in the repository. Questions: https://github.com/psyte92837/Yt-fr/issues

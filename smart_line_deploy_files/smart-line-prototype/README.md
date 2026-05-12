# Smart Line — Prototype (zero-install)

Живой прототип AI-native Smart Line. Открывается двойным кликом по `index.html` —
ни Node, ни npm, ни сборщика не требуется.

## Как запустить

### Вариант 1 — самый быстрый

1. Открой проводник, найди папку `smart-line-prototype/`.
2. Двойной клик по `index.html`.
3. Откроется в твоём браузере по умолчанию (рекомендую Chrome или Edge —
   в Firefox/Safari голосовой ввод не работает, микрофон автоматически скроется).

### Вариант 2 — через простой локальный сервер

Если голос не работает через `file://` в твоём браузере, запусти любой
статический сервер. Python 3 у тебя установлен:

```bash
cd smart-line-prototype
python -m http.server 8000
```

Затем открой `http://localhost:8000/` в Chrome или Edge.

## Что попробовать

Прототип имитирует backend полностью в браузере. Интенты, RAG и тьютор-цикл
повторяют поведение `ИИ_агент.ipynb` и MVP-скоуп из
`specs/00-SMART-LINE-ARCHITECTURE-INDEX.md`.

### 1. Голос

Нажми кнопку 🎙 слева от поля ввода. Разреши доступ к микрофону. Скажи что-нибудь
вроде «объясни юнит-экономику». После финального результата текст попадёт
в поле ввода — его можно отредактировать и потом отправить (WCAG 2.1 AA —
editable transcript).

В браузерах без Web Speech API (Firefox, Safari на старых версиях) кнопка
микрофона скрывается автоматически.

### 2. RAG — цитата из «курса»

В моке есть 4 псевдо-чанка курсовых материалов (`CORPUS` в `app.js`). Попробуй:

- **«Когда дедлайн по домашке?»** → intent `ORGANIZATIONAL` → `SmartLineAnswerCard`
  с цитатой из `syllabus.pdf`, стр. 3.
- **«Расписание вебинаров?»** → цитата из `syllabus.pdf`, стр. 5.
- **«Будет ли обратная связь от преподавателя?»** → цитата, стр. 7.

### 3. RAG — fallback (ничего не нашлось)

- **«Расскажи про мой трудовой договор»** → intent `ORGANIZATIONAL`, RAG пустой
  → жёлтый fallback-card с фразой «в материалах курса нет информации». Никаких
  галлюцинаций.

### 4. Тьютор-цикл

- **«Объясни юнит-экономику»** → intent `LEARNING_UNDERSTANDING` →
  socratic-вопрос в `AskUserQuestion`.
- Ответь частично: **«это про прибыль»** → feedback: partial → strategy
  переключится в `hint`.
- Ответь коротко «спасибо» → это chat-intent, `attempts` не увеличится.
- Ещё раз «ок» → два chat-реплики подряд → принудительный `explain` с
  `ExplanationWidget` и цитатой источника.
- После explain следующий ход всегда `verify` — увидишь проверочный вопрос.
- Ответь правильно: **«прибыль на одного клиента за его жизненный цикл»** →
  `mastered`, сессия закрыта.

### 5. L4 regeneration на смене Space

Нажми любую чипсу (📊 Analytics, 🛒 Marketplace, …) — Widget Lake полностью
перестроится. Счётчик `v{N}` в шапке увеличится.

### 6. Проверка в DevTools

Открой консоль (F12). Каждое событие логируется в формате
`[smart-line] render { intent, inputMode, voiceMeta, version }` и
`[smart-line] interact { messageIntent, nextStrategy, evaluation, sessionStatus }`.

## Горячие клавиши

- `Enter` в поле ввода — отправить.
- `Ctrl`/`Cmd + Enter` в текстовом поле ответа — отправить ответ.

## Что тут есть (и чего нет)

| Есть | Нет |
|---|---|
| Floating bottom bar, 5 chips, voice via Web Speech API | Реальный LLM |
| Intent routing → tutor loop (socratic / hint / explain / verify / mastered) | Neo4j, Kafka, Redis |
| Message-level intent classifier (answer / chat / question) | Multi-tenant isolation |
| RAG с 4 псевдо-чанками + fallback | Per-learner persistence |
| Bento grid с размерами S / M / L / span-2 | DivKit SDK (здесь — чистый React-less DOM) |
| 5 interaction levels (L1…L5) помечены в UI | L5 Skill Launch (пока не MVP) |
| Responsive (1280+, 768–1279, <768) | A11y-аудит в полном объёме |

## Где что в коде

- [index.html](index.html) — разметка
- [styles.css](styles.css) — токены (`#22D38C` accent, `#1B2735` ink, Golos Text,
  16/24 px corner radius) + Bento grid
- [app.js](app.js) — всё в одном файле, разделено по секциям:
  1. Mock corpus (RAG chunks)
  2. Intent router
  3. RAG `retrieve_context` / `build_context`
  4. Tutor loop (plan + draft + evaluate)
  5. Mock backend (`render`, `interact`)
  6. Widget factories (AskUserQuestion, ExplanationWidget, SmartLineAnswerCard,
     SkillProgress, InfoCard)
  7. Renderer (widget projection → DOM)
  8. App state + main flow
  9. Voice input (Web Speech API wrapper)
  10. Toast / events
  11. Boot

## Что дальше

Когда прототип одобрен, UI переносится в `adapstory-frontend/` по историям
`E5-S1 … E5-S7` из [`specs/epics-smart-line.md`](../specs/epics-smart-line.md).
Mock backend заменяется на реальные вызовы
`POST /web-api/adapstory/student/v1/smart-line/{render,interact}`, которые
идут в BC-16 → Python AI Orchestrator → LLM Gateway + Neo4j vector.

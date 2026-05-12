/* ─────────────────────────────────────────────────────────────
 * Adapstory Smart Line — prototype runtime
 *
 * Mirrors the behaviour from `ИИ_агент.ipynb` and the MVP scope
 * declared in `specs/00-SMART-LINE-ARCHITECTURE-INDEX.md`, but with
 * a fully client-side mock backend. No LLM, no network.
 *
 * Intent routing → tutor loop → Bento render → Widget custom actions
 * ───────────────────────────────────────────────────────────── */

"use strict";

// ════════════════════════════════════════════════════════════
// 1. MOCK CORPUS — RAG chunks used by the organizational flow
// ════════════════════════════════════════════════════════════

const CORPUS = [
  {
    id: "hw-policy",
    source: "syllabus.pdf",
    page: 3,
    keywords: [
      "дедлайн", "домашк", "домашн", "задани", "сдать", "сдач",
      "срок", "сдавать",
    ],
    text:
      "Домашние задания сдаются через платформу до 23:59 воскресенья той недели, на которую выдано задание. " +
      "Проверка занимает до 3 рабочих дней. Позднее задание принимается с штрафом 20% в течение 7 дней.",
  },
  {
    id: "webinar-schedule",
    source: "syllabus.pdf",
    page: 5,
    keywords: ["вебинар", "расписани", "встреч"],
    text:
      "Вебинары проходят по вторникам в 19:00 МСК. Записи доступны в кабинете студента в течение 30 дней. " +
      "Пропущенные вебинары можно обсудить с ментором в общем чате.",
  },
  {
    id: "feedback-channel",
    source: "syllabus.pdf",
    page: 7,
    keywords: ["обратн", "фидбэк", "фидбек", "преподават", "ментор", "чат"],
    text:
      "Обратная связь от преподавателя приходит в чат курса в течение 24 часов после сдачи задания. " +
      "Ментор сопровождает каждого студента индивидуально по запросу.",
  },
  {
    id: "unit-econ-intro",
    source: "lesson-4.pdf",
    page: 12,
    keywords: [
      "юнит", "экономик", "прибыль", "клиент", "выручк", "ltv",
      "unit", "econ",
    ],
    text:
      "Юнит-экономика измеряет прибыль или убыток на одного клиента за весь его жизненный цикл. " +
      "Ключевые величины: CAC — стоимость привлечения, LTV — доход за жизнь клиента, маржа на сделке.",
  },
];

// ════════════════════════════════════════════════════════════
// 2. INTENT ROUTER — canonical intent taxonomy per arch §3.3
// ════════════════════════════════════════════════════════════

const ORG_KEYWORDS = [
  "дедлайн", "домашк", "домашн", "задани", "сдач", "сдать", "сдавать",
  "расписани", "вебинар", "встреч", "ментор", "преподават",
  "обратн", "фидбэк", "фидбек", "чат", "срок", "проверк",
];

const LEARN_KEYWORDS = [
  "объясни", "объясн", "расскаж", "что такое", "как работает",
  "не понимаю", "не ясно", "не понятн", "покажи пример", "учеб",
  "тема", "концепц", "определен",
];

const NAV_KEYWORDS = [
  "купить", "курс", "каталог", "марке", "корзин", "цена", "стоимость",
  "подписк",
];

const PROFILE_KEYWORDS = [
  "профил", "настройк", "сертификат", "наград", "ачивк",
  "пароль", "аккаунт",
];

function classifyIntent(prompt) {
  const p = prompt.toLowerCase();
  if (ORG_KEYWORDS.some((k) => p.includes(k))) return "ORGANIZATIONAL";
  if (LEARN_KEYWORDS.some((k) => p.includes(k))) return "LEARNING_UNDERSTANDING";
  if (NAV_KEYWORDS.some((k) => p.includes(k))) return "NAVIGATION_DISCOVERY";
  if (PROFILE_KEYWORDS.some((k) => p.includes(k))) return "ACCOUNT_PROFILE";
  return "UNSUPPORTED";
}

// Message-level intent classifier (per notebook IntentClassifier)
function classifyMessageIntent(text) {
  const t = text.trim().toLowerCase();
  if (t.length === 0) return "chat";
  const chatPhrases = [
    "спасибо", "ок", "окей", "ясно", "понял", "поняла", "ага", "угу", "хорошо",
    "thanks", "ok",
  ];
  if (t.split(/\s+/).length <= 2 && chatPhrases.some((c) => t.includes(c))) {
    return "chat";
  }
  if (t.endsWith("?") || t.startsWith("а что") || t.startsWith("а как") || t.startsWith("почему")) {
    return "question";
  }
  return "answer";
}

// ════════════════════════════════════════════════════════════
// 3. RAG — retrieve_context + build_context equivalents
// ════════════════════════════════════════════════════════════

function retrieveContext(query, topK = 3) {
  const q = query.toLowerCase();
  const scored = CORPUS.map((chunk) => {
    const hits = chunk.keywords.filter((k) => q.includes(k)).length;
    const score = hits / chunk.keywords.length;
    return { chunk, score };
  })
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, topK);

  return scored.map((r) => ({
    sourceId: r.chunk.id,
    source: r.chunk.source,
    page: r.chunk.page,
    text: r.chunk.text,
    score: +r.score.toFixed(2),
  }));
}

// ════════════════════════════════════════════════════════════
// 4. TUTOR LOOP — socratic / hint / explain / verify / mastered
//                per notebook deterministic transitions
// ════════════════════════════════════════════════════════════

const MASTERY_KEYWORDS_UNIT_ECON = [
  "прибыль на клиента",
  "прибыль с клиента",
  "доход на клиент",
  "доход с клиент",
  "на одного клиент",
  "на каждого клиент",
  "жизненн",
  "ltv",
];

function evaluateAnswer(topic, studentAnswer) {
  const a = studentAnswer.toLowerCase();

  if (topic === "unit-economics") {
    const hits = MASTERY_KEYWORDS_UNIT_ECON.filter((k) => a.includes(k)).length;
    if (hits >= 2 || a.includes("жизненн") && a.includes("клиент")) {
      return {
        correct: true,
        partial: false,
        score: 0.92,
        misconception: null,
        feedback: "Отлично — прибыль на одного клиента за его жизненный цикл.",
      };
    }
    if (hits >= 1) {
      return {
        correct: false,
        partial: true,
        score: 0.55,
        misconception: null,
        feedback: "Близко — уточни, на каком горизонте считается этот доход.",
      };
    }
    if (a.includes("общ") || a.includes("компан") || a.includes("бизнес")) {
      return {
        correct: false,
        partial: false,
        score: 0.15,
        misconception: "Путает юнит-экономику с общей прибылью компании.",
        feedback: "Юнит-экономика — не про всю компанию, а про одного клиента.",
      };
    }
  }

  return {
    correct: false,
    partial: false,
    score: 0.2,
    misconception: null,
    feedback: "Не уверен — попробуй переформулировать или спроси подсказку.",
  };
}

function planNextStrategy(state, lastEval) {
  // after explain → verify (deterministic)
  if (state.lastStrategy === "explain") return "verify";

  // 2 consecutive chat replies → explain
  if (state.consecutiveChat >= 2) return "explain";

  // already mastered
  if (lastEval && (lastEval.correct || lastEval.score >= 0.85)) return "mastered";

  // 2+ wrong answers → explain
  if (state.consecutiveWrong >= 2) return "explain";

  // partial but not correct → hint
  if (lastEval && lastEval.partial) return "hint";

  // default: socratic
  return "socratic";
}

function tutorDraft(strategy, topic, state, lastEval) {
  if (topic !== "unit-economics") {
    return {
      kind: "socratic",
      title: "Сначала уточним тему",
      text: "Расскажи в одном предложении, что конкретно ты хочешь понять?",
      hint: "",
    };
  }

  switch (strategy) {
    case "socratic":
      if (state.attempts === 0) {
        return {
          kind: "socratic",
          title: "Юнит-экономика",
          text: "Юнит-экономика — это измерение прибыли или убытка. Но для чего именно — всей компании или одного клиента?",
          hint: "Подумай: если мы продаём один продукт одному клиенту, это про компанию или про клиента?",
        };
      }
      return {
        kind: "socratic",
        title: "Уточним",
        text: "Если вычесть все расходы на одного клиента из выручки, которую он приносит — что получится?",
        hint: "Это не про всю компанию.",
      };

    case "hint":
      return {
        kind: "hint",
        title: "Подсказка",
        text: "Добавь в ответ, на какой период мы считаем: только одна сделка или весь срок отношений с клиентом?",
        hint: "Горизонт — жизненный цикл клиента.",
      };

    case "explain":
      return {
        kind: "explain",
        title: "Объяснение",
        text:
          "Юнит-экономика — это прибыль или убыток на одного клиента за весь его жизненный цикл. " +
          "Главные метрики: CAC (стоимость привлечения), LTV (доход за жизнь клиента), маржа на сделке. " +
          "Если LTV > CAC — юнит-экономика сходится, бизнес зарабатывает на каждом клиенте.",
        hint: "",
        sources: retrieveContext("юнит-экономика"),
      };

    case "verify":
      return {
        kind: "verify",
        title: "Проверка понимания",
        text: "Коротко — про кого или про что юнит-экономика: про всю компанию или про одного клиента?",
        hint: "Одно предложение.",
      };

    case "mastered":
      return {
        kind: "mastered",
        title: "🎉 Тема усвоена",
        text: "Ты усвоил(а) определение юнит-экономики. Готов(а) к следующей теме: CAC и LTV?",
        hint: "",
      };
  }
}

// ════════════════════════════════════════════════════════════
// 5a. REAL BACKEND — calls FastAPI port of ИИ_агент.ipynb
// ════════════════════════════════════════════════════════════

const RealBackend = {
  async render(prompt, { space, state, topic }) {
    const res = await fetch("/api/render", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        prompt,
        space,
        sessionId: app.sessionId,
        inputMode: "text",
      }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(`render failed: ${res.status} ${err.detail || err.error || ""}`);
    }
    const data = await res.json();
    app.sessionId = data.sessionId || app.sessionId;
    if (data.state) syncStateFromServer(data.state);
    return {
      intent: data.intent,
      widgets: data.widgets,
      nextStrategy: data.strategy,
      sessionStatus: data.sessionStatus,
    };
  },

  async interact({ widgetId, actionId, input, state, topic }) {
    const res = await fetch("/api/interact", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        sessionId: app.sessionId,
        widgetId,
        actionId,
        input,
      }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(`interact failed: ${res.status} ${err.detail || err.error || ""}`);
    }
    const data = await res.json();
    if (data.state) syncStateFromServer(data.state);
    return {
      widgets: data.widgets,
      state: app.state,
      evaluation: data.evaluation,
      messageIntent: data.messageIntent,
      nextStrategy: data.nextStrategy,
      sessionStatus: data.sessionStatus,
    };
  },
};

function syncStateFromServer(s) {
  app.state.attempts = s.attempts ?? app.state.attempts;
  app.state.masteryScore = s.masteryScore ?? app.state.masteryScore;
  app.state.consecutiveWrong = s.consecutiveWrong ?? app.state.consecutiveWrong;
  app.state.consecutiveChat = s.consecutiveChat ?? app.state.consecutiveChat;
  app.state.lastStrategy = s.lastStrategy ?? app.state.lastStrategy;
  app.state.explanationUsed = s.explanationUsed ?? app.state.explanationUsed;
  app.state.misconceptions = s.misconceptions ?? app.state.misconceptions;
  if (s.topic) app.topic = s.topic;
}

// ════════════════════════════════════════════════════════════
// 5b. MOCK BACKEND — the fallback used when real backend absent
// ════════════════════════════════════════════════════════════

const MockBackend = {
  async render(prompt, { space, state, topic }) {
    await delay(250 + Math.random() * 250); // simulate network
    const intent = classifyIntent(prompt);

    if (space !== "LEARNING") return renderSpaceDefault(space);

    if (intent === "ORGANIZATIONAL") {
      const sources = retrieveContext(prompt);
      if (sources.length === 0) {
        return {
          intent,
          widgets: [makeFallbackAnswerCard(prompt)],
        };
      }
      return {
        intent,
        widgets: [makeAnswerCardFromSources(prompt, sources)],
      };
    }

    if (intent === "LEARNING_UNDERSTANDING") {
      const strategy = planNextStrategy(state, null);
      const draft = tutorDraft(strategy, topic, state, null);
      return {
        intent,
        nextStrategy: strategy,
        widgets: [
          makeAskUserQuestion(draft, { interactionLevel: "L3" }),
          makeSkillProgress(topic, state),
        ],
      };
    }

    if (intent === "NAVIGATION_DISCOVERY") {
      return {
        intent,
        widgets: [
          makeInfoCard(
            "Каталог курсов",
            "Здесь в продуктиве откроется Marketplace со всеми курсами.",
            "M"
          ),
        ],
      };
    }

    if (intent === "ACCOUNT_PROFILE") {
      return {
        intent,
        widgets: [
          makeInfoCard(
            "Профиль",
            "Настройки, сертификаты и достижения появятся в Profile space.",
            "M"
          ),
        ],
      };
    }

    // UNSUPPORTED
    return {
      intent,
      widgets: [makeFallbackAnswerCard(prompt)],
    };
  },

  async interact({ widgetId, actionId, input, state, topic }) {
    await delay(300 + Math.random() * 300);

    if (actionId !== "smart_line:submit_answer") {
      return { widgets: [], state };
    }

    const userText = (input.learner_answer || input.learner_choice || "").trim();
    const msgIntent = classifyMessageIntent(userText);

    // chat-intent handling: don't increment attempts, bump consecutiveChat
    if (msgIntent === "chat") {
      const newState = {
        ...state,
        consecutiveChat: state.consecutiveChat + 1,
      };
      if (newState.consecutiveChat >= 2) {
        // force explain
        const draft = tutorDraft("explain", topic, newState, null);
        return {
          widgets: [
            makeExplanationWidget(draft),
            makeSkillProgress(topic, newState),
          ],
          state: {
            ...newState,
            consecutiveChat: 0,
            lastStrategy: "explain",
            explanationUsed: true,
          },
          evaluation: null,
          messageIntent: "chat",
          nextStrategy: "explain",
        };
      }
      const draft = tutorDraft(state.lastStrategy || "socratic", topic, newState, null);
      return {
        widgets: [
          makeAskUserQuestion(draft, {
            interactionLevel: "L3",
            chatReminder: "Видимо это чат — вернёмся к вопросу ↓",
          }),
          makeSkillProgress(topic, newState),
        ],
        state: newState,
        evaluation: null,
        messageIntent: "chat",
      };
    }

    // answer-intent — evaluate and plan next
    const evalRes = evaluateAnswer(topic, userText);
    let newState = {
      ...state,
      attempts: state.attempts + 1,
      masteryScore: evalRes.score,
      consecutiveChat: 0,
      consecutiveWrong:
        evalRes.correct || evalRes.score >= 0.5
          ? 0
          : state.consecutiveWrong + 1,
      misconceptions: evalRes.misconception
        ? [...state.misconceptions, evalRes.misconception].slice(-3)
        : state.misconceptions,
    };

    const nextStrategy = planNextStrategy(newState, evalRes);
    newState.lastStrategy = nextStrategy;
    if (nextStrategy === "explain") newState.explanationUsed = true;

    if (nextStrategy === "mastered") {
      return {
        widgets: [
          makeInfoCard(
            "🎉 Тема усвоена",
            "Отличный ответ. Сессия завершена. Можешь переключить Space или задать новый вопрос.",
            "L",
            { category: "tutoring", criticality: "urgent", primary: true }
          ),
          makeSkillProgress(topic, newState),
        ],
        state: newState,
        evaluation: evalRes,
        messageIntent: "answer",
        nextStrategy,
        sessionStatus: "mastered",
      };
    }

    if (nextStrategy === "explain") {
      const draft = tutorDraft("explain", topic, newState, evalRes);
      return {
        widgets: [
          makeExplanationWidget(draft),
          makeSkillProgress(topic, newState),
        ],
        state: newState,
        evaluation: evalRes,
        messageIntent: "answer",
        nextStrategy,
      };
    }

    const draft = tutorDraft(nextStrategy, topic, newState, evalRes);
    return {
      widgets: [
        makeAskUserQuestion(draft, {
          interactionLevel: "L3",
          evaluation: evalRes,
        }),
        makeSkillProgress(topic, newState),
      ],
      state: newState,
      evaluation: evalRes,
      messageIntent: "answer",
      nextStrategy,
    };
  },
};

// ════════════════════════════════════════════════════════════
// 6. WIDGET FACTORIES — server-side projection equivalents
// ════════════════════════════════════════════════════════════

function makeAskUserQuestion(draft, { interactionLevel, evaluation, chatReminder } = {}) {
  return {
    type: "AskUserQuestion",
    widgetId: "aq-" + Math.random().toString(36).slice(2, 8),
    category: "tutoring",
    criticality: "urgent",
    calibrability: "tenant",
    layout: { size: "L", cornerRadiusPreset: "primary-24", priority: "urgent" },
    interactionLevel: interactionLevel || "L3",
    allowedActions: ["smart_line:submit_answer"],
    payload: { draft },
    chatReminder: chatReminder || null,
    evaluation: evaluation || null,
  };
}

function makeExplanationWidget(draft) {
  return {
    type: "ExplanationWidget",
    widgetId: "ex-" + Math.random().toString(36).slice(2, 8),
    category: "tutoring",
    criticality: "high",
    calibrability: "tenant",
    layout: { size: "L", cornerRadiusPreset: "primary-24", priority: "high" },
    interactionLevel: "L1",
    allowedActions: ["smart_line:submit_answer"], // verify follow-up
    payload: { draft },
  };
}

function makeAnswerCardFromSources(prompt, sources) {
  const best = sources[0];
  return {
    type: "SmartLineAnswerCard",
    widgetId: "ac-" + Math.random().toString(36).slice(2, 8),
    category: "operational",
    criticality: "high",
    calibrability: "tenant",
    layout: { size: "L", cornerRadiusPreset: "baseline-16", priority: "high" },
    interactionLevel: "L1",
    allowedActions: [],
    payload: {
      title: "Ответ из материалов курса",
      text: best.text,
      sources,
      fallback: false,
    },
  };
}

function makeFallbackAnswerCard(prompt) {
  return {
    type: "SmartLineAnswerCard",
    widgetId: "ac-" + Math.random().toString(36).slice(2, 8),
    category: "operational",
    criticality: "normal",
    calibrability: "tenant",
    layout: { size: "L", cornerRadiusPreset: "baseline-16", priority: "normal" },
    interactionLevel: "L1",
    allowedActions: [],
    payload: {
      title: "В материалах курса ничего не нашлось",
      text:
        "По этому запросу в материалах курса нет информации. " +
        "Уточни у преподавателя или ментора — я не выдумываю ответы, чтобы не дезинформировать.",
      sources: [],
      fallback: true,
    },
  };
}

function makeSkillProgress(topic, state) {
  return {
    type: "SkillProgress",
    widgetId: "sp-" + Math.random().toString(36).slice(2, 8),
    category: "progress",
    criticality: "low",
    calibrability: "learner",
    layout: { size: "S", cornerRadiusPreset: "baseline-16", priority: "low" },
    interactionLevel: "L1",
    allowedActions: [],
    payload: {
      skill: topic === "unit-economics" ? "Юнит-экономика" : topic,
      score: state.masteryScore,
      attempts: state.attempts,
    },
  };
}

function makeInfoCard(title, text, size = "M", extras = {}) {
  return {
    type: "InfoCard",
    widgetId: "info-" + Math.random().toString(36).slice(2, 8),
    category: extras.category || "content",
    criticality: extras.criticality || "normal",
    calibrability: "tenant",
    layout: {
      size,
      cornerRadiusPreset: extras.primary ? "primary-24" : "baseline-16",
      priority: extras.criticality || "normal",
    },
    interactionLevel: "L1",
    allowedActions: [],
    payload: { title, text },
  };
}

function renderSpaceDefault(space) {
  const data = {
    ANALYTICS: [
      makeInfoCard(
        "📊 Прогресс за неделю",
        "4.2 часа обучения, 3 темы начаты, 1 тема усвоена.",
        "L",
        { category: "progress", criticality: "high" }
      ),
      makeInfoCard("⏱ Средняя сессия", "12 минут — немного выше среднего по когорте.", "M", {
        category: "progress",
      }),
      makeInfoCard("⚠ Слабое место", "Тема «юнит-экономика» — 2 неверных ответа.", "M", {
        category: "assessment",
        criticality: "high",
      }),
    ],
    MARKETPLACE: [
      makeInfoCard("🛒 Рекомендуем", "«Продвинутая финансовая модель» — под твой трек.", "L", {
        category: "commerce",
      }),
      makeInfoCard("⭐ Самый популярный", "«Основы юнит-экономики» — 1 247 студентов.", "M", {
        category: "commerce",
      }),
    ],
    PROFILE: [
      makeInfoCard("👤 Мой профиль", "Имя, аватар, биография. Редактирование — в полном приложении.", "L", {
        category: "profile",
        criticality: "high",
      }),
      makeInfoCard("🏅 Сертификаты", "1 сертификат получен. Ещё 3 ждут сдачи финального теста.", "M", {
        category: "profile",
      }),
    ],
    ONBOARDING: [
      makeInfoCard("🎯 Приветствуем!", "Пройди короткий опрос, чтобы мы подобрали подходящий трек.", "L", {
        category: "onboarding",
        criticality: "urgent",
        primary: true,
      }),
      makeInfoCard("⏲ Готов к обучению?", "Выбери комфортный ритм: 15, 30 или 45 минут в день.", "M", {
        category: "onboarding",
      }),
    ],
  };
  return { intent: "SPACE_DEFAULT", widgets: data[space] || [] };
}

// ════════════════════════════════════════════════════════════
// 7. RENDERER — serialized widget projection → DOM
// ════════════════════════════════════════════════════════════

const bentoEl = document.getElementById("bento");

function renderWidgets(widgets) {
  bentoEl.innerHTML = "";
  widgets.forEach((w) => bentoEl.appendChild(renderWidget(w)));
}

function renderWidget(w) {
  const el = document.createElement("article");
  el.className = `widget size-${w.layout.size}`;
  if (w.layout.cornerRadiusPreset === "primary-24") el.classList.add("widget--primary");
  if (w.type === "SmartLineAnswerCard" && w.payload.fallback) el.classList.add("widget--fallback");
  if (w.type === "SkillProgress") el.classList.add("widget--progress");

  const levelTag = `<span class="level">${w.interactionLevel}</span>`;
  const categoryTag = `<span class="tag">${w.category}</span>`;
  el.insertAdjacentHTML(
    "afterbegin",
    `<div class="widget__meta">${categoryTag}${levelTag}</div>`
  );

  switch (w.type) {
    case "AskUserQuestion": renderAskUserQuestion(el, w); break;
    case "ExplanationWidget": renderExplanationWidget(el, w); break;
    case "SmartLineAnswerCard": renderAnswerCard(el, w); break;
    case "SkillProgress": renderSkillProgress(el, w); break;
    case "InfoCard": renderInfoCard(el, w); break;
  }
  return el;
}

function renderAskUserQuestion(el, w) {
  const d = w.payload.draft;
  const evalBlock = w.evaluation
    ? `<div class="evaluation ${w.evaluation.correct ? "evaluation--correct" : "evaluation--wrong"}">
         ${w.evaluation.correct ? "✓" : w.evaluation.partial ? "~" : "✗"}
         score=${w.evaluation.score.toFixed(2)} —
         ${w.evaluation.feedback}
       </div>`
    : "";
  const chatNote = w.chatReminder
    ? `<div class="widget__hint">💬 ${w.chatReminder}</div>`
    : "";
  el.insertAdjacentHTML(
    "beforeend",
    `
      <h2>${escapeHtml(d.title)}</h2>
      <p>${escapeHtml(d.text)}</p>
      ${d.hint ? `<div class="widget__hint">💡 ${escapeHtml(d.hint)}</div>` : ""}
      ${chatNote}
      <div style="display:flex; flex-direction:column; gap:10px;">
        <textarea
          class="textarea"
          placeholder="Ответь в 1–2 предложениях…"
          data-role="answer"
          aria-label="Твой ответ"
        ></textarea>
        <div class="actions">
          <button class="btn btn--primary" data-action="submit_answer">
            Отправить ответ
          </button>
          <button class="btn btn--ghost" data-action="chat">
            Я подумаю (пропустить)
          </button>
        </div>
        ${evalBlock}
      </div>
    `
  );

  const textarea = el.querySelector('[data-role="answer"]');
  textarea.addEventListener("keydown", (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      submitAnswer(w, textarea.value);
    }
  });
  el.querySelector('[data-action="submit_answer"]').addEventListener("click", () =>
    submitAnswer(w, textarea.value)
  );
  el.querySelector('[data-action="chat"]').addEventListener("click", () =>
    submitAnswer(w, "ясно")
  );
}

function renderExplanationWidget(el, w) {
  const d = w.payload.draft;
  const sourcesBlock = d.sources && d.sources.length > 0
    ? `<div class="widget__sources">Источники:
         <ul>${d.sources
           .map((s) => `<li>[${s.sourceId}] ${s.source}, стр. ${s.page}</li>`)
           .join("")}</ul>
       </div>`
    : "";
  el.insertAdjacentHTML(
    "beforeend",
    `
      <h2>${escapeHtml(d.title)}</h2>
      <p>${escapeHtml(d.text)}</p>
      ${sourcesBlock}
      <div style="display:flex; flex-direction:column; gap:10px; margin-top:6px;">
        <div class="widget__hint">Проверим, что ты понял(а):</div>
        <input
          class="input"
          placeholder="Одно предложение — про что юнит-экономика?"
          data-role="answer"
        />
        <div class="actions">
          <button class="btn btn--primary" data-action="submit_answer">Проверить</button>
        </div>
      </div>
    `
  );
  const input = el.querySelector('[data-role="answer"]');
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") submitAnswer(w, input.value);
  });
  el.querySelector('[data-action="submit_answer"]').addEventListener("click", () =>
    submitAnswer(w, input.value)
  );
}

function renderAnswerCard(el, w) {
  const p = w.payload;
  const sourcesBlock = p.sources && p.sources.length > 0
    ? `<div class="widget__sources">Источники:
         <ul>${p.sources
           .map((s) => `<li>[${s.sourceId}] ${s.source}, стр. ${s.page}</li>`)
           .join("")}</ul>
       </div>`
    : "";
  el.insertAdjacentHTML(
    "beforeend",
    `
      <h2>${escapeHtml(p.title)}</h2>
      <p>${escapeHtml(p.text)}</p>
      ${sourcesBlock}
      ${p.fallback ? `<div class="widget__hint">⚠ fallback — RAG не нашёл чанков</div>` : ""}
    `
  );
}

function renderSkillProgress(el, w) {
  const p = w.payload;
  const pct = Math.round(p.score * 100);
  el.insertAdjacentHTML(
    "beforeend",
    `
      <div class="widget__hint">${escapeHtml(p.skill)}</div>
      <div class="progress">
        <div class="progress__bar"><div class="progress__fill" style="width:${pct}%"></div></div>
        <div class="progress__value">${pct}%</div>
      </div>
      <div class="widget__hint">Попыток: ${p.attempts}</div>
    `
  );
}

function renderInfoCard(el, w) {
  el.insertAdjacentHTML(
    "beforeend",
    `<h2>${escapeHtml(w.payload.title)}</h2><p>${escapeHtml(w.payload.text)}</p>`
  );
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

// ════════════════════════════════════════════════════════════
// 8. APP STATE + MAIN FLOW
// ════════════════════════════════════════════════════════════

const SPACES = [
  { id: "LEARNING", icon: "📚", label: "Learning" },
  { id: "ANALYTICS", icon: "📊", label: "Analytics" },
  { id: "MARKETPLACE", icon: "🛒", label: "Marketplace" },
  { id: "PROFILE", icon: "👤", label: "Profile" },
  { id: "ONBOARDING", icon: "🎯", label: "Get Started" },
];

const app = {
  activeSpace: "LEARNING",
  sessionId: "sess-" + Math.random().toString(36).slice(2, 10),
  topic: "unit-economics",
  state: {
    attempts: 0,
    masteryScore: 0,
    consecutiveWrong: 0,
    consecutiveChat: 0,
    lastStrategy: null,
    explanationUsed: false,
    misconceptions: [],
  },
  lastIntent: null,
  renderedVersion: 0,
};

function resetStudentState() {
  app.state = {
    attempts: 0,
    masteryScore: 0,
    consecutiveWrong: 0,
    consecutiveChat: 0,
    lastStrategy: null,
    explanationUsed: false,
    misconceptions: [],
  };
}

// Chips
function renderChips() {
  const chipsEl = document.getElementById("chips");
  chipsEl.innerHTML = "";
  SPACES.forEach((sp) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "chip";
    b.setAttribute("aria-pressed", String(sp.id === app.activeSpace));
    b.textContent = `${sp.icon} ${sp.label}`;
    b.addEventListener("click", () => switchSpace(sp.id));
    chipsEl.appendChild(b);
  });
  updateMeta();
}

async function switchSpace(space) {
  app.activeSpace = space;
  app.renderedVersion++;
  renderChips();
  showToast(`Space → ${space}. Widget Lake regenerating (L4)…`);
  resetStudentState();
  const res = await Backend.render("", { space, state: app.state, topic: app.topic });
  app.lastIntent = res.intent;
  renderWidgets(res.widgets);
  updateMeta();
}

function updateMeta() {
  const sp = SPACES.find((s) => s.id === app.activeSpace);
  document.getElementById("meta-space").textContent = `${sp.icon} ${sp.label}`;
  document.getElementById("meta-session").textContent = `session ${app.sessionId} · v${app.renderedVersion}`;
}

// Submit flow (prompt → render)
async function submitPrompt(prompt, { inputMode = "text", voiceMeta = null } = {}) {
  if (!prompt || !prompt.trim()) return;
  app.renderedVersion++;
  updateMeta();
  showToast(`[${inputMode}] "${truncate(prompt, 80)}"`);

  let res;
  try {
    res = await Backend.render(prompt, {
      space: app.activeSpace,
      state: app.state,
      topic: app.topic,
    });
  } catch (e) {
    console.error("[smart-line] render error", e);
    showToast(`❌ ${e.message || "render failed"}`);
    return;
  }
  app.lastIntent = res.intent;
  if (res.intent === "LEARNING_UNDERSTANDING") {
    app.state.lastStrategy = res.nextStrategy;
  }
  renderWidgets(res.widgets);
  updateMeta();
  logEvent("render", {
    intent: res.intent,
    inputMode,
    voiceMeta,
    version: app.renderedVersion,
  });
}

// Submit flow (widget answer)
async function submitAnswer(widget, answerText) {
  app.renderedVersion++;
  let res;
  try {
    res = await Backend.interact({
      widgetId: widget.widgetId,
      actionId: "smart_line:submit_answer",
      input: { learner_answer: answerText },
      state: app.state,
      topic: app.topic,
    });
  } catch (e) {
    console.error("[smart-line] interact error", e);
    showToast(`❌ ${e.message || "interact failed"}`);
    return;
  }
  app.state = res.state;
  renderWidgets(res.widgets);
  updateMeta();
  showToast(
    res.sessionStatus === "mastered"
      ? "✅ mastered"
      : `intent=${res.messageIntent} · next=${res.nextStrategy}`
  );
  logEvent("interact", {
    messageIntent: res.messageIntent,
    nextStrategy: res.nextStrategy,
    evaluation: res.evaluation,
    sessionStatus: res.sessionStatus,
  });
}

// ════════════════════════════════════════════════════════════
// 9. VOICE INPUT — Web Speech API wrapper
// ════════════════════════════════════════════════════════════

let recognition = null;
let voiceStartTs = 0;
const composerEl = document.getElementById("composer");
const micBtn = document.getElementById("mic");
const promptInput = document.getElementById("prompt");

const SpeechRecognition =
  window.SpeechRecognition || window.webkitSpeechRecognition || null;

if (!SpeechRecognition) {
  composerEl.classList.add("no-voice");
  document.getElementById("hint").textContent =
    "Голосовой ввод недоступен в этом браузере. Используй Chrome/Edge.";
} else {
  recognition = new SpeechRecognition();
  recognition.lang = "ru-RU";
  recognition.interimResults = true;
  recognition.continuous = false;

  recognition.addEventListener("result", (ev) => {
    let interim = "";
    let finalText = "";
    let confidence = 0;
    for (let i = ev.resultIndex; i < ev.results.length; i++) {
      const r = ev.results[i];
      if (r.isFinal) {
        finalText += r[0].transcript;
        confidence = r[0].confidence || 0;
      } else {
        interim += r[0].transcript;
      }
    }
    if (interim) showToast(`🎙 ${interim}`);
    if (finalText) {
      promptInput.value = finalText.trim();
      promptInput.focus();
      const voiceMeta = {
        confidence: +confidence.toFixed(2),
        language: recognition.lang,
        durationMs: Date.now() - voiceStartTs,
      };
      // Do NOT auto-submit — let the user edit per UX-DR5 (WCAG error recovery).
      showToast(`🎙 готово — проверь текст и нажми отправить`);
      micBtn.classList.remove("active");
      promptInput.dataset.inputMode = "voice";
      promptInput.dataset.voiceMeta = JSON.stringify(voiceMeta);
    }
  });

  recognition.addEventListener("end", () => micBtn.classList.remove("active"));
  recognition.addEventListener("error", (ev) => {
    micBtn.classList.remove("active");
    showToast(`🎙 ошибка: ${ev.error}`);
  });

  micBtn.addEventListener("click", () => {
    if (micBtn.classList.contains("active")) {
      recognition.stop();
      return;
    }
    voiceStartTs = Date.now();
    try {
      recognition.start();
      micBtn.classList.add("active");
    } catch (e) {
      console.error(e);
    }
  });
}

// ════════════════════════════════════════════════════════════
// 10. Toast / events
// ════════════════════════════════════════════════════════════

let toastTimer = null;
function showToast(text) {
  const t = document.getElementById("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    t.hidden = true;
  }, 2400);
}

function logEvent(name, payload) {
  console.log(`[smart-line] ${name}`, payload);
}

function delay(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function truncate(s, n) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

// ════════════════════════════════════════════════════════════
// 11. Boot
// ════════════════════════════════════════════════════════════

// Form submit
document.getElementById("composer").addEventListener("submit", async (e) => {
  e.preventDefault();
  const prompt = promptInput.value.trim();
  if (!prompt) return;
  const inputMode = promptInput.dataset.inputMode === "voice" ? "voice" : "text";
  const voiceMeta =
    inputMode === "voice" && promptInput.dataset.voiceMeta
      ? JSON.parse(promptInput.dataset.voiceMeta)
      : null;
  promptInput.value = "";
  promptInput.dataset.inputMode = "text";
  promptInput.dataset.voiceMeta = "";
  await submitPrompt(prompt, { inputMode, voiceMeta });
});

// ════════════════════════════════════════════════════════════
// 12. Backend selection — real (FastAPI) if available, else mock
// ════════════════════════════════════════════════════════════

let Backend = MockBackend;
let BackendMode = "mock";

async function probeBackend() {
  try {
    const r = await fetch("/api/health", { method: "GET" });
    if (!r.ok) throw new Error("health not ok");
    const data = await r.json();
    if (data.openai === true) {
      Backend = RealBackend;
      BackendMode = "real";
      return data;
    }
    return data; // healthy but no key — stay in mock
  } catch (e) {
    return null;
  }
}

function renderBackendBanner(health) {
  const meta = document.getElementById("meta");
  const pill = document.createElement("span");
  pill.style.padding = "2px 9px";
  pill.style.borderRadius = "999px";
  pill.style.fontWeight = "600";
  pill.style.fontSize = "11px";
  pill.style.textTransform = "uppercase";
  pill.style.letterSpacing = "0.06em";
  if (BackendMode === "real") {
    pill.textContent = `● real · ${health.chat_model}`;
    pill.style.background = "var(--accent-soft)";
    pill.style.color = "var(--accent-ink)";
  } else {
    pill.textContent = "● mock";
    pill.style.background = "#fef3c7";
    pill.style.color = "#78350f";
  }
  meta.prepend(pill);
  meta.prepend(document.createTextNode(" "));
}

(async function boot() {
  renderChips();
  const health = await probeBackend();
  renderBackendBanner(health || { chat_model: "—" });

  if (BackendMode === "real") {
    renderWidgets([
      makeInfoCard(
        "Привет 👋 — подключено к настоящему бэкенду",
        "Запрос уходит в FastAPI → OpenAI (" +
          (health.chat_model || "") +
          ") + Qdrant (" +
          (health.collection || "") +
          "). Попробуй: «Что такое юнит-экономика?», «Когда дедлайн по домашке?», «Расписание вебинаров».",
        "L",
        { category: "onboarding", criticality: "urgent", primary: true }
      ),
      makeInfoCard(
        "⏱ Латентность",
        "Каждый ход — 2–5 сек: embed → Qdrant → 2–4 LLM вызова. Это нормально для notebook-уровня.",
        "M"
      ),
      makeInfoCard(
        "⚙ Проверь в консоли (F12)",
        "Каждый render / interact логируется. Ошибки OpenAI / Qdrant всплывут в toast.",
        "M"
      ),
    ]);
  } else {
    renderWidgets([
      makeInfoCard(
        "Привет 👋 — mock-режим",
        "Бэкенд не отвечает или OPENAI_API_KEY не задан. UI работает на in-browser моке. Попробуй: «Объясни юнит-экономику», «Когда дедлайн?», «Расписание вебинаров». Можно голосом.",
        "L",
        { category: "onboarding", criticality: "urgent", primary: true }
      ),
      makeInfoCard(
        "💡 Как включить real",
        "Запусти `python backend/server.py` из smart-line-prototype/ и обнови страницу.",
        "M"
      ),
      makeInfoCard(
        "⚙ Пробные фразы",
        "• «дедлайн по домашке» → RAG-цитата (в моке)\n• «про инопланетян» → fallback\n• 📊 Analytics → space switch",
        "M"
      ),
    ]);
  }
  updateMeta();
})();

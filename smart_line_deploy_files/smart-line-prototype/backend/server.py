"""
Smart Line — real backend ported from ИИ_агент.ipynb.

FastAPI service wrapping:
  - RAG via Qdrant (text-embedding-3-large over presentations_industrix_openai)
  - 7 agents: QuestionTypeClassifier, OrganizationalAgent, PlannerAgent,
    TutorAgent, IntentClassifier, ChatAgent, EvaluatorAgent
  - Session state in-memory keyed by sessionId
  - Static frontend served at /

Endpoints:
  GET  /api/health         — status + configured models
  POST /api/render         — initial prompt → Bento widgets
  POST /api/interact       — student action on widget → updated widgets
  GET  /                   — index.html (static)

Run:
  cd smart-line-prototype/backend
  python -m venv .venv && source .venv/Scripts/activate  # Windows git-bash
  pip install -r requirements.txt
  # set OPENAI_API_KEY via backend/.env
  python server.py
"""

from __future__ import annotations

import json
import logging
import os
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

# ─────────────────────────── env loading ───────────────────────────
# Minimal .env loader — we don't want python-dotenv as a hard dependency.
_ENV_PATH = Path(__file__).parent / ".env"
if _ENV_PATH.exists():
    for _line in _ENV_PATH.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if not _line or _line.startswith("#") or "=" not in _line:
            continue
        _k, _v = _line.split("=", 1)
        os.environ.setdefault(_k.strip(), _v.strip())

# ─────────────────────────── imports ───────────────────────────────
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from openai import OpenAI
from pydantic import BaseModel, Field
from qdrant_client import QdrantClient

# ─────────────────────────── config ───────────────────────────────
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "").strip()
EMBED_MODEL = os.environ.get("EMBED_MODEL", "text-embedding-3-large")
CHAT_MODEL = os.environ.get("CHAT_MODEL", "gpt-4o-mini")
QDRANT_URL = os.environ.get("QDRANT_URL", "https://qdrant.dev.adapstory.com")
COLLECTION = os.environ.get("QDRANT_COLLECTION", "presentations_industrix_openai")

os.environ["QDRANT_DISABLE_CHECK"] = "1"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
)
log = logging.getLogger("smart-line")

# ─────────────────────────── clients (lazy) ───────────────────────
_openai_client: Optional[OpenAI] = None
_qdrant: Optional[QdrantClient] = None


def openai_client() -> OpenAI:
    global _openai_client
    if _openai_client is None:
        if not OPENAI_API_KEY:
            raise HTTPException(
                status_code=503,
                detail="OPENAI_API_KEY is not set. Put it into smart-line-prototype/backend/.env",
            )
        _openai_client = OpenAI(api_key=OPENAI_API_KEY)
    return _openai_client


def qdrant() -> QdrantClient:
    global _qdrant
    if _qdrant is None:
        _qdrant = QdrantClient(url=QDRANT_URL, port=443, timeout=120)
    return _qdrant


# ═══════════════════════════════════════════════════════════════════
# 1. NOTEBOOK HELPERS — ported verbatim from ИИ_агент.ipynb
# ═══════════════════════════════════════════════════════════════════

def embed_query(text: str) -> list:
    return openai_client().embeddings.create(
        model=EMBED_MODEL, input=text
    ).data[0].embedding


def ask_llm(prompt: str, temperature: float = 0.3) -> str:
    r = openai_client().chat.completions.create(
        model=CHAT_MODEL,
        messages=[{"role": "user", "content": prompt}],
        temperature=temperature,
        max_tokens=700,
    )
    return r.choices[0].message.content.strip()


def parse_json(text: str) -> dict:
    cleaned = (
        text.strip()
        .removeprefix("```json")
        .removeprefix("```")
        .removesuffix("```")
        .strip()
    )
    return json.loads(cleaned)


def retrieve_context(question: str, top_k: int = 10) -> list:
    vector = embed_query(question)
    # qdrant-client >=1.10 replaced .search() with .query_points()
    response = qdrant().query_points(
        collection_name=COLLECTION,
        query=vector,
        limit=top_k,
        with_payload=True,
    )
    points = getattr(response, "points", response)
    contexts = []
    for r in points:
        payload = r.payload or {}
        text = (payload.get("text") or "").strip()
        if not text:
            continue
        contexts.append({
            "text": text,
            "page": payload.get("page"),
            "source": payload.get("source"),
            "score": r.score,
        })
    return contexts


def build_context(contexts: list) -> str:
    chunks = []
    for i, c in enumerate(contexts):
        chunks.append(
            f"CHUNK_ID: {i}\nSOURCE: {c.get('source')}\nPAGE: {c.get('page')}\n\nTEXT:\n{c.get('text')}"
        )
    return "\n\n---\n\n".join(chunks)


def format_history(history: list) -> str:
    return "\n".join(history) if history else "История диалога пуста."


# ═══════════════════════════════════════════════════════════════════
# 2. STATE
# ═══════════════════════════════════════════════════════════════════

@dataclass
class StudentState:
    session_id: str
    topic: str
    attempts: int = 0
    mastery_score: float = 0.0
    history: List[str] = field(default_factory=list)
    misconceptions: List[str] = field(default_factory=list)
    last_strategy: Optional[str] = None
    explanation_used: bool = False
    consecutive_wrong: int = 0
    consecutive_chat: int = 0
    # cached retrieval for this session — set on render
    contexts: List[dict] = field(default_factory=list)
    last_tutor_msg: str = ""
    active_widget_id: Optional[str] = None
    force_next_strategy: Optional[str] = None


SESSIONS: Dict[str, StudentState] = {}


# ═══════════════════════════════════════════════════════════════════
# 3. AGENTS — ported from ИИ_агент.ipynb
# ═══════════════════════════════════════════════════════════════════

class QuestionTypeClassifier:
    """
    Routes the question into one of: organizational | learning | other.
    """

    def classify(self, question: str) -> str:
        prompt = f"""
Ты классифицируешь вопросы студентов в учебном курсе.

Вопрос: "{question}"

Категории и их ПРИЗНАКИ:

organizational — вопрос про ПРОЦЕСС обучения, а НЕ про содержание.
  Ключевые слова: домашка, задание, дедлайн, обратная связь, преподаватель,
  расписание, оценки, платформа, проверка, сдача, срок, вебинар, чат, ментор, эксперт, формат сдачи, домашенее задание, как сдвать.
  Примеры: "Будет ли обратная связь?", "Когда дедлайн?",
            "Как сдавать домашку?", "Есть ли проверка заданий?"

learning — вопрос про содержание курса: термины, концепции, темы.
  Примеры: "Что такое юнит-экономика?", "Почему стартапы проваливаются?"

other — всё остальное, не связанное с курсом.

ЖЁСТКОЕ ПРАВИЛО: если в вопросе есть хотя бы одно из слов:
домашка / задание / дедлайн / обратная связь / преподаватель /
расписание / проверка / вебинар / чат — это ВСЕГДА organizational.

Верни ТОЛЬКО JSON без пояснений: {{"type": "learning|organizational|other"}}
"""
        try:
            res = ask_llm(prompt, temperature=0)
            return parse_json(res).get("type", "learning")
        except Exception as e:
            log.warning("QuestionTypeClassifier fallback: %s", e)
            return "learning"


class OrganizationalAgent:
    """Answers organizational questions strictly from RAG context."""

    def respond(self, question: str, context: str) -> str:
        prompt = f"""
Студент курса задал организационный вопрос.

Информация из материалов курса:
{context}

Вопрос студента: "{question}"

Правила:
- Отвечай ТОЛЬКО на основе информации из материалов курса выше
- Если информация есть — изложи её кратко и точно своими словами (2-4 предложения)
- Если информации нет — скажи: "В материалах курса нет информации по этому вопросу. Уточните у преподавателя."
- Не придумывай ответы из общих знаний LLM
"""
        return ask_llm(prompt, temperature=0.1)


class PlannerAgent:
    """
    Chooses strategy: socratic | hint | explain | verify | mastered.
    Preserves notebook transitions.
    """

    def plan(
        self,
        question: str,
        context_text: str,
        state: StudentState,
        last_answer: Optional[str] = None,
        evaluation: Optional[dict] = None,
    ) -> dict:
        misconceptions_text = (
            "\n".join(f"- {m}" for m in state.misconceptions)
            if state.misconceptions
            else "нет"
        )
        prompt = f"""
Ты педагогический планировщик ИИ-тьютора.

Выбери ОДНУ стратегию из: socratic, hint, explain, verify.

Правила выбора:
1. socratic  — студент ещё не пробовал отвечать или ответил частично (score < 0.5), attempts <= 1
2. hint      — студент ответил, но score < 0.6, attempts <= 3; или есть явное заблуждение
3. explain   — attempts >= 3 ИЛИ consecutive_wrong >= 2 ИЛИ студент явно не понимает
4. verify    — стратегия была explain → нужно проверить понимание (задать короткий вопрос)
5. НЕ повторяй одну стратегию больше 2 раз подряд без изменений
6. Если score > 0.8 → ответь "mastered" (тема усвоена, сессию можно завершать)

Текущее состояние:
- attempts: {state.attempts}
- consecutive_wrong: {state.consecutive_wrong}
- last_strategy: {state.last_strategy or "нет"}
- explanation_used: {state.explanation_used}
- mastery_score: {state.mastery_score}

Заблуждения студента:
{misconceptions_text}

Последний ответ студента:
{last_answer or "пока нет ответа"}

Оценка последнего ответа:
{json.dumps(evaluation, ensure_ascii=False) if evaluation else "нет"}

Контекст (фрагмент):
{context_text[:800]}

Верни ТОЛЬКО JSON без пояснений:
{{"strategy": "socratic|hint|explain|verify|mastered"}}
"""
        response = ask_llm(prompt, temperature=0.1)
        try:
            strategy = parse_json(response).get("strategy", "socratic")
        except Exception:
            strategy = "socratic"
        return {"strategy": strategy}


class TutorAgent:
    """Generates tutor text per chosen strategy."""

    STRATEGY_INSTRUCTIONS = {
        "socratic": (
            "Задай ОДИН наводящий вопрос, который помогает студенту самому прийти к ответу.\n"
            "Не давай ответ и не перечисляй факты. Максимум — 2 предложения."
        ),
        "hint": (
            "Дай ОДНУ небольшую подсказку, указывающую направление мысли.\n"
            "Не раскрывай полный ответ. Упомяни заблуждение студента, если оно есть.\n"
            "Максимум — 3 предложения."
        ),
        "explain": (
            "Объясни тему развёрнуто и структурированно.\n"
            "Используй ТОЛЬКО контекст курса.\n"
            "В конце укажи источник: [Источник: SOURCE, стр. PAGE]\n"
            "Исправь заблуждения студента, если они есть."
        ),
        "verify": (
            "Ты только что объяснил тему. Теперь задай студенту ОДИН короткий проверочный вопрос,\n"
            "чтобы убедиться, что он понял объяснение. Вопрос должен быть конкретным."
        ),
    }

    def respond(
        self,
        strategy: str,
        question: str,
        contexts: list,
        history: list,
        misconceptions: Optional[List[str]] = None,
    ) -> str:
        context_text = build_context(contexts)
        history_text = format_history(history)
        misc_text = (
            "Заблуждения студента, которые нужно исправить:\n"
            + "\n".join(f"- {m}" for m in misconceptions)
            if misconceptions
            else ""
        )
        instruction = self.STRATEGY_INSTRUCTIONS.get(
            strategy, self.STRATEGY_INSTRUCTIONS["socratic"]
        )
        prompt = f"""
Ты ИИ-тьютор курса. Используй ТОЛЬКО информацию из контекста.
Если информации нет — скажи: «В материалах курса нет информации по этому вопросу».

КОНТЕКСТ КУРСА:
{context_text}

ВОПРОС СТУДЕНТА:
{question}

ИСТОРИЯ ДИАЛОГА:
{history_text}

{misc_text}

СТРАТЕГИЯ: {strategy}
ИНСТРУКЦИЯ:
{instruction}

ОТВЕТ ТЬЮТОРА:
"""
        return ask_llm(prompt, temperature=0.3)


class IntentClassifier:
    """answer | chat | question | off_topic"""

    def classify(self, student_message: str, tutor_last_message: str, topic: str) -> str:
        prompt = f"""
Ты классификатор намерений в диалоге тьютора и студента.

Тема обучения: {topic}

Последнее сообщение тьютора:
{tutor_last_message}

Сообщение студента:
{student_message}

Определи намерение сообщения студента. Варианты:
- answer     — студент пытается ответить на вопрос тьютора (даже частично или неверно)
- chat       — разговорное сообщение: благодарность, подтверждение, "понял", "окей", "спасибо" и т.п.
- question   — студент задаёт свой вопрос по теме курса
- off_topic  — сообщение не связано с темой обучения

Верни ТОЛЬКО JSON:
{{"intent": "answer|chat|question|off_topic"}}
"""
        response = ask_llm(prompt, temperature=0)
        try:
            return parse_json(response).get("intent", "answer")
        except Exception:
            return "answer"


class ChatAgent:
    def respond_to_chat(
        self,
        student_message: str,
        tutor_last_message: str,
        topic: str,
        history: list,
    ) -> str:
        history_text = format_history(history[-6:])
        prompt = f"""
Ты ИИ-тьютор курса. Студент написал тебе разговорное сообщение.

Тема обучения: {topic}

История диалога:
{history_text}

Твоё последнее сообщение:
{tutor_last_message}

Сообщение студента:
{student_message}

Ответь естественно и коротко (1-2 предложения).
Затем мягко напомни свой предыдущий вопрос или предложи продолжить.
Не повторяй весь вопрос дословно — перефразируй кратко.
"""
        return ask_llm(prompt, temperature=0.4)

    def respond_to_question(
        self,
        student_question: str,
        contexts: list,
        topic: str,
        history: list,
        tutor_last_message: str,
    ) -> str:
        context_text = build_context(contexts[:3])
        history_text = format_history(history[-6:])
        prompt = f"""
Ты ИИ-тьютор курса. Студент задал уточняющий вопрос.

Тема обучения: {topic}
Контекст курса: {context_text}

История диалога:
{history_text}

Вопрос студента: {student_question}

Ответь кратко, используя только контекст курса (2-4 предложения).
Если информации нет — скажи честно.
После ответа мягко верни студента к теме, напомнив свой вопрос одним предложением.

Твой предыдущий вопрос был: {tutor_last_message}
"""
        return ask_llm(prompt, temperature=0.3)


class EvaluatorAgent:
    def evaluate(self, question: str, student_answer: str, context: str) -> dict:
        prompt = f"""
Ты оцениваешь ответ студента на вопрос по материалам курса.

Вопрос: {question}
Ответ студента: {student_answer}
Контекст курса: {context}

Верни ТОЛЬКО JSON без пояснений:
{{
  "correct": true/false,
  "partial": true/false,
  "misconception": "описание заблуждения или пустая строка",
  "score": 0.0-1.0,
  "feedback": "краткий комментарий для тьютора (не для студента)"
}}
"""
        response = ask_llm(prompt, temperature=0)
        try:
            return parse_json(response)
        except Exception:
            return {
                "correct": False,
                "partial": False,
                "misconception": "",
                "score": 0.0,
                "feedback": "",
            }


# Singletons
qtype_clf = QuestionTypeClassifier()
org_agent = OrganizationalAgent()
planner = PlannerAgent()
tutor = TutorAgent()
intent_clf = IntentClassifier()
chat_agent = ChatAgent()
evaluator = EvaluatorAgent()


# ═══════════════════════════════════════════════════════════════════
# 4. WIDGET FACTORIES — match shapes the frontend renderer expects
# ═══════════════════════════════════════════════════════════════════

def _widget_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


def make_ask_user_question(
    title: str,
    text: str,
    *,
    interaction_level: str = "L3",
    evaluation: Optional[dict] = None,
    chat_reminder: Optional[str] = None,
    hint: str = "",
) -> dict:
    return {
        "type": "AskUserQuestion",
        "widgetId": _widget_id("aq"),
        "category": "tutoring",
        "criticality": "urgent",
        "calibrability": "tenant",
        "layout": {
            "size": "L",
            "cornerRadiusPreset": "primary-24",
            "priority": "urgent",
        },
        "interactionLevel": interaction_level,
        "allowedActions": ["smart_line:submit_answer"],
        "payload": {
            "draft": {"title": title, "text": text, "hint": hint}
        },
        "chatReminder": chat_reminder,
        "evaluation": evaluation,
    }


def make_explanation_widget(title: str, text: str, sources: list) -> dict:
    return {
        "type": "ExplanationWidget",
        "widgetId": _widget_id("ex"),
        "category": "tutoring",
        "criticality": "high",
        "calibrability": "tenant",
        "layout": {
            "size": "L",
            "cornerRadiusPreset": "primary-24",
            "priority": "high",
        },
        "interactionLevel": "L1",
        "allowedActions": ["smart_line:submit_answer"],
        "payload": {
            "draft": {
                "title": title,
                "text": text,
                "hint": "",
                "sources": [
                    {
                        "sourceId": s.get("source") or "unknown",
                        "source": s.get("source") or "unknown",
                        "page": s.get("page"),
                        "score": round(s.get("score") or 0, 2),
                    }
                    for s in (sources or [])[:3]
                ],
            }
        },
    }


def make_answer_card(
    title: str, text: str, sources: list, fallback: bool = False
) -> dict:
    return {
        "type": "SmartLineAnswerCard",
        "widgetId": _widget_id("ac"),
        "category": "operational",
        "criticality": "high" if not fallback else "normal",
        "calibrability": "tenant",
        "layout": {
            "size": "L",
            "cornerRadiusPreset": "baseline-16",
            "priority": "high" if not fallback else "normal",
        },
        "interactionLevel": "L1",
        "allowedActions": [],
        "payload": {
            "title": title,
            "text": text,
            "fallback": fallback,
            "sources": [
                {
                    "sourceId": s.get("source") or "unknown",
                    "source": s.get("source") or "unknown",
                    "page": s.get("page"),
                    "score": round(s.get("score") or 0, 2),
                }
                for s in (sources or [])[:3]
            ],
        },
    }


def make_skill_progress(topic: str, state: StudentState) -> dict:
    return {
        "type": "SkillProgress",
        "widgetId": _widget_id("sp"),
        "category": "progress",
        "criticality": "low",
        "calibrability": "learner",
        "layout": {
            "size": "S",
            "cornerRadiusPreset": "baseline-16",
            "priority": "low",
        },
        "interactionLevel": "L1",
        "allowedActions": [],
        "payload": {
            "skill": topic,
            "score": state.mastery_score,
            "attempts": state.attempts,
        },
    }


def make_info_card(
    title: str, text: str, size: str = "M", primary: bool = False, category: str = "content"
) -> dict:
    return {
        "type": "InfoCard",
        "widgetId": _widget_id("info"),
        "category": category,
        "criticality": "urgent" if primary else "normal",
        "calibrability": "tenant",
        "layout": {
            "size": size,
            "cornerRadiusPreset": "primary-24" if primary else "baseline-16",
            "priority": "urgent" if primary else "normal",
        },
        "interactionLevel": "L1",
        "allowedActions": [],
        "payload": {"title": title, "text": text},
    }


def space_default(space: str) -> list:
    data = {
        "ANALYTICS": [
            make_info_card("📊 Прогресс за неделю", "4.2 часа обучения, 3 темы начаты, 1 тема усвоена.", "L", category="progress"),
            make_info_card("⏱ Средняя сессия", "12 минут — выше среднего по когорте.", "M", category="progress"),
            make_info_card("⚠ Слабое место", "Тема «юнит-экономика» — 2 неверных ответа.", "M", category="assessment"),
        ],
        "MARKETPLACE": [
            make_info_card("🛒 Рекомендуем", "«Продвинутая финансовая модель» — под твой трек.", "L", category="commerce"),
            make_info_card("⭐ Популярный", "«Основы юнит-экономики» — 1 247 студентов.", "M", category="commerce"),
        ],
        "PROFILE": [
            make_info_card("👤 Мой профиль", "Имя, аватар, биография. Редактирование — в полном приложении.", "L", category="profile"),
            make_info_card("🏅 Сертификаты", "1 получен, 3 ожидают финальный тест.", "M", category="profile"),
        ],
        "ONBOARDING": [
            make_info_card("🎯 Приветствуем", "Пройди короткий опрос, чтобы подобрать подходящий трек.", "L", primary=True, category="onboarding"),
            make_info_card("⏲ Ритм", "Выбери комфортный ритм: 15, 30 или 45 минут в день.", "M", category="onboarding"),
        ],
    }
    return data.get(space, [])


# ═══════════════════════════════════════════════════════════════════
# 5. FASTAPI APP
# ═══════════════════════════════════════════════════════════════════

app = FastAPI(title="Smart Line backend (notebook port)")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class RenderRequest(BaseModel):
    prompt: str
    space: str = "LEARNING"
    session_id: Optional[str] = Field(default=None, alias="sessionId")
    input_mode: str = Field(default="text", alias="inputMode")

    class Config:
        populate_by_name = True


class InteractRequest(BaseModel):
    session_id: str = Field(alias="sessionId")
    widget_id: Optional[str] = Field(default=None, alias="widgetId")
    action_id: str = Field(alias="actionId")
    input: dict = Field(default_factory=dict)

    class Config:
        populate_by_name = True


@app.exception_handler(Exception)
async def _unhandled(request: Request, exc: Exception):  # noqa: ARG001
    log.exception("unhandled error")
    return JSONResponse(status_code=500, content={"error": str(exc), "type": type(exc).__name__})


@app.get("/api/health")
def health():
    ok_key = bool(OPENAI_API_KEY)
    return {
        "ok": ok_key,
        "openai": ok_key,
        "qdrant": QDRANT_URL,
        "collection": COLLECTION,
        "chat_model": CHAT_MODEL,
        "embed_model": EMBED_MODEL,
        "sessions": len(SESSIONS),
    }


def _state_snapshot(state: StudentState) -> dict:
    return {
        "sessionId": state.session_id,
        "topic": state.topic,
        "attempts": state.attempts,
        "masteryScore": state.mastery_score,
        "consecutiveWrong": state.consecutive_wrong,
        "consecutiveChat": state.consecutive_chat,
        "lastStrategy": state.last_strategy,
        "explanationUsed": state.explanation_used,
        "misconceptions": state.misconceptions,
    }


@app.post("/api/render")
def render(req: RenderRequest):
    sid = req.session_id or f"sess-{uuid.uuid4().hex[:10]}"

    # Non-learning spaces: deterministic content, no LLM
    if req.space != "LEARNING":
        return {
            "sessionId": sid,
            "intent": "SPACE_DEFAULT",
            "widgets": space_default(req.space),
            "state": None,
        }

    # Fresh StudentState per new prompt (matches notebook: 1 orchestrator = 1 question)
    state = StudentState(session_id=sid, topic=req.prompt)
    SESSIONS[sid] = state

    # ШАГ 0: classify question type
    qtype = qtype_clf.classify(req.prompt)
    log.info("[%s] question type: %s", sid, qtype)

    # Retrieve RAG context (used for both organizational and learning)
    contexts = retrieve_context(req.prompt)
    state.contexts = contexts

    # ORGANIZATIONAL branch
    if qtype == "organizational":
        if not contexts:
            widgets = [make_answer_card(
                "В материалах курса ничего не нашлось",
                "В материалах курса нет информации по этому вопросу. Уточните у преподавателя.",
                sources=[], fallback=True,
            )]
        else:
            ctx_text = build_context(contexts[:5])
            answer_text = org_agent.respond(req.prompt, ctx_text)
            widgets = [make_answer_card(
                "Ответ из материалов курса",
                answer_text,
                sources=contexts[:3], fallback=False,
            )]
        return {"sessionId": sid, "intent": "ORGANIZATIONAL", "widgets": widgets, "state": _state_snapshot(state)}

    # OTHER branch — not course related
    if qtype == "other":
        widgets = [make_info_card(
            "Это не про курс",
            "Вопрос не связан с материалами курса. Попробуй переформулировать по теме курса.",
            size="L",
        )]
        return {"sessionId": sid, "intent": "OTHER", "widgets": widgets, "state": _state_snapshot(state)}

    # LEARNING branch — initial socratic step
    if not contexts:
        widgets = [make_answer_card(
            "В материалах курса ничего не нашлось",
            "В базе знаний курса нет информации по этой теме.",
            sources=[], fallback=True,
        )]
        return {"sessionId": sid, "intent": "LEARNING", "widgets": widgets, "state": _state_snapshot(state)}

    ctx_text = build_context(contexts[:5])
    plan = planner.plan(req.prompt, ctx_text, state, None, None)
    strategy = plan["strategy"]
    if strategy == "mastered":
        strategy = "socratic"  # fresh session, fall back

    tutor_msg = tutor.respond(
        strategy, req.prompt, contexts, state.history, state.misconceptions,
    )
    state.last_strategy = strategy
    state.last_tutor_msg = tutor_msg
    state.history.append(f"Тьютор: {tutor_msg}")

    if strategy == "explain":
        state.explanation_used = True
        state.force_next_strategy = "verify"
        widget = make_explanation_widget(
            f"Объяснение: {req.prompt}", tutor_msg, contexts[:3]
        )
    else:
        widget = make_ask_user_question(
            title=req.prompt,
            text=tutor_msg,
            interaction_level="L3",
        )
    state.active_widget_id = widget["widgetId"]

    return {
        "sessionId": sid,
        "intent": "LEARNING",
        "widgets": [widget, make_skill_progress(req.prompt, state)],
        "state": _state_snapshot(state),
        "strategy": strategy,
    }


@app.post("/api/interact")
def interact(req: InteractRequest):
    state = SESSIONS.get(req.session_id)
    if state is None:
        raise HTTPException(404, "Session not found — call /api/render first")

    if req.action_id != "smart_line:submit_answer":
        raise HTTPException(400, f"Unsupported action: {req.action_id}")

    raw_input = str(req.input.get("learner_answer") or req.input.get("learner_choice") or "").strip()
    if not raw_input:
        return {
            "sessionId": state.session_id,
            "widgets": [
                make_ask_user_question(
                    title=state.topic,
                    text=state.last_tutor_msg,
                    chat_reminder="Напиши ответ или поставь «?» для уточняющего вопроса.",
                ),
                make_skill_progress(state.topic, state),
            ],
            "state": _state_snapshot(state),
            "messageIntent": None,
        }

    # Message-level intent classification
    intent = intent_clf.classify(raw_input, state.last_tutor_msg, state.topic)
    log.info("[%s] message intent: %s", state.session_id, intent)

    # CHAT branch
    if intent == "chat":
        state.consecutive_chat += 1
        state.history.append(f"Студент: {raw_input}")

        if state.consecutive_chat >= 2:
            # Force explain
            state.consecutive_chat = 0
            state.consecutive_wrong += 1
            contexts = state.contexts or retrieve_context(state.topic)
            ctx_text = build_context(contexts[:5])
            tutor_msg = tutor.respond(
                "explain", state.topic, contexts, state.history, state.misconceptions,
            )
            state.last_strategy = "explain"
            state.explanation_used = True
            state.force_next_strategy = "verify"
            state.last_tutor_msg = tutor_msg
            state.history.append(f"Тьютор: {tutor_msg}")
            widget = make_explanation_widget(
                f"Объяснение: {state.topic}", tutor_msg, contexts[:3]
            )
            return {
                "sessionId": state.session_id,
                "widgets": [widget, make_skill_progress(state.topic, state)],
                "state": _state_snapshot(state),
                "messageIntent": "chat",
                "nextStrategy": "explain",
                "evaluation": None,
            }

        reply = chat_agent.respond_to_chat(
            raw_input, state.last_tutor_msg, state.topic, state.history,
        )
        state.history.append(f"Тьютор: {reply}")
        state.last_tutor_msg = reply
        widget = make_ask_user_question(
            title=state.topic, text=reply,
            chat_reminder="Похоже, это была чат-реплика. Напиши содержательный ответ.",
        )
        return {
            "sessionId": state.session_id,
            "widgets": [widget, make_skill_progress(state.topic, state)],
            "state": _state_snapshot(state),
            "messageIntent": "chat",
            "nextStrategy": state.last_strategy,
            "evaluation": None,
        }

    # QUESTION branch
    if intent == "question":
        contexts = state.contexts or retrieve_context(state.topic)
        reply = chat_agent.respond_to_question(
            raw_input, contexts, state.topic, state.history, state.last_tutor_msg,
        )
        state.history.append(f"Студент: {raw_input}")
        state.history.append(f"Тьютор: {reply}")
        state.last_tutor_msg = reply
        widget = make_ask_user_question(title=state.topic, text=reply)
        return {
            "sessionId": state.session_id,
            "widgets": [widget, make_skill_progress(state.topic, state)],
            "state": _state_snapshot(state),
            "messageIntent": "question",
            "nextStrategy": state.last_strategy,
            "evaluation": None,
        }

    # OFF_TOPIC branch
    if intent == "off_topic":
        widget = make_ask_user_question(
            title=state.topic, text=state.last_tutor_msg,
            chat_reminder="Сообщение не по теме — давай вернёмся к вопросу выше.",
        )
        return {
            "sessionId": state.session_id,
            "widgets": [widget, make_skill_progress(state.topic, state)],
            "state": _state_snapshot(state),
            "messageIntent": "off_topic",
            "nextStrategy": state.last_strategy,
            "evaluation": None,
        }

    # ANSWER branch — evaluate and plan next
    contexts = state.contexts or retrieve_context(state.topic)
    ctx_text = build_context(contexts[:5])
    evaluation = evaluator.evaluate(state.topic, raw_input, ctx_text)
    score = float(evaluation.get("score") or 0)

    state.mastery_score = score
    state.attempts += 1
    state.consecutive_chat = 0
    misconception = evaluation.get("misconception", "")
    if misconception and misconception not in state.misconceptions:
        state.misconceptions.append(misconception)
    if not evaluation.get("correct") and score < 0.5:
        state.consecutive_wrong += 1
    else:
        state.consecutive_wrong = 0
    state.history.append(f"Студент: {raw_input}")

    # Mastery termination
    if evaluation.get("correct") or score >= 0.85:
        widget = make_info_card(
            "🎉 Тема усвоена",
            f"Отличный ответ. {evaluation.get('feedback', '')}",
            size="L", primary=True, category="tutoring",
        )
        return {
            "sessionId": state.session_id,
            "widgets": [widget, make_skill_progress(state.topic, state)],
            "state": _state_snapshot(state),
            "messageIntent": "answer",
            "nextStrategy": "mastered",
            "evaluation": evaluation,
            "sessionStatus": "mastered",
        }

    # Determine next strategy
    if state.force_next_strategy:
        next_strategy = state.force_next_strategy
        state.force_next_strategy = None
    else:
        plan = planner.plan(state.topic, ctx_text, state, raw_input, evaluation)
        next_strategy = plan["strategy"]
        if next_strategy == "mastered":
            next_strategy = "verify"

    # Max attempts safeguard
    if state.attempts >= 6 and next_strategy != "verify":
        widget = make_info_card(
            "Лимит попыток достигнут",
            f"Сессия завершена. {evaluation.get('feedback', '')}",
            size="L", primary=True, category="tutoring",
        )
        return {
            "sessionId": state.session_id,
            "widgets": [widget, make_skill_progress(state.topic, state)],
            "state": _state_snapshot(state),
            "messageIntent": "answer",
            "nextStrategy": "max_attempts_reached",
            "evaluation": evaluation,
            "sessionStatus": "max_attempts_reached",
        }

    # Build the next tutor widget
    tutor_msg = tutor.respond(
        next_strategy, state.topic, contexts, state.history, state.misconceptions,
    )
    state.last_strategy = next_strategy
    state.last_tutor_msg = tutor_msg
    state.history.append(f"Тьютор: {tutor_msg}")
    if next_strategy == "explain":
        state.explanation_used = True
        state.force_next_strategy = "verify"
        widget = make_explanation_widget(
            f"Объяснение: {state.topic}", tutor_msg, contexts[:3]
        )
    else:
        widget = make_ask_user_question(
            title=state.topic, text=tutor_msg,
            interaction_level="L3", evaluation=evaluation,
        )
    state.active_widget_id = widget["widgetId"]

    return {
        "sessionId": state.session_id,
        "widgets": [widget, make_skill_progress(state.topic, state)],
        "state": _state_snapshot(state),
        "messageIntent": "answer",
        "nextStrategy": next_strategy,
        "evaluation": evaluation,
    }


# ─────────────────────────── static frontend ───────────────────────
# Mount *after* API routes so /api/* is routed to handlers above.
_STATIC_DIR = Path(__file__).parent.parent  # smart-line-prototype/
app.mount("/", StaticFiles(directory=str(_STATIC_DIR), html=True), name="static")


# ─────────────────────────── main ──────────────────────────────────
if __name__ == "__main__":
    import uvicorn

    log.info("Smart Line backend starting")
    log.info("  OPENAI_API_KEY: %s", "SET" if OPENAI_API_KEY else "NOT SET")
    log.info("  QDRANT: %s / %s", QDRANT_URL, COLLECTION)
    log.info("  CHAT_MODEL: %s   EMBED_MODEL: %s", CHAT_MODEL, EMBED_MODEL)

    uvicorn.run(app, host="127.0.0.1", port=5173, log_level="info")

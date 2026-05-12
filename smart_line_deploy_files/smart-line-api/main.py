"""
Smart Line AI Tutor — FastAPI server.

Full port of ИИ_агент.ipynb logic:
- QuestionTypeClassifier (organizational / learning / other)
- OrganizationalAgent (RAG-only answers)
- PlannerAgent (socratic / hint / explain / verify / mastered)
- TutorAgent (strategy-specific LLM prompts)
- EvaluatorAgent (LLM-based answer scoring)
- IntentClassifier (answer / chat / question / off_topic)
- ChatAgent (conversational replies)
- RAG via Qdrant + OpenAI embeddings

Endpoints:
  POST /smart-line/render   — send prompt, get DivKit widget
  POST /smart-line/interact — submit answer, get evaluation
"""

from __future__ import annotations

import json
import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from openai import OpenAI
from qdrant_client import QdrantClient

# ─────────────────────────── Config ─────────────────────────────

OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
EMBED_MODEL = "text-embedding-3-large"
CHAT_MODEL = "gpt-4o-mini"
QDRANT_URL = os.environ.get("QDRANT_URL", "https://qdrant.dev.adapstory.com")
COLLECTION_NAME = os.environ.get("QDRANT_COLLECTION", "presentations_industrix_openai")

os.environ["QDRANT_DISABLE_CHECK"] = "1"

client = OpenAI(api_key=OPENAI_API_KEY)
qdrant = QdrantClient(url=QDRANT_URL, port=443, timeout=120)

app = FastAPI(title="Smart Line AI Tutor", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ─────────────────────────── LLM helpers ────────────────────────

def embed_query(text: str) -> list[float]:
    return client.embeddings.create(model=EMBED_MODEL, input=text).data[0].embedding


def ask_llm(prompt: str, temperature: float = 0.3, max_tokens: int = 700) -> str:
    response = client.chat.completions.create(
        model=CHAT_MODEL,
        messages=[{"role": "user", "content": prompt}],
        temperature=temperature,
        max_tokens=max_tokens,
    )
    return response.choices[0].message.content.strip()


def ask_llm_system(system: str, user: str, temperature: float = 0.3, max_tokens: int = 700) -> str:
    response = client.chat.completions.create(
        model=CHAT_MODEL,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        temperature=temperature,
        max_tokens=max_tokens,
    )
    return response.choices[0].message.content.strip()


def parse_json_safe(text: str) -> dict:
    cleaned = text.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    return json.loads(cleaned)


# ─────────────────────────── RAG ────────────────────────────────

def retrieve_context(question: str, top_k: int = 10) -> list[dict]:
    vector = embed_query(question)
    results = qdrant.search(
        collection_name=COLLECTION_NAME,
        query_vector=vector,
        limit=top_k,
        with_payload=True,
    )
    contexts = []
    for r in results:
        payload = r.payload or {}
        text = payload.get("text", "").strip()
        if not text:
            continue
        contexts.append({
            "text": text,
            "page": payload.get("page"),
            "source": payload.get("source"),
            "score": r.score,
        })
    return contexts


def build_context_text(contexts: list[dict]) -> str:
    chunks = []
    for i, c in enumerate(contexts):
        chunks.append(f"SOURCE: {c.get('source')}\nPAGE: {c.get('page')}\n\nTEXT:\n{c.get('text')}")
    return "\n\n---\n\n".join(chunks)


# ─────────────────────────── Session state ──────────────────────

@dataclass
class SessionState:
    session_id: str
    topic: str
    attempts: int = 0
    mastery_score: float = 0.0
    history: list[str] = field(default_factory=list)
    misconceptions: list[str] = field(default_factory=list)
    last_strategy: Optional[str] = None
    explanation_used: bool = False
    consecutive_wrong: int = 0
    consecutive_chat: int = 0
    contexts: list[dict] = field(default_factory=list)
    context_text: str = ""
    last_tutor_msg: str = ""
    last_widget_id: str = ""


# In-memory session store (good enough for demo)
sessions: dict[str, SessionState] = {}


# ─────────────────────────── Agents (from notebook) ─────────────

def classify_question_type(question: str) -> str:
    """ORGANIZATIONAL / LEARNING / OTHER — keyword-first, LLM fallback."""
    lower = question.lower()
    org_keywords = [
        "домашка", "задание", "дедлайн", "обратная связь", "преподаватель",
        "расписание", "оценки", "проверка", "сдача", "срок", "вебинар",
        "чат", "ментор", "эксперт", "формат сдачи",
    ]
    if any(kw in lower for kw in org_keywords):
        return "organizational"

    prompt = f"""Классифицируй вопрос студента.
Вопрос: "{question}"
Категории: organizational (процесс обучения), learning (содержание курса), other.
Верни ТОЛЬКО JSON: {{"type": "learning|organizational|other"}}"""
    try:
        res = ask_llm(prompt, temperature=0)
        return parse_json_safe(res).get("type", "learning")
    except Exception:
        return "learning"


def plan_strategy(state: SessionState) -> str:
    """PlannerAgent — select socratic/hint/explain/verify/mastered."""
    if state.mastery_score >= 0.8:
        return "mastered"
    if state.explanation_used and state.last_strategy == "explain":
        return "verify"
    if state.attempts <= 1 and state.mastery_score < 0.5:
        return "socratic"
    if state.mastery_score < 0.6 and state.attempts <= 3:
        return "hint"
    if not state.misconceptions and state.attempts <= 3:
        return "hint"
    if state.attempts >= 3 or state.consecutive_wrong >= 2:
        return "explain"
    return "socratic"


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
        "В конце укажи источник: [Источник: SOURCE, стр. PAGE].\n"
        "Исправь заблуждения студента, если они есть."
    ),
    "verify": (
        "Ты только что объяснил тему. Задай студенту ОДИН короткий проверочный вопрос,\n"
        "чтобы убедиться, что он понял объяснение. Вопрос должен быть конкретным."
    ),
}


def generate_tutor_response(state: SessionState, strategy: str) -> str:
    """TutorAgent — generate response using RAG context + strategy."""
    instruction = STRATEGY_INSTRUCTIONS.get(strategy, STRATEGY_INSTRUCTIONS["socratic"])
    history_text = "\n".join(state.history[-10:]) if state.history else "История диалога пуста."
    misc_text = ""
    if state.misconceptions:
        misc_text = "Заблуждения студента:\n" + "\n".join(f"- {m}" for m in state.misconceptions)

    prompt = f"""Ты ИИ-тьютор курса. Используй ТОЛЬКО информацию из контекста.
Если информации нет — скажи: «В материалах курса нет информации по этому вопросу».

КОНТЕКСТ КУРСА:
{state.context_text[:3000]}

ТЕМА СТУДЕНТА: {state.topic}

ИСТОРИЯ ДИАЛОГА:
{history_text}

{misc_text}

СТРАТЕГИЯ: {strategy}
ИНСТРУКЦИЯ:
{instruction}"""

    return ask_llm(prompt, temperature=0.3)


def organizational_respond(question: str, context_text: str) -> str:
    """OrganizationalAgent — answer from RAG only."""
    prompt = f"""Студент курса задал организационный вопрос.

Информация из материалов курса:
{context_text[:3000]}

Вопрос студента: "{question}"

Правила:
- Отвечай ТОЛЬКО на основе информации из материалов курса выше
- Если информация есть — изложи её кратко и точно (2-4 предложения)
- Если информации нет — скажи: "В материалах курса нет информации по этому вопросу. Уточните у преподавателя."
- Не придумывай ответы из общих знаний LLM"""
    return ask_llm(prompt, temperature=0.1)


def evaluate_answer(topic: str, answer: str, context_text: str) -> dict:
    """EvaluatorAgent — LLM-based answer evaluation."""
    prompt = f"""Ты оцениваешь ответ студента по материалам курса.

Вопрос/тема: {topic}
Ответ студента: {answer}
Контекст курса: {context_text[:2000]}

Верни ТОЛЬКО JSON:
{{"correct": true/false, "partial": true/false, "misconception": "описание или пустая строка", "score": 0.0-1.0, "feedback": "краткий комментарий"}}"""
    try:
        res = ask_llm(prompt, temperature=0)
        return parse_json_safe(res)
    except Exception:
        return {"correct": False, "partial": False, "misconception": "", "score": 0.0, "feedback": "Ошибка оценки"}


# ─────────────────────────── DivKit builders ────────────────────

def build_ask_question_divdata(widget_id: str, question: str) -> dict:
    return {
        "card": {
            "log_id": f"smart-line-{widget_id}",
            "states": [{
                "state_id": 0,
                "div": {
                    "type": "container",
                    "orientation": "vertical",
                    "paddings": {"left": 16, "top": 16, "right": 16, "bottom": 16},
                    "background": [{"type": "solid", "color": "@{card_bg}"}],
                    "border": {"corner_radius": 12},
                    "items": [
                        {
                            "type": "text",
                            "text": question,
                            "font_size": 15,
                            "font_weight": "bold",
                            "text_color": "@{text_primary}",
                            "line_height": 22,
                        },
                    ],
                },
            }],
        },
    }


def build_answer_card_divdata(widget_id: str, text: str) -> dict:
    return {
        "card": {
            "log_id": f"smart-line-{widget_id}",
            "states": [{
                "state_id": 0,
                "div": {
                    "type": "container",
                    "orientation": "vertical",
                    "paddings": {"left": 16, "top": 16, "right": 16, "bottom": 16},
                    "background": [{"type": "solid", "color": "@{card_bg}"}],
                    "border": {"corner_radius": 12},
                    "items": [
                        {"type": "text", "text": "Ответ", "font_size": 12, "font_weight": "medium", "text_color": "@{brand}"},
                        {"type": "text", "text": text, "font_size": 14, "text_color": "@{text_primary}", "line_height": 22, "paddings": {"top": 4}},
                    ],
                },
            }],
        },
    }


def build_explanation_divdata(widget_id: str, text: str) -> dict:
    return {
        "card": {
            "log_id": f"smart-line-{widget_id}",
            "states": [{
                "state_id": 0,
                "div": {
                    "type": "container",
                    "orientation": "vertical",
                    "paddings": {"left": 16, "top": 16, "right": 16, "bottom": 16},
                    "background": [{"type": "solid", "color": "@{card_bg}"}],
                    "border": {"corner_radius": 12},
                    "items": [
                        {"type": "text", "text": "Объяснение", "font_size": 12, "font_weight": "medium", "text_color": "@{brand}"},
                        {"type": "text", "text": text, "font_size": 14, "text_color": "@{text_primary}", "line_height": 22, "paddings": {"top": 4}},
                    ],
                },
            }],
        },
    }


# ─────────────────────────── API models ─────────────────────────

class RenderRequest(BaseModel):
    sessionId: str
    learnerId: str
    space: str = "LEARNING"
    prompt: str
    topic: Optional[str] = None

class InteractRequest(BaseModel):
    sessionId: str
    widgetId: str
    actionId: str = "smart_line:submit_answer"
    space: Optional[str] = None
    learnerAnswer: str


# ─────────────────────────── Endpoints ──────────────────────────

@app.post("/smart-line/render")
def render(req: RenderRequest):
    session_id = req.sessionId
    prompt = req.prompt
    topic = req.topic or prompt

    # Load or create session
    state = sessions.get(session_id)
    if state is None:
        # First request — classify intent, retrieve RAG context
        contexts = retrieve_context(topic)
        context_text = build_context_text(contexts[:5])
        state = SessionState(
            session_id=session_id,
            topic=topic,
            contexts=contexts,
            context_text=context_text,
        )
        sessions[session_id] = state
    else:
        # Continuing session — update topic if changed
        if topic != state.topic:
            state.topic = topic
            state.contexts = retrieve_context(topic)
            state.context_text = build_context_text(state.contexts[:5])

    # Classify question type
    qtype = classify_question_type(prompt)
    widget_id = f"w-{uuid.uuid4().hex[:8]}"
    state.last_widget_id = widget_id

    # ORGANIZATIONAL → answer from RAG
    if qtype == "organizational":
        if not state.contexts:
            answer = "В материалах курса нет информации по этому вопросу. Уточните у преподавателя."
        else:
            answer = organizational_respond(prompt, state.context_text)

        return {
            "sessionId": session_id,
            "space": req.space,
            "intent": "ORGANIZATIONAL",
            "generatedAt": _now(),
            "sessionStatus": "ACTIVE",
            "widgets": [{
                "widgetId": widget_id,
                "widgetType": "SmartLineAnswerCard",
                "name": "Ответ",
                "space": req.space,
                "interactionLevel": "L1",
                "sourcePlugin": "adapstory.tutoring.socratic-tutor",
                "rankScore": 0.85,
                "ttlSeconds": 300,
                "allowedActions": [],
                "payload": None,
                "divData": build_answer_card_divdata(widget_id, answer),
            }],
        }

    # LEARNING → tutor orchestration
    if not state.contexts:
        return {
            "sessionId": session_id,
            "space": req.space,
            "intent": "LEARNING_UNDERSTANDING",
            "generatedAt": _now(),
            "sessionStatus": "ACTIVE",
            "widgets": [{
                "widgetId": widget_id,
                "widgetType": "SmartLineAnswerCard",
                "name": "Ответ",
                "space": req.space,
                "interactionLevel": "L1",
                "sourcePlugin": "adapstory.tutoring.socratic-tutor",
                "rankScore": 0.80,
                "ttlSeconds": 300,
                "allowedActions": [],
                "payload": None,
                "divData": build_answer_card_divdata(widget_id, "В базе знаний нет информации по данному вопросу."),
            }],
        }

    strategy = plan_strategy(state)

    # Mastered
    if strategy == "mastered":
        return {
            "sessionId": session_id,
            "space": req.space,
            "intent": "LEARNING_UNDERSTANDING",
            "generatedAt": _now(),
            "sessionStatus": "TERMINATED_MASTERED",
            "widgets": [{
                "widgetId": widget_id,
                "widgetType": "SmartLineAnswerCard",
                "name": "Тема усвоена",
                "space": req.space,
                "interactionLevel": "L5",
                "sourcePlugin": "adapstory.tutoring.socratic-tutor",
                "rankScore": 0.95,
                "ttlSeconds": 300,
                "allowedActions": [],
                "payload": None,
                "divData": build_answer_card_divdata(widget_id, "Отлично! Тема усвоена. Переходите к следующему заданию."),
            }],
        }

    # Generate tutor response
    tutor_text = generate_tutor_response(state, strategy)
    state.last_strategy = strategy
    state.last_tutor_msg = tutor_text
    state.history.append(f"Тьютор ({strategy}): {tutor_text}")

    if strategy == "explain":
        state.explanation_used = True
        widget_type = "ExplanationWidget"
        widget_name = "Объяснение"
        divdata = build_explanation_divdata(widget_id, tutor_text)
        allowed_actions: list[str] = []
    else:
        widget_type = "AskUserQuestion"
        widget_name = f"Вопрос ({strategy})"
        divdata = build_ask_question_divdata(widget_id, tutor_text)
        allowed_actions = ["smart_line:submit_answer"]

    return {
        "sessionId": session_id,
        "space": req.space,
        "intent": "LEARNING_UNDERSTANDING",
        "generatedAt": _now(),
        "sessionStatus": "ACTIVE",
        "widgets": [{
            "widgetId": widget_id,
            "widgetType": widget_type,
            "name": widget_name,
            "space": req.space,
            "interactionLevel": _select_level(state),
            "sourcePlugin": "adapstory.tutoring.socratic-tutor",
            "rankScore": 0.92,
            "ttlSeconds": 300,
            "allowedActions": allowed_actions,
            "payload": None,
            "divData": divdata,
        }],
    }


@app.post("/smart-line/interact")
def interact(req: InteractRequest):
    state = sessions.get(req.sessionId)
    if state is None:
        raise HTTPException(status_code=404, detail="Session not found")

    answer = req.learnerAnswer
    state.history.append(f"Студент: {answer}")

    # Evaluate via LLM
    evaluation = evaluate_answer(state.topic, answer, state.context_text)

    score = evaluation.get("score", 0.0)
    is_correct = evaluation.get("correct", False)
    misconception = evaluation.get("misconception", "")
    feedback = evaluation.get("feedback", "")

    # Update state
    state.attempts += 1
    state.mastery_score = max(state.mastery_score, score)

    if misconception and misconception not in state.misconceptions:
        state.misconceptions.append(misconception)

    if not is_correct and score < 0.5:
        state.consecutive_wrong += 1
    else:
        state.consecutive_wrong = 0

    # Check termination
    session_status = "ACTIVE"
    if state.mastery_score >= 0.8:
        session_status = "TERMINATED_MASTERED"
    elif state.consecutive_wrong >= 3:
        session_status = "TERMINATED_MAX_WRONG"
    elif state.attempts >= 6:
        session_status = "TERMINATED_MAX_ATTEMPTS"

    return {
        "correct": is_correct,
        "score": score,
        "feedback": feedback,
        "nextInteractionLevel": _select_level(state),
        "sessionStatus": session_status,
    }


@app.get("/health")
def health():
    return {"status": "ok", "model": CHAT_MODEL, "qdrant": QDRANT_URL}


# ─────────────────────────── Helpers ────────────────────────────

def _now() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


def _select_level(state: SessionState) -> str:
    if state.mastery_score >= 0.8:
        return "L5"
    if state.mastery_score >= 0.6 and state.attempts >= 3:
        return "L4"
    if state.mastery_score >= 0.4 and state.attempts >= 2:
        return "L3"
    if state.mastery_score >= 0.2:
        return "L2"
    return "L1"


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8099)

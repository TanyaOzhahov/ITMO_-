import type { DivJson } from '@/src/components/divkit/divkit.types'
import type { SmartLineRenderResponse, SmartLineInteractResponse } from '../smart-line.types'

// ---------------------------------------------------------------------------
// Mock DivKit widgets for Smart Line demo
// ---------------------------------------------------------------------------

/** Socratic question widget — asks a probing question. */
function createSocraticQuestionDivData(widgetId: string, question: string): DivJson {
  return {
    card: {
      log_id: `smart-line-${widgetId}`,
      states: [
        {
          state_id: 0,
          div: {
            type: 'container',
            orientation: 'vertical',
            paddings: { left: 16, top: 16, right: 16, bottom: 16 },
            background: [{ type: 'solid', color: '@{card_bg}' }],
            border: { corner_radius: 12 },
            items: [
              {
                type: 'text',
                text: question,
                font_size: 15,
                font_weight: 'bold',
                text_color: '@{text_primary}',
                line_height: 22,
              },
              {
                type: 'input',
                text_variable: 'learner_answer',
                hint_text: 'Введите ваш ответ...',
                font_size: 14,
                text_color: '@{text_primary}',
                hint_color: '@{text_secondary}',
                paddings: { left: 12, top: 10, right: 12, bottom: 10 },
                margins: { top: 12 },
                border: { corner_radius: 8, stroke: { color: '#E5E7EB', width: 1 } },
                keyboard_type: 'default',
                line_height: 20,
                accessibility: {
                  type: 'auto',
                  description: 'Поле для ответа',
                },
              },
              {
                type: 'text',
                text: 'Ответить',
                font_size: 14,
                font_weight: 'bold',
                text_color: '#FFFFFF',
                text_alignment_horizontal: 'center',
                paddings: { left: 16, top: 12, right: 16, bottom: 12 },
                margins: { top: 12 },
                background: [{ type: 'solid', color: '@{brand}' }],
                border: { corner_radius: 10 },
                actions: [
                  {
                    log_id: `submit-answer-${widgetId}`,
                    url: `action://smart_line/submit_answer/${widgetId}`,
                  },
                ],
                accessibility: {
                  type: 'button',
                  description: 'Отправить ответ',
                },
              },
            ],
          },
        },
      ],
    },
  }
}

/** Explanation widget — shows an explanation from course materials. */
function createExplanationDivData(widgetId: string, text: string): DivJson {
  return {
    card: {
      log_id: `smart-line-${widgetId}`,
      states: [
        {
          state_id: 0,
          div: {
            type: 'container',
            orientation: 'vertical',
            paddings: { left: 16, top: 16, right: 16, bottom: 16 },
            background: [{ type: 'solid', color: '@{card_bg}' }],
            border: { corner_radius: 12 },
            items: [
              {
                type: 'text',
                text,
                font_size: 14,
                text_color: '@{text_primary}',
                line_height: 22,
              },
            ],
          },
        },
      ],
    },
  }
}

/** Answer card widget — simple text answer for organizational questions. */
function createAnswerCardDivData(widgetId: string, text: string): DivJson {
  return {
    card: {
      log_id: `smart-line-${widgetId}`,
      states: [
        {
          state_id: 0,
          div: {
            type: 'container',
            orientation: 'vertical',
            paddings: { left: 16, top: 16, right: 16, bottom: 16 },
            background: [{ type: 'solid', color: '@{card_bg}' }],
            border: { corner_radius: 12 },
            items: [
              {
                type: 'text',
                text: 'Ответ',
                font_size: 12,
                font_weight: 'medium',
                text_color: '@{brand}',
              },
              {
                type: 'text',
                text,
                font_size: 14,
                text_color: '@{text_primary}',
                line_height: 22,
                paddings: { top: 4 },
              },
            ],
          },
        },
      ],
    },
  }
}

// ---------------------------------------------------------------------------
// Prompt → Response mapping for demo scenarios
// ---------------------------------------------------------------------------

interface MockScenario {
  keywords: string[]
  intent: SmartLineRenderResponse['intent']
  widgetType: string
  widgetName: string
  createDivData: (widgetId: string) => DivJson
}

const MOCK_SCENARIOS: MockScenario[] = [
  {
    keywords: ['юнит-экономика', 'unit economics', 'юнит экономика'],
    intent: 'LEARNING_UNDERSTANDING',
    widgetType: 'AskUserQuestion',
    widgetName: 'Сократический вопрос',
    createDivData: (id) =>
      createSocraticQuestionDivData(
        id,
        'Как вы думаете, что измеряет юнит-экономика для одного клиента? Подумайте о разнице между тем, сколько стоит привлечь клиента, и сколько он приносит.',
      ),
  },
  {
    keywords: ['декоратор', 'decorator', 'python'],
    intent: 'LEARNING_UNDERSTANDING',
    widgetType: 'AskUserQuestion',
    widgetName: 'Сократический вопрос',
    createDivData: (id) =>
      createSocraticQuestionDivData(
        id,
        'Декоратор в Python — это функция, которая принимает другую функцию. Как вы думаете, зачем это нужно? Какую задачу это решает?',
      ),
  },
  {
    keywords: ['домашка', 'задание', 'дедлайн', 'сдача', 'расписание', 'обратная связь'],
    intent: 'ORGANIZATIONAL',
    widgetType: 'SmartLineAnswerCard',
    widgetName: 'Ответ',
    createDivData: (id) =>
      createAnswerCardDivData(
        id,
        'Домашние задания сдаются через платформу до конца недели (воскресенье, 23:59 МСК). Обратная связь от куратора приходит в течение 2 рабочих дней. Если нужно продление — напишите куратору в чат.',
      ),
  },
  {
    keywords: ['прогресс', 'статистика', 'результат', 'аналитика'],
    intent: 'PROGRESS_REFLECTION',
    widgetType: 'SmartLineAnswerCard',
    widgetName: 'Прогресс',
    createDivData: (id) =>
      createAnswerCardDivData(
        id,
        'Вы прошли 60% курса. Средний балл по тестам — 78%. Рекомендуем повторить тему "Финансовые модели" перед финальным тестом.',
      ),
  },
]

/** Default fallback for unknown prompts. */
const DEFAULT_SCENARIO: MockScenario = {
  keywords: [],
  intent: 'LEARNING_UNDERSTANDING',
  widgetType: 'AskUserQuestion',
  widgetName: 'Вопрос',
  createDivData: (id) =>
    createSocraticQuestionDivData(
      id,
      'Интересный вопрос! Давайте разберёмся вместе. Расскажите, что вы уже знаете по этой теме?',
    ),
}

// ---------------------------------------------------------------------------
// Public mock factory functions
// ---------------------------------------------------------------------------

let interactionCount = 0

/**
 * Create a mock render response based on prompt text.
 * Matches keywords to determine intent and widget type.
 */
export function createMockRenderResponse(
  sessionId: string,
  space: string,
  prompt: string,
): SmartLineRenderResponse {
  const lower = prompt.toLowerCase()
  const scenario = MOCK_SCENARIOS.find((s) => s.keywords.some((kw) => lower.includes(kw))) ?? DEFAULT_SCENARIO
  const widgetId = `w-sl-${Date.now().toString(36)}`

  return {
    sessionId,
    space,
    intent: scenario.intent,
    generatedAt: new Date().toISOString(),
    sessionStatus: 'ACTIVE',
    widgets: [
      {
        widgetId,
        widgetType: scenario.widgetType,
        name: scenario.widgetName,
        space,
        interactionLevel: 'L1',
        sourcePlugin: 'adapstory.tutoring.socratic-tutor',
        rankScore: 0.92,
        ttlSeconds: 300,
        allowedActions: ['smart_line:submit_answer'],
        payload: null,
        divData: scenario.createDivData(widgetId),
      },
    ],
  }
}

/**
 * Create a mock interaction response.
 * Alternates between correct/partial answers for demo variety.
 */
export function createMockInteractResponse(learnerAnswer: string): SmartLineInteractResponse {
  interactionCount++
  const isCorrect = interactionCount % 3 !== 0 // 2 correct, 1 wrong cycle
  const score = isCorrect ? 0.75 + Math.random() * 0.2 : 0.2 + Math.random() * 0.3

  return {
    correct: isCorrect,
    score: Math.round(score * 100) / 100,
    feedback: isCorrect
      ? 'Хорошо! Вы на правильном пути. Попробуйте развить мысль подробнее.'
      : 'Не совсем. Подумайте ещё раз — обратите внимание на ключевое отличие.',
    nextInteractionLevel: isCorrect ? 'L2' : 'L1',
    sessionStatus: score > 0.85 ? 'TERMINATED_MASTERED' : 'ACTIVE',
  }
}

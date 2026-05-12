'use client'

import { useCallback, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { apiClient } from '@/src/lib/api-client'
import { SMART_LINE_BFF_PATH } from '../smart-line.constants'
import {
  createMockRenderResponse,
  createMockInteractResponse,
} from '../mocks/smart-line-mock-data'
import type {
  SmartLineInteractRequest,
  SmartLineInteractResponse,
  SmartLineRenderRequest,
  SmartLineRenderResponse,
  SmartLineSpace,
  SmartLineState,
} from '../smart-line.types'

/**
 * Mock mode: `true` = local mock data (no backend).
 * Real AI mode: `false` = calls FastAPI AI server.
 *
 * Set NEXT_PUBLIC_SMART_LINE_MOCK=false to use real AI.
 * Set NEXT_PUBLIC_SMART_LINE_API_URL to override API base (default: http://localhost:8099).
 */
const USE_MOCK = process.env.NEXT_PUBLIC_SMART_LINE_MOCK !== 'false'
const AI_API_URL = process.env.NEXT_PUBLIC_SMART_LINE_API_URL ?? 'http://localhost:8099'

function generateSessionId(): string {
  return crypto.randomUUID()
}

/**
 * Hook для управления Smart Line — отправка промптов, обработка ответов, взаимодействие.
 *
 * Если `NEXT_PUBLIC_SMART_LINE_MOCK !== 'false'` — работает на моках без бэкенда.
 * Иначе вызывает BFF endpoints.
 */
export function useSmartLine(learnerId: string) {
  const [state, setState] = useState<SmartLineState>({
    isOpen: false,
    activeSpace: 'LEARNING',
    sessionId: null,
    widgets: [],
    intent: null,
    sessionStatus: 'ACTIVE',
    isLoading: false,
  })

  // ─────────────────── Render mutation ───────────────────

  const renderMutation = useMutation({
    mutationFn: async (body: SmartLineRenderRequest): Promise<SmartLineRenderResponse> => {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 800 + Math.random() * 600))
        return createMockRenderResponse(body.sessionId, body.space, body.prompt)
      }
      // Call FastAPI AI server directly (bypasses BFF for demo)
      const res = await fetch(`${AI_API_URL}/smart-line/render`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!res.ok) throw new Error(`AI server error: ${res.status}`)
      return res.json()
    },
    onMutate: () => {
      setState((prev) => ({ ...prev, isLoading: true }))
    },
    onSuccess: (data) => {
      setState((prev) => ({
        ...prev,
        sessionId: data.sessionId,
        widgets: data.widgets,
        intent: data.intent,
        sessionStatus: data.sessionStatus,
        isLoading: false,
        isOpen: true,
      }))
    },
    onError: () => {
      setState((prev) => ({ ...prev, isLoading: false }))
    },
  })

  // ─────────────────── Interact mutation ─────────────────

  const interactMutation = useMutation({
    mutationFn: async (body: SmartLineInteractRequest): Promise<SmartLineInteractResponse> => {
      if (USE_MOCK) {
        await new Promise((r) => setTimeout(r, 500 + Math.random() * 400))
        return createMockInteractResponse(body.learnerAnswer)
      }
      const res = await fetch(`${AI_API_URL}/smart-line/interact`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!res.ok) throw new Error(`AI server error: ${res.status}`)
      return res.json()
    },
    onSuccess: (data) => {
      setState((prev) => ({
        ...prev,
        sessionStatus: data.sessionStatus,
      }))
    },
  })

  // ─────────────────── Actions ───────────────────────────

  const sendPrompt = useCallback(
    (prompt: string, topic?: string) => {
      const sessionId = state.sessionId ?? generateSessionId()

      if (!state.sessionId) {
        setState((prev) => ({ ...prev, sessionId }))
      }

      renderMutation.mutate({
        sessionId,
        learnerId,
        space: state.activeSpace,
        prompt,
        topic,
      })
    },
    [state.sessionId, state.activeSpace, learnerId, renderMutation],
  )

  const submitAnswer = useCallback(
    (widgetId: string, learnerAnswer: string) => {
      if (!state.sessionId) return

      interactMutation.mutate({
        sessionId: state.sessionId,
        widgetId,
        actionId: 'smart_line:submit_answer',
        space: state.activeSpace,
        learnerAnswer,
      })
    },
    [state.sessionId, state.activeSpace, interactMutation],
  )

  const switchSpace = useCallback((space: SmartLineSpace) => {
    setState((prev) => ({ ...prev, activeSpace: space }))
  }, [])

  const toggle = useCallback(() => {
    setState((prev) => ({ ...prev, isOpen: !prev.isOpen }))
  }, [])

  const close = useCallback(() => {
    setState((prev) => ({ ...prev, isOpen: false }))
  }, [])

  return {
    state,
    sendPrompt,
    submitAnswer,
    switchSpace,
    toggle,
    close,
    isRendering: renderMutation.isPending,
    isInteracting: interactMutation.isPending,
    interactResult: interactMutation.data ?? null,
    renderError: renderMutation.error,
    interactError: interactMutation.error,
  }
}

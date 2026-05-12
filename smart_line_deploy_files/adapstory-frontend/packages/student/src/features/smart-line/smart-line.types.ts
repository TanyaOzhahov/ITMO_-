import type { DivJson } from '@/src/components/divkit/divkit.types'

/** Canonical Smart Line spaces (architecture v1). */
export type SmartLineSpace = 'LEARNING' | 'ANALYTICS' | 'MARKETPLACE' | 'PROFILE' | 'ONBOARDING'

/** Intent resolved by BC-16 IntentRouter. */
export type SmartLineIntent =
  | 'ORGANIZATIONAL'
  | 'LEARNING_UNDERSTANDING'
  | 'NAVIGATION_DISCOVERY'
  | 'PROGRESS_REFLECTION'
  | 'ACCOUNT_PROFILE'
  | 'UNSUPPORTED'

/** Widget projection returned from backend. */
export interface SmartLineWidget {
  widgetId: string
  widgetType: string
  name: string
  space: string
  interactionLevel: string
  sourcePlugin: string
  rankScore: number
  ttlSeconds: number
  allowedActions: string[]
  payload: Record<string, unknown> | null
  divData: DivJson
}

/** POST /smart-line/render — request body. */
export interface SmartLineRenderRequest {
  sessionId: string
  learnerId: string
  space: SmartLineSpace
  prompt: string
  topic?: string
  contextScope?: {
    courseId?: string
    lessonId?: string
    pageId?: string
  }
}

/** POST /smart-line/render — response. */
export interface SmartLineRenderResponse {
  sessionId: string
  space: string
  intent: SmartLineIntent
  generatedAt: string
  sessionStatus: string
  widgets: SmartLineWidget[]
}

/** POST /smart-line/interact — request body. */
export interface SmartLineInteractRequest {
  sessionId: string
  widgetId: string
  actionId: string
  space?: SmartLineSpace
  learnerAnswer: string
}

/** POST /smart-line/interact — response. */
export interface SmartLineInteractResponse {
  correct: boolean
  score: number
  feedback: string
  nextInteractionLevel: string
  sessionStatus: string
}

/** Local UI state for the Smart Line panel. */
export interface SmartLineState {
  isOpen: boolean
  activeSpace: SmartLineSpace
  sessionId: string | null
  widgets: SmartLineWidget[]
  intent: SmartLineIntent | null
  sessionStatus: string
  isLoading: boolean
}

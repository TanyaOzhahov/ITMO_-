'use client'

import { useEffect } from 'react'
import { useSmartLine } from '../hooks/use-smart-line'
import { SmartLinePanel } from './SmartLinePanel'
import {
  registerActionHandler,
  unregisterActionHandler,
} from '@/src/components/divkit/action-handlers'

interface SmartLineProviderProps {
  learnerId: string
}

/**
 * SmartLineProvider — mounts SmartLinePanel and registers the
 * `smart_line` DivKit action handler so that widget buttons
 * (e.g., "Ответить") can trigger the interact flow.
 *
 * Place this component once in the portal layout.
 */
export function SmartLineProvider({ learnerId }: SmartLineProviderProps) {
  const smartLine = useSmartLine(learnerId)

  // Register DivKit action handler for smart_line:submit_answer
  useEffect(() => {
    registerActionHandler('smart_line', (params: string) => {
      // params format: "submit_answer/{widgetId}" or just "submit_answer"
      const parts = params.split('/')
      const command = parts[0]
      const widgetId = parts[1] ?? ''

      if (command === 'submit_answer' && widgetId) {
        // Find the input value from the DivKit rendered widget
        const container = document.querySelector(`[data-divkit-id="smart-line-${widgetId}"]`)
        const input = container?.querySelector<HTMLInputElement>('input, textarea')
        const answer = input?.value ?? ''

        if (answer.trim()) {
          smartLine.submitAnswer(widgetId, answer)
        }
      }
    })

    return () => {
      unregisterActionHandler('smart_line')
    }
  }, [smartLine])

  return <SmartLinePanel smartLine={smartLine} />
}

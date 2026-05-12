'use client'

import { DivKitRenderer } from '@/src/components/divkit/DivKitRenderer'
import { useTenantThemeContext } from '@/src/features/theme'
import { cn } from '@/lib/utils'
import type { SmartLineWidget } from '../smart-line.types'

interface SmartLineWidgetRendererProps {
  widget: SmartLineWidget
  onCustomAction?: (action: { url: string; [key: string]: unknown }) => void
}

/**
 * Renders a single Smart Line widget using DivKitRenderer.
 *
 * Wraps widget.divData in the standard DivKit JSON structure
 * (card > states > div) expected by the SDK.
 */
export function SmartLineWidgetRenderer({ widget, onCustomAction }: SmartLineWidgetRendererProps) {
  const { divkitPalette, resolvedMode } = useTenantThemeContext()

  // Wrap raw divData into DivKit card structure if needed
  const divJson = normalizeDivData(widget)

  return (
    <div
      className={cn(
        'mb-3 overflow-hidden rounded-xl border border-neutral-200 dark:border-neutral-700',
        'bg-white dark:bg-main-800',
      )}
    >
      {/* Widget header */}
      <div className="flex items-center justify-between border-b border-neutral-100 px-3 py-2 dark:border-neutral-700">
        <span className="text-xs font-medium text-neutral-500">{widget.name}</span>
        <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-[10px] text-neutral-400 dark:bg-main-700">
          {widget.interactionLevel}
        </span>
      </div>

      {/* DivKit render area */}
      <div className="p-3">
        <DivKitRenderer
          data={divJson}
          id={`smart-line-${widget.widgetId}`}
          theme={resolvedMode === 'dark' ? 'dark' : 'light'}
          palette={divkitPalette}
          onCustomAction={onCustomAction}
        />
      </div>
    </div>
  )
}

/**
 * Normalize widget divData into DivKit card format.
 *
 * The backend may return raw container JSON (not wrapped in card/states).
 * This ensures the SDK always gets the expected structure.
 */
function normalizeDivData(widget: SmartLineWidget) {
  const raw = widget.divData

  // If already has card.states — pass through
  if (raw && 'card' in raw && raw.card) {
    return raw
  }

  // Wrap raw div element into card structure
  return {
    card: {
      log_id: `smart-line-${widget.widgetId}`,
      states: [
        {
          state_id: 0,
          div: raw ?? {
            type: 'text',
            text: 'Widget content unavailable',
          },
        },
      ],
    },
  }
}

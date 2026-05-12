'use client'

import { type FormEvent, useRef, useState } from 'react'
import { MessageCircle, Send, X, Loader2, CornerDownLeft } from 'lucide-react'
import { cn } from '@/lib/utils'
import { SMART_LINE_SPACES } from '../smart-line.constants'
import type { SmartLineSpace } from '../smart-line.types'
import type { useSmartLine } from '../hooks/use-smart-line'
import { SmartLineWidgetRenderer } from './SmartLineWidgetRenderer'

type SmartLineHook = ReturnType<typeof useSmartLine>

interface SmartLinePanelProps {
  smartLine: SmartLineHook
}

/**
 * SmartLinePanel — floating bottom panel for student AI interaction.
 *
 * Input field is dual-mode:
 * - No active question widget → sends a NEW prompt (renders a widget)
 * - Active AskUserQuestion widget → submits ANSWER to the current question
 *
 * This avoids the problem of reading DivKit input variables from DOM.
 */
export function SmartLinePanel({ smartLine }: SmartLinePanelProps) {
  const {
    state,
    sendPrompt,
    submitAnswer,
    switchSpace,
    toggle,
    close,
    isRendering,
    isInteracting,
    interactResult,
  } = smartLine
  const [inputValue, setInputValue] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)

  // Check if there's an active question widget awaiting an answer
  const activeQuestionWidget = state.widgets.find(
    (w) => w.widgetType === 'AskUserQuestion' && state.sessionStatus === 'ACTIVE',
  )
  const isAnswerMode = !!activeQuestionWidget && !interactResult
  const isBusy = isRendering || isInteracting

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const trimmed = inputValue.trim()
    if (!trimmed || isBusy) return

    if (isAnswerMode && activeQuestionWidget) {
      // Submit answer to the current question widget
      submitAnswer(activeQuestionWidget.widgetId, trimmed)
    } else {
      // Send new prompt
      sendPrompt(trimmed)
    }
    setInputValue('')
  }

  function handleNewQuestion() {
    // After seeing feedback, allow asking a new question
    setInputValue('')
    inputRef.current?.focus()
  }

  // ─────────────── Collapsed state: FAB button ───────────

  if (!state.isOpen) {
    return (
      <button
        type="button"
        onClick={toggle}
        aria-label="Open Smart Line"
        className={cn(
          'fixed bottom-20 right-4 z-50 lg:bottom-6',
          'flex h-14 w-14 items-center justify-center',
          'rounded-full bg-accent-600 text-white shadow-lg',
          'transition-transform hover:scale-105 active:scale-95',
          'focus-visible:ring-2 focus-visible:ring-accent-600 focus-visible:ring-offset-2 focus-visible:outline-none',
        )}
      >
        <MessageCircle className="h-6 w-6" />
      </button>
    )
  }

  // ─────────────── Expanded state: full panel ────────────

  return (
    <div
      role="dialog"
      aria-label="Smart Line"
      className={cn(
        'fixed inset-x-0 bottom-0 z-50 lg:inset-x-auto lg:right-4 lg:bottom-6 lg:w-[420px]',
        'flex max-h-[80vh] flex-col',
        'rounded-t-2xl lg:rounded-2xl',
        'border border-neutral-200 bg-white shadow-2xl',
        'dark:border-neutral-700 dark:bg-main-800',
        'animate-in slide-in-from-bottom duration-200',
      )}
    >
      {/* ─── Header ─── */}
      <div className="flex items-center justify-between border-b border-neutral-200 px-4 py-3 dark:border-neutral-700">
        <h2 className="text-sm font-semibold text-main-600 dark:text-main-100">Smart Line</h2>
        <button
          type="button"
          onClick={close}
          aria-label="Close Smart Line"
          className="rounded-lg p-1.5 text-neutral-400 transition-colors hover:bg-neutral-100 hover:text-main-600 dark:hover:bg-main-700"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* ─── Space Chips ─── */}
      <div
        role="tablist"
        aria-label="Smart Line spaces"
        className="flex gap-1.5 overflow-x-auto border-b border-neutral-200 px-4 py-2 dark:border-neutral-700"
      >
        {SMART_LINE_SPACES.map((space) => {
          const isActive = state.activeSpace === space.id
          const Icon = space.icon
          return (
            <button
              key={space.id}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => switchSpace(space.id as SmartLineSpace)}
              className={cn(
                'flex shrink-0 items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium transition-colors',
                isActive
                  ? 'bg-accent-600 text-white'
                  : 'bg-neutral-100 text-neutral-500 hover:bg-neutral-200 dark:bg-main-700 dark:text-neutral-300 dark:hover:bg-main-600',
              )}
            >
              <Icon className="h-3.5 w-3.5" />
              {space.label}
            </button>
          )
        })}
      </div>

      {/* ─── Widget area ─── */}
      <div className="flex-1 overflow-y-auto px-4 py-3" style={{ minHeight: 120, maxHeight: '50vh' }}>
        {isRendering && (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-accent-600" />
            <span className="ml-2 text-sm text-neutral-500">Генерация...</span>
          </div>
        )}

        {!isRendering && state.widgets.length === 0 && (
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <MessageCircle className="mb-2 h-8 w-8 text-neutral-300" />
            <p className="text-sm text-neutral-400">
              Задайте вопрос по курсу — ИИ-тьютор поможет разобраться
            </p>
          </div>
        )}

        {!isRendering &&
          state.widgets.map((widget) => (
            <SmartLineWidgetRenderer key={widget.widgetId} widget={widget} />
          ))}

        {/* ─── Loading indicator for answer evaluation ─── */}
        {isInteracting && (
          <div className="mt-3 flex items-center justify-center py-4">
            <Loader2 className="h-5 w-5 animate-spin text-accent-600" />
            <span className="ml-2 text-sm text-neutral-500">Оцениваю ответ...</span>
          </div>
        )}

        {/* ─── Interaction feedback ─── */}
        {interactResult && !isInteracting && (
          <div
            className={cn(
              'mt-3 rounded-xl border p-3',
              interactResult.correct
                ? 'border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-950'
                : 'border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-950',
            )}
          >
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">
                {interactResult.correct ? 'Правильно!' : 'Не совсем...'}
              </span>
              <span className="text-xs text-neutral-500">
                Score: {(interactResult.score * 100).toFixed(0)}%
              </span>
            </div>
            {interactResult.feedback && (
              <p className="mt-1 text-xs text-neutral-600 dark:text-neutral-300">
                {interactResult.feedback}
              </p>
            )}
            <button
              type="button"
              onClick={handleNewQuestion}
              className="mt-2 text-xs font-medium text-accent-600 hover:text-accent-700"
            >
              Задать новый вопрос →
            </button>
          </div>
        )}
      </div>

      {/* ─── Input area (dual-mode) ─── */}
      <form
        onSubmit={handleSubmit}
        className="flex items-center gap-2 border-t border-neutral-200 px-4 py-3 dark:border-neutral-700"
      >
        {/* Mode indicator */}
        {isAnswerMode && (
          <div className="flex items-center" title="Режим ответа">
            <CornerDownLeft className="h-4 w-4 text-accent-600" />
          </div>
        )}

        <input
          ref={inputRef}
          type="text"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder={isAnswerMode ? 'Введите ваш ответ...' : 'Спросите что-нибудь...'}
          aria-label={isAnswerMode ? 'Answer input' : 'Smart Line input'}
          disabled={isBusy}
          className={cn(
            'flex-1 rounded-xl border px-4 py-2.5 text-sm',
            'placeholder:text-neutral-400',
            'focus:ring-1 focus:outline-none',
            'disabled:opacity-50',
            isAnswerMode
              ? 'border-accent-300 bg-accent-50 focus:border-accent-600 focus:ring-accent-600 dark:border-accent-700 dark:bg-accent-950'
              : 'border-neutral-200 bg-neutral-50 focus:border-accent-600 focus:ring-accent-600 dark:border-neutral-600 dark:bg-main-700',
            'dark:text-white',
          )}
        />
        <button
          type="submit"
          disabled={!inputValue.trim() || isBusy}
          aria-label={isAnswerMode ? 'Submit answer' : 'Send prompt'}
          className={cn(
            'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
            'text-white transition-colors',
            'disabled:opacity-40 disabled:cursor-not-allowed',
            'focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none',
            isAnswerMode
              ? 'bg-green-600 hover:bg-green-700 active:bg-green-800 focus-visible:ring-green-600'
              : 'bg-accent-600 hover:bg-accent-700 active:bg-accent-800 focus-visible:ring-accent-600',
          )}
        >
          {isBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
        </button>
      </form>
    </div>
  )
}

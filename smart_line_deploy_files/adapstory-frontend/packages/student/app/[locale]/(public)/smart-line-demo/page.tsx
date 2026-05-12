'use client'

import { QueryProvider } from '@/src/components/shell/QueryProvider'
import { SmartLineProvider } from '@/src/features/smart-line/components/SmartLineProvider'

/**
 * Smart Line standalone demo page.
 * No auth required — works fully on mock data.
 * Access: /portal/ru/smart-line-demo
 */
export default function SmartLineDemoPage() {
  return (
    <QueryProvider>
      <div className="flex min-h-screen flex-col bg-neutral-50 dark:bg-main-900">
        {/* Header */}
        <header className="border-b border-neutral-200 bg-white px-6 py-4 dark:border-neutral-700 dark:bg-main-800">
          <h1 className="text-lg font-semibold text-main-600 dark:text-main-100">
            Smart Line Demo
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            ИИ-тьютор с сократическим методом обучения
          </p>
        </header>

        {/* Main content area */}
        <main className="flex flex-1 flex-col items-center justify-center px-4 py-8">
          <div className="max-w-md text-center">
            <div className="mb-4 text-5xl">🎓</div>
            <h2 className="text-xl font-bold text-main-600 dark:text-main-100">
              Нажмите на зелёную кнопку справа внизу
            </h2>
            <p className="mt-3 text-sm leading-relaxed text-neutral-500">
              Попробуйте спросить:
            </p>
            <ul className="mt-4 space-y-2 text-left text-sm text-neutral-600 dark:text-neutral-300">
              <li className="rounded-lg bg-white px-4 py-2 shadow-sm dark:bg-main-800">
                💡 «Что такое юнит-экономика?»
              </li>
              <li className="rounded-lg bg-white px-4 py-2 shadow-sm dark:bg-main-800">
                💡 «Расскажи про декораторы Python»
              </li>
              <li className="rounded-lg bg-white px-4 py-2 shadow-sm dark:bg-main-800">
                💡 «Когда дедлайн по домашке?»
              </li>
              <li className="rounded-lg bg-white px-4 py-2 shadow-sm dark:bg-main-800">
                💡 «Покажи мой прогресс»
              </li>
            </ul>
          </div>
        </main>

        {/* Smart Line floating panel */}
        <SmartLineProvider learnerId="demo-user" />
      </div>
    </QueryProvider>
  )
}

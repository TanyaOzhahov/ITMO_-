import { BookOpen, BarChart3, ShoppingBag, User, Compass } from 'lucide-react'
import type { SmartLineSpace } from './smart-line.types'
import type { LucideIcon } from 'lucide-react'

export interface SpaceChipConfig {
  id: SmartLineSpace
  label: string
  icon: LucideIcon
}

export const SMART_LINE_SPACES: readonly SpaceChipConfig[] = [
  { id: 'LEARNING', label: 'Обучение', icon: BookOpen },
  { id: 'ANALYTICS', label: 'Аналитика', icon: BarChart3 },
  { id: 'MARKETPLACE', label: 'Каталог', icon: ShoppingBag },
  { id: 'PROFILE', label: 'Профиль', icon: User },
  { id: 'ONBOARDING', label: 'Старт', icon: Compass },
] as const

/** BFF base path for Smart Line endpoints. */
export const SMART_LINE_BFF_PATH = '/web-api/adapstory/student/v1/smart-line'

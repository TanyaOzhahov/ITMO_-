# adapstory-personalization-runtime

## Обзор

**Бизнес-цель:** Движок гиперперсонализации — Core Domain #1 (CD-1). Рендеринг динамического UI через SDUI (Server-Driven UI) на базе DivKit (Yandex). UI обновляется без редеплоя за минуты. Golden Paths — готовые конфигурации для сегментов школ.

**Контекст:** BC-01 (CD-1 Hyper-Personalization Engine). Главный дифференцирующий домен платформы. UI как данные — кросс-платформенность без отдельных релизов.

## Домен

- **Bounded Context:** BC-01 Personalization Runtime
- **Категория:** Core
- **Domain Type:** Core Domain (CD-1 Hyper-Personalization Engine)
- **Wave:** 1
- **Технологии:** Java 25, Spring Boot, DivKit

## Ключевые функции

- SDUI rendering (DivKit integration)
- Dynamic UI — обновление интерфейса без редеплоя
- Golden Paths — шаблоны под целевые сегменты школ
- Variant selection — A/B тестирование UI-вариантов
- Plugin Constructor (L0-L3) runtime

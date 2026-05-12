# 🚀 Smart Line Widget Lake - Быстрый старт (Quick Start)

## 📋 Предварительные требования

Перед тем как начать, убедитесь, что у вас установлены:

- **Docker** v24.0+ ([установить](https://docs.docker.com/engine/install/))
- **Docker Compose** v2.20+ (обычно идёт с Docker Desktop)
- **Git** для клонирования репозитория
- **RAM**: минимум 4 ГБ свободной памяти
- **Свободные порты**: 5432, 6379, 2181, 9092, 8080, 8090

Проверить версии:
```bash
docker --version   # Docker version 24.0+ expected
docker-compose --version  # Docker Compose version 2.20+ expected
```

---

## 🏃 Быстрый старт (30 сек)

### Шаг 1: Перейти в директорию проекта
```bash
cd /path/to/adapstory-ai-lms
```

### Шаг 2: Запустить один скрипт
```bash
bash scripts/run-smart-line-docker.sh
```

**Что делает скрипт:**
1. ✅ Проверяет наличие Docker и Docker Compose
2. ✅ Проверяет доступность портов
3. 🔨 Собирает Docker образ (Maven compilation)
4. 🚀 Запускает все сервисы (PostgreSQL, Redis, Kafka, Smart Line Service)
5. ⏳ Ждёт, пока сервисы станут готовы
6. 📊 Показывает endpoints и helpful commands
7. 🧪 Тестирует /actuator/health endpoint

### Шаг 3: Проверить, что всё работает

Скрипт автоматически протестирует здоровье сервиса. Если видите:
```
[✓] Service is responding (HTTP 200)
```

Значит всё готово! 🎉

---

## 📡 API Endpoints

После запуска `run-smart-line-docker.sh` доступны следующие endpoints:

### Render Widget
```bash
POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/{tenantId}/render

curl -X POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/00000000-0000-0000-0000-000000000001/render \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "12345678-1234-1234-1234-123456789012",
    "learner_id": "87654321-4321-4321-4321-210987654321",
    "space": "LEARNING",
    "topic": "fractions-division",
    "student_state": {
      "attempts": 0,
      "mastery_score": 0.0,
      "consecutive_wrong": 0
    }
  }'
```

### Submit Answer
```bash
POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/{tenantId}/submit-answer

curl -X POST http://localhost:8080/api/bc-16/personalization-runtime/v1/smart-line/00000000-0000-0000-0000-000000000001/submit-answer \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "12345678-1234-1234-1234-123456789012",
    "learner_id": "87654321-4321-4321-4321-210987654321",
    "widget_id": "widget-001",
    "answer": "3.5"
  }'
```

### Health Check
```bash
GET http://localhost:8080/actuator/health

curl http://localhost:8080/actuator/health | jq .
```

---

## 🔧 Полезные команды

### Просмотр логов
```bash
# Логи Smart Line Service
docker-compose -f docker-compose.smart-line.yml logs -f personalization-runtime

# Логи Redis
docker-compose -f docker-compose.smart-line.yml logs -f redis

# Логи PostgreSQL
docker-compose -f docker-compose.smart-line.yml logs -f postgres

# Логи Kafka
docker-compose -f docker-compose.smart-line.yml logs -f kafka
```

### Запуск тестов
```bash
# Все Smart Line тесты
docker-compose -f docker-compose.smart-line.yml exec personalization-runtime mvn test -Dtest=SmartLine*

# Конкретный тест
docker-compose -f docker-compose.smart-line.yml exec personalization-runtime mvn test -Dtest=SmartLineServiceTest
```

### Подключение к базам данных

#### PostgreSQL
```bash
docker exec -it adapstory-postgres-smart-line psql -U adapstory -d adapstory

# Внутри psql:
\dt bc19_*        -- Показать таблицы Smart Line
SELECT COUNT(*) FROM bc19_widget_instance;
SELECT * FROM bc19_student_state LIMIT 5;
```

#### Redis
```bash
docker exec -it adapstory-redis-smart-line redis-cli

# Внутри redis-cli:
KEYS widget-lake:session:*     -- Найти все сессии
GET widget-lake:session:12345  -- Получить состояние студента
TTL widget-lake:session:12345  -- Проверить время жизни (TTL)
```

#### Kafka Topics
```bash
# Список топиков
docker exec -it adapstory-kafka-smart-line kafka-topics \
  --bootstrap-server localhost:9092 \
  --list

# Подписаться на топик в реальном времени
docker exec -it adapstory-kafka-smart-line kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic smart-line.widget.rendered \
  --from-beginning
```

### Мониторинг через UI

**Kafka UI** - веб интерфейс для управления Kafka:
- Откройте в браузере: http://localhost:8090
- Просмотрите топики, сообщения, consumer groups
- Нет авторизации, локальный доступ только

---

## 🛑 Остановка сервисов

### Остановить (контейнеры остаются)
```bash
docker-compose -f docker-compose.smart-line.yml stop
```

### Запустить снова
```bash
docker-compose -f docker-compose.smart-line.yml start
```

### Полностью удалить (с удалением контейнеров и сетей)
```bash
docker-compose -f docker-compose.smart-line.yml down
```

### Удалить вместе с данными (ВНИМАНИЕ: потеря данных!)
```bash
docker-compose -f docker-compose.smart-line.yml down -v
```

---

## 🐛 Troubleshooting

### Проблема: "Port already in use"

**Решение 1:** Остановить другие контейнеры
```bash
docker-compose -f docker-compose.smart-line.yml down
docker ps  # Проверить, что наших контейнеров нет
```

**Решение 2:** Найти что занимает порт и завершить
```bash
# Linux/Mac
lsof -i :8080
kill -9 <PID>

# Windows PowerShell
Get-NetTCPConnection -LocalPort 8080 | Stop-Process
```

### Проблема: "Connection refused" при тестировании endpoint

**Причина:** Сервис ещё не готов

**Решение:** Подождать 30-60 сек и попробовать снова
```bash
# Проверить статус контейнеров
docker-compose -f docker-compose.smart-line.yml ps

# Просмотреть логи стартапа
docker-compose -f docker-compose.smart-line.yml logs personalization-runtime | tail -50
```

### Проблема: "Docker build failed"

**Причина:** Maven не может скачать зависимости

**Решение:** Проверить интернет и перестроить
```bash
# Очистить Maven кэш
docker volume prune
rm -rf ~/.m2/repository

# Перестроить
bash scripts/build-smart-line.sh
```

### Проблема: Контейнеры запустились, но не переходят в "healthy"

**Решение:** Проверить логи
```bash
# Посмотреть последние 100 строк логов
docker-compose -f docker-compose.smart-line.yml logs --tail=100

# Если PostgreSQL не стартует:
docker-compose -f docker-compose.smart-line.yml logs postgres | grep ERROR

# Если Redis не доступен:
docker-compose -f docker-compose.smart-line.yml exec redis redis-cli ping
```

---

## 📊 Система мониторинга

Скрипт автоматически устанавливает health checks для всех сервисов.

Проверить статус:
```bash
docker-compose -f docker-compose.smart-line.yml ps

# Expected output:
# NAME                         STATUS
# adapstory-postgres-smart-line    healthy
# adapstory-redis-smart-line       healthy
# adapstory-zookeeper-smart-line   Un (проверка нужна)
# adapstory-kafka-smart-line       healthy
# adapstory-personalization-runtime  healthy
```

Проверить health отдельного контейнера:
```bash
docker inspect adapstory-personalization-runtime --format='{{.State.Health.Status}}'
# Output: healthy
```

---

## 📝 Какие сервисы запускаются?

| Сервис | Порт | Описание | Статус |
|--------|------|---------|--------|
| **PostgreSQL** | 5432 | Реляционная БД (tablesspace: `main`) | `healthy` |
| **Redis** | 6379 | Cache для StudentState (TTL 1800s) | `healthy` |
| **Zookeeper** | 2181 | Coordination для Kafka | `running` |
| **Kafka** | 9092 | Event streaming (CloudEvents 1.0) | `healthy` |
| **Smart Line Service** | 8080 | Java Spring Boot приложение | `healthy` |
| **Kafka UI** | 8090 | GUI для управления Kafka | `running` |

---

## 🎯 Следующие шаги

### Для тестирования
1. Откройте http://localhost:8090 (Kafka UI)
2. Запустите render endpoint (см. API Endpoints выше)
3. Проверьте логи: `docker-compose logs -f personalization-runtime`

### Для разработки
1. Изменяйте код в `adapstory-personalization-runtime/`
2. Перестройте образ: `bash scripts/build-smart-line.sh`
3. Перезапустите контейнер: `docker-compose -f docker-compose.smart-line.yml restart personalization-runtime`

### Для интеграции
1. Используйте endpoints: `/smart-line/{tenantId}/render` и `/smart-line/{tenantId}/submit-answer`
2. Изучите OpenAPI spec: `/specs/smart-line-bff-openapi.yaml`
3. Интегрируйте с BFF (adapstory-bff-student)

### Для production deployment
1. Используйте Kubernetes вместо Docker Compose
2. Создайте Helm charts для управления
3. Используйте managed PostgreSQL/Redis вместо контейнеризированных
4. Настройте TLS/SSL
5. Настройте monitoring (Prometheus + Grafana)

---

## 📚 Документация

- **Архитектура:** `/specs/smart-line-widget-lake-architecture.md`
- **OpenAPI:** `/specs/smart-line-bff-openapi.yaml`
- **Database Schema:** `/specs/smart-line-database-schema.sql`
- **E2E Tests:** `/specs/smart-line-e2e-tests.feature`
- **Java Implementation:** `/specs/SMART-LINE-JAVA-IMPLEMENTATION.md`
- **Docker Deployment:** `/DOCKER-DEPLOYMENT-GUIDE.md`

---

## ❓ Частые вопросы (FAQ)

**Q: Где хранятся данные?**
A: В Docker volumes:
- PostgreSQL: `adapstory-postgres-data-smart-line`
- Redis: `adapstory-redis-data-smart-line`

**Q: Как сбросить БД?**
A: `docker-compose -f docker-compose.smart-line.yml down -v` (удалит все volumes)

**Q: Как запустить в production?**
A: Используйте Kubernetes + Helm вместо Docker Compose (см. DOCKER-DEPLOYMENT-GUIDE.md)

**Q: Какие переменные окружения нужны?**
A: Скрипт автоматически устанавливает все необходимые (см. application-smart-line.yml)

**Q: Как интегрировать с моим приложением?**
A: Используйте HTTP endpoints (port 8080) или Kafka topics для event-driven архитектуры

**Q: Какой размер образа Docker?**
A: ~100 MB (multi-stage build исключает Maven)

---

## 🚨 Получение помощи

Если что-то не работает:

1. **Проверить логи:**
   ```bash
   docker-compose -f docker-compose.smart-line.yml logs --tail=100
   ```

2. **Проверить статус контейнеров:**
   ```bash
   docker-compose -f docker-compose.smart-line.yml ps
   ```

3. **Перезапустить сервисы:**
   ```bash
   docker-compose -f docker-compose.smart-line.yml restart
   ```

4. **Прочитать DOCKER-DEPLOYMENT-GUIDE.md** для подробного troubleshooting

5. **Создать issue** с логами и информацией о вашей системе

---

## ✅ Checklist: Система готова, когда

- [x] `run-smart-line-docker.sh` выполняется без ошибок
- [x] Все 6 контейнеров имеют статус `healthy` или `running`
- [x] GET http://localhost:8080/actuator/health возвращает 200
- [x] POST запрос на /smart-line/{tenantId}/render работает
- [x] Kafka UI доступна на http://localhost:8090
- [x] PostgreSQL и Redis доступны (см. команды подключения)
- [x] Логи не содержат ERROR сообщений

---

## 🎉 Готово!

Вы только что запустили **Smart Line Widget Lake** — GenAI-native widget generation систему на основе ChatGPT, работающую локально с полным окружением (PostgreSQL, Redis, Kafka, Docker).

**Дальше:**
- Интегрируйте с вашим BFF
- Создавайте frontend компоненты используя DivKit
- Запустите E2E тесты
- Развертывайте в Kubernetes

Удачи! 🚀

---

**Questions?** Посмотрите документацию в `/specs/` или `/DOCKER-DEPLOYMENT-GUIDE.md`

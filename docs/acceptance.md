# Карта приёмки

Соответствие техническому заданию, результаты проверок и особенности реализации.

## Проверки

На 2026-11-09: 300 тестов, 0 ошибок и пропусков; сборка, `spotlessCheck` и `systemTest` прошли. Отдельно через Gateway проверены Swagger UI, выбор схем Booking/Hotel, HTTP-коды и схемы успешных ответов OpenAPI.

Повторить проверку можно командами из [README](../README.md#тестирование). Подробности сквозных сценариев и расположение логов — в [verification](../verification/README.md).

## Критерии ТЗ

| № | Требование | Реализация и проверка |
|---|---|---|
| 1 | Планирование занятости | Сортировка timesBooked/id, защита от пересечений; InventoryProtocolTest, BookingAutoSelectionTest, конкурентный systemTest |
| 2 | Согласованность сервисов | Сага, компенсация, Retry/Timeout/Circuit Breaker; BookingFlowTest, BookingRecoveryTest, HotelHttpClientTest, systemTest |
| 3 | Бронирования | Создание, история, чтение, отмена, права и идемпотентность; BookingFlowTest, BookingHistoryTest, BookingCancellationTest, BookingIdempotencyTest |
| 4 | Каталог и статистика | CRUD, фильтры, календарная загрузка; HotelCatalogTest, OccupancyReportTest |
| 5 | Gateway и Eureka | Динамическая маршрутизация, JWT, traceId; GatewaySecurityTest, systemTest |
| 6 | Безопасность | JWT в сервисах, роли и method security; BookingSecurityTest, HotelSecurityTest, JwtPolicyTest |
| 7 | Интеграционные сценарии | MVC/WebTestClient, H2, конкурентные запросы и сетевые отказы; тесты сервисов и systemTest |
| 8 | База данных | Миграции, связи, индексы и ограничения: [Booking](../booking-service/src/main/resources/db/migration), [Hotel](../hotel-service/src/main/resources/db/migration) |
| 9 | Предзаполнение | [Демонстрационный набор](../demo-fixtures/README.md), DemoBootstrapTest, команда наполнения |
| 10 | Структура кода | Предметные модули и независимые ядра; [ADR 002](adr/002-service-boundaries.md), HexagonalArchitectureTest |
| 11 | Стиль | Spotless/ktlint, команда spotlessCheck |
| 12 | Ошибки | Единый формат и HTTP-коды; BookingSecurityHttpTest и тесты API |
| 13 | Логирование | JSON-логи, bookingId и traceId; проверка корреляции в systemTest |
| 14 | Документация | README проекта и модулей, OpenAPI, схема системы и ADR |
| 15 | Поддерживаемость тестов | Управляемое время, изолированные H2 и временный Docker-контур |

## Уточнения и отличия от исходного ТЗ

Ниже перечислены особенности реализации и отличия от примеров исходного технического задания.

- Проект написан на Kotlin/JDK 21. Вместо рекомендованных Lombok/MapStruct используются Kotlin data classes и явное преобразование DTO.
- Для автоподбора обязателен hotelId; roomId при autoSelect=true игнорируется. Исходное ТЗ не задаёт обязательность hotelId.
- Список `/api/rooms` также отсортирован по timesBooked/id, хотя ТЗ описывает его как список без специальной сортировки.
- Четыре самостоятельных приложения; H2 только у Booking и Hotel, поскольку Eureka/Gateway не хранят предметные таблицы. Публичные зависимости устанавливает Gradle автоматически.
- Hotel хранит минимальные резервирования и календарь; Booking — пользователей, заказы и прогресс саги. Примерные маршруты из ТЗ приведены к `/api/users`, `/api/bookings`, `/api/hotels`, `/api/rooms`; служебные hold/commit/release находятся под `/internal` и скрыты Gateway.
- UTC и интервалы [заезд,выезд); заезд сегодня допустим, новая пользовательская отмена с даты заезда запрещена. Техническая компенсация этой границей не ограничена.
- ADMIN имеет также публичные USER-права, может видеть/отменять чужие брони; USER ограничен своими. Уже выданный JWT живёт до exp после изменения/удаления пользователя.
- timesBooked — неотменённые RESERVED за всю историю; завершение проживания не уменьшает счётчик. Аналитика считает только ночи внутри отчётного окна.
- Потеря любой H2 требует общего reset. Независимое восстановление утраченной базы, production-масштабирование нескольких H2-экземпляров и строгая равномерность при параллельных непересекающихся датах не заявляются.

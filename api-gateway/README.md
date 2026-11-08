# API Gateway

Публичная точка входа на порту 8080. Spring Cloud Gateway находит сервисы через Eureka, проверяет JWT и передаёт токен и X-Trace-Id в backend.

| Маршрут | Сервис |
|---|---|
| `/api/users/**`, `/api/bookings/**` | Booking |
| `/api/hotels/**`, `/api/rooms/**` | Hotel |
| `/health/booking`, `/health/hotel` | Проверка доступности backend через Gateway |

`/internal/**` возвращает 404. Swagger UI — `/swagger-ui.html`, схемы — `/v3/api-docs/booking` и `/v3/api-docs/hotel`.

[Запуск системы](../README.md#запуск). Настройки для отдельного запуска: SERVER_PORT, EUREKA_URL, APP_SECURITY_JWK_SET_URI (адрес JWKS Booking).

```sh
./gradlew :api-gateway:test :api-gateway:bootJar
```

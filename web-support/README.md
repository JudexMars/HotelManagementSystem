# Web support

Общие HTTP-контракты Booking, Hotel и Gateway: ошибки `{code,message,traceId}`, идентификаторы запросов и проверка обязательных полей JWT. Криптографическую подпись каждый сервис проверяет самостоятельно. Библиотека отдельно не запускается.

```sh
./gradlew :web-support:test
```

[Решение по безопасности](../docs/adr/001-jwt-boundaries.md).

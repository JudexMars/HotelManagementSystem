# booking-core

Бизнес-правила пользователей и бронирований: создание, отмена, идемпотентность, автоподбор и согласование с Hotel. Kotlin/JDK-модуль без Spring и базы данных; входит в [Booking Service](../booking-service/README.md), отдельно не запускается.

```sh
./gradlew :booking-core:build :booking-service:test
```

Тесты поведения и архитектуры находятся в booking-service. [Архитектурное решение](../docs/adr/002-service-boundaries.md).

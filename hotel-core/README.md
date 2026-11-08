# hotel-core

Бизнес-правила каталога, резервирования номеров и расчёта загрузки. Kotlin/JDK-модуль без Spring и базы данных; входит в [Hotel Service](../hotel-service/README.md), отдельно не запускается.

```sh
./gradlew :hotel-core:build :hotel-service:test
```

Тесты поведения и архитектуры находятся в hotel-service. [Архитектурное решение](../docs/adr/002-service-boundaries.md).

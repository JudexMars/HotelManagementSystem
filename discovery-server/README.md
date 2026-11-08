# Eureka Server

Реестр для обнаружения Booking, Hotel и Gateway. Порт по умолчанию — 8761, интерфейс — `http://localhost:8761`. Бизнес-данные не хранит.

[Запуск системы](../README.md#запуск). Клиенты используют EUREKA_URL с окончанием `/eureka/`; Compose задаёт адрес автоматически. Для самого Eureka доступны SERVER_PORT и DISCOVERY_HOST.

```sh
./gradlew :discovery-server:bootJar
```

Маршрутизация готова, когда оба запроса `/health/booking` и `/health/hotel` через Gateway возвращают UP. Одной доступности Eureka недостаточно.

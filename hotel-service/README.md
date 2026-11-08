# Hotel Service

Каталог отелей и номеров, календарь резервирований и отчёты о загрузке. Общий запуск — в [README проекта](../README.md). Порт сервиса — 8082; в Docker Compose API доступен через Gateway на порту 8080.

## Каталог

| Метод | Доступ | Запрос и результат |
|---|---|---|
| POST `/api/hotels` | ADMIN | `{name,address}` → 201 `{id,name,address}` и Location |
| GET `/api/hotels/{id}` | USER/ADMIN | Отель по UUID |
| GET `/api/hotels` | USER/ADMIN | Страница отелей; параметры page, size, name, address |
| PUT `/api/hotels/{id}` | ADMIN | `{name,address}` → обновлённый отель |
| DELETE `/api/hotels/{id}` | ADMIN | Удалить отель вместе с номерами, 204 |
| POST `/api/rooms` | ADMIN | `{hotelId,number,available?}` → 201 и Location; available по умолчанию true |
| GET `/api/rooms/{id}` | USER/ADMIN | `{id,hotelId,number,available,timesBooked}` |
| PUT `/api/rooms/{id}` | ADMIN | `{number,available}` → обновлённый номер |
| DELETE `/api/rooms/{id}` | ADMIN | Удалить номер, 204 |

Страница отелей содержит `{items,page,size,totalElements,totalPages}`, порядок — name/id. Page начинается с нуля, size — 1–100 (по умолчанию 20); page × size не должен превышать 2 147 483 647. Фильтры name/address ищут подстроку без учёта регистра; `%` и `_` воспринимаются буквально.

Name, address и number не могут быть пустыми или состоять из пробелов; максимальная длина — 200, 500 и 50 символов. Неизвестные поля JSON дают 400. Номер уникален внутри отеля; конфликт — 409. HotelId существующего номера и timesBooked через API не меняются. Неизвестный объект — 404.

Удаление запрещено при любом действующем удержании либо подтверждённом резервировании с выездом позже сегодняшней UTC-даты: 409 CATALOG_IN_USE. Отель удаляется целиком либо не меняется. История сохраняется для аналитики, но удалённые объекты скрываются из каталога. Повтор удаления — 404; обозначение удалённого номера остаётся занятым.

## Свободные номера

`GET /api/rooms` и `GET /api/rooms/recommend` требуют USER/ADMIN и параметры `startDate`, `endDate`; `hotelId` необязателен. Даты ISO, выезд позже заезда, поиск в прошлом допустим. Неизвестный hotelId — 404.

Ответ — массив `{id,hotelId,number,available,timesBooked}`, отсортированный по timesBooked, затем id. Включаются только действующие свободные номера с available=true. Available отражает возможность эксплуатации, а не занятость по датам. Интервал проживания — [заезд,выезд): новый заезд в день выезда допустим. Поиск не удерживает номер.

TimesBooked — количество неотменённых подтверждённых резервирований за всю историю. Отмена уменьшает счётчик, завершение проживания — нет.

## Аналитика загрузки

`GET /api/hotels/{id}/occupancy` доступен ADMIN. Обязательны `from` и `to` — непустой период [from,to). Необязательны `sort=occupancy|bookings` (по умолчанию occupancy), `direction=asc|desc` (по умолчанию desc). Равенство разрешается по UUID номера по возрастанию. Неверные параметры — 400, неизвестный отель — 404.

Ответ: hotelId, hotelName, hotelDeleted, from, to, totalNights, rooms. Для номера: roomId, roomNumber, deleted, bookingCount, bookedNights, occupancyPercent.

Учитываются подтверждённые неотменённые резервирования; ночи обрезаются границами отчёта. Процент — bookedNights / totalNights × 100, округлённый до двух знаков. Например, проживания 8–12 и 18–23 ноября в окне 10–20 ноября дают 2 брони, 4 ночи и 40% загрузки.

Отчёт включает номера без броней, недоступные и удалённые номера; доступен и для удалённого отеля. Периоды ремонта из знаменателя не вычитаются.

## Межсервисный API

Эти методы предназначены для Booking и недоступны через Gateway. Нужен сервисный JWT: audience `hotel-inventory`, scope `inventory:read` для чтения или `inventory:write` для команд. Пользовательский ADMIN не имеет служебных полномочий.

| Метод | Назначение |
|---|---|
| GET `/internal/rooms` | Поиск свободных номеров с теми же параметрами дат и hotelId |
| GET `/internal/rooms/{id}` | Действующий номер независимо от занятости |
| POST `/internal/rooms/{id}/hold` | Временно удержать даты, HELD |
| POST `/internal/rooms/{id}/commit` | Подтвердить удержание, RESERVED |
| POST `/internal/rooms/{id}/release` | Освободить даты, RELEASED |

Команды принимают `{attemptId,bookingId,startDate,endDate}` и возвращают 200 с этими полями, roomId, status, expiresAt. Повтор той же команды безопасен; изменение привязки attemptId — 409 ATTEMPT_CONFLICT. Занятость — 409 ROOM_UNAVAILABLE, истёкшее удержание — 409 HOLD_EXPIRED, освобождённая попытка — 409 ATTEMPT_RELEASED. Неизвестный номер — 404 ROOM_NOT_FOUND; commit неизвестной попытки — 404 ATTEMPT_NOT_FOUND; неверный период — 400 INVALID_PERIOD.

Удержание действует 30 секунд (INVENTORY_HOLD_TTL). Подтверждённое резервирование не истекает автоматически. Release неизвестной попытки существующего номера предотвращает её последующее удержание. Изменение available=false не отменяет ранее выданное удержание. [Согласование резервирования](../docs/adr/003-booking-saga.md).

## Хранение и проверка

H2 in-memory содержит hotels, rooms и reservations. [Миграции](src/main/resources/db/migration) описывают поля, связи, индексы и ограничения. Потеря данных требует общего сброса системы.

```sh
./gradlew :hotel-service:test :hotel-service:bootJar spotlessCheck
```

OpenAPI через Gateway: `/v3/api-docs/hotel`. Сквозные проверки — в [verification](../verification/README.md).

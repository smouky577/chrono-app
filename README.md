<p align="center">
  <img src="app/src/main/res/drawable/logo.png" width="150" height="150" alt="Chrono app">
</p>

# Chrono app

Android приложение за Wi‑Fi хронографи **HT-X3006 / HT-50**.

Направено върху [ChronoMate](https://github.com/Rec0iL/ChronoMate), опростено за ползване на полето: жива скорост, енергия по теглото от хронографа, история на изстрелите и PDF експорт.

## Възможности

- Свързва се към Wi‑Fi хотспота на хронографа и чете изстрелите на живо
- Взима теглото на снаряда от уреда (не се въвежда ръчно в приложението)
- Табло с последен изстрел, енергия, статистики и графика на скоростта
- История и PDF отчет на сесията
- По желание калибрация спрямо втори хронограф
- Тъмна / светла тема и опция екранът да не гасне

## Инсталация

Свали APK-то от **Releases** и го инсталирай на телефона. Ако Android попита, разреши инсталация от този файл.

## Свързване с хронографа

1. Включи Wi‑Fi на HT-X3006 (задръж долния бутон, докато се появи Wi‑Fi).
2. На телефона се свържи към хотспот **HT-X3000**, парола `88888888`.
3. Отвори **Chrono app** и натисни значката за връзка, ако още не е на живо.

## Компилация

Проектът е за `compileSdk 34` и Gradle 8.7. Отвори папката в Android Studio или изпълни `gradle assembleDebug`.

---

# Chrono app (English)

Android companion app for **HT-X3006 / HT-50** Wi‑Fi chronographs.

Based on [ChronoMate](https://github.com/Rec0iL/ChronoMate), simplified for field use: live velocity, energy from the chronograph’s projectile weight, shot history, and PDF export.

## Features

- Connects to the chronograph Wi‑Fi hotspot and reads live shot data
- Uses projectile weight from the device (no manual weight entry in the app)
- Dashboard with latest shot, energy, stats, and velocity chart
- History and PDF session export
- Optional calibration against a second chronograph
- Dark / light theme and keep-screen-on

## Install

Download the APK from **Releases** and install it on the phone. Allow installation from this file if Android asks.

## Connect the chronograph

1. Turn on Wi‑Fi on the HT-X3006 (hold the down button until Wi‑Fi is on).
2. On the phone, join hotspot **HT-X3000**, password `88888888`.
3. Open **Chrono app** and tap the connection badge if it is not already live.

## Build

The project uses `compileSdk 34` and Gradle 8.7. Open the folder in Android Studio or run `gradle assembleDebug`.

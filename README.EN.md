# Whispers for Android

⚙️ **Android Smartphone App Build Instructions**

---

## 🎯 Overview

This repository contains an Android Studio/Gradle project for building an Android smartphone application. Follow the steps below to build and run on an emulator or physical device.

---

## 📜 Prerequisites

- **Java Development Kit (JDK) 11+**  
- **Android SDK** (API Level 21+)  
- **Android Studio Arctic Fox or later**  
- **Gradle Wrapper** (included in the project root)  
- For physical device testing: USB debugging enabled and `adb` available in your PATH

---

## ⚙️ Setup

1. **Clone the repository**  
   ```bash
   git clone https://github.com/<your-org>/<your-repo>.git
   cd <your-repo>
   ```

2. **Open in Android Studio**

   * Go to **File → Open** and select `settings.gradle` (or `build.gradle`) in the project root.
   * Wait for Gradle sync to complete.

3. *(Optional CLI check)* Ensure required SDKs are installed:

   ```bash
   sdkmanager --list
   ```

---

## 🏗️ Build

### 1. From Android Studio

* Click the **Run ▶️** button in the toolbar
* Or go to **Build → Make Project**

### 2. From the Command Line

* **Debug APK**

  ```bash
  ./gradlew clean assembleDebug
  ```
* **Release APK**

  ```bash
  ./gradlew clean assembleRelease
  ```

#### Build Artifacts

* Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
* Release APK: `app/build/outputs/apk/release/app-release.apk`

---

## 📱 Install & Run

1. Launch your emulator or connect a device via USB.
2. Install the APK using adb:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Find and launch the app from your device’s app drawer.

---

## 🔐 Signing a Release APK

1. Place your keystore file (e.g. `release-key.jks`) under the `app/` directory.
2. Add the following properties to `app/gradle.properties`:

   ```properties
   KEYSTORE_FILE=release-key.jks
   KEYSTORE_PASSWORD=yourKeystorePassword
   KEY_ALIAS=yourKeyAlias
   KEY_PASSWORD=yourKeyPassword
   ```
3. Enable the corresponding `signingConfigs` block in `app/build.gradle`.
4. Build the signed release:

   ```bash
   ./gradlew assembleRelease
   ```
5. The signed APK is output to `app/build/outputs/apk/release/`.

---

## 📄 License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## 🖋️ Author

* **Your Name** (@your-github-username)
* **Email:** [your.email@example.com](mailto:your.email@example.com)

```
```

# 📱 Jetpack Compose Compatibility: Devices & UI Overview

This document provides a structured comparison of Android devices and custom UIs (e.g., MIUI, One UI, HyperOS) with regard to their compatibility with Jetpack Compose, Android versions, and update policies.

---

## 📱 Device Specs & Update Policies

| 📱 Model Name         | 🧩 Initial OS | 🎨 UI      | 🧱 SDK | 🔄 Update Policy                               | 💾 RAM / Storage                   | ⭐ Key Features                                               |
|----------------------|---------------|------------|--------|------------------------------------------------|------------------------------------|--------------------------------------------------------------|
| **Redmi Note 13 Pro** | Android 13    | MIUI 14    | API 33 | 🔁 2 major Android updates                     | 8GB / 128GB, 12GB / 256GB          | 🛠️ MIUI-based UI (expected support for MIUI 15)              |
| **Redmi Note 14 Pro** | Android 14    | HyperOS    | API 34 | 🔁 Estimated 3 major Android updates           | 8GB / 128GB, 12GB / 256GB          | ⚡ Lightweight, secure, and performance-focused              |
| **Galaxy A55 5G**     | Android 14    | One UI 6.1 | API 34 | ✅ 4 Android + 5 years of security patches     | 8GB / 128GB, 8GB / 256GB           | 💧 IP67, 🛡️ Gorilla Glass Victus+, near-flagship build      |
| **Galaxy A34 5G**     | Android 13    | One UI 5.1 | API 33 | ✅ 4 Android + 5 years of security patches     | 6GB / 128GB, 8GB / 256GB + microSD | 🆙 Android 17 ready, 💽 expandable storage                   |
| **Galaxy A35 5G**     | Android 14    | One UI 6.1 | API 34 | ✅ 4 Android + 5 years of security patches     | 6GB / 128GB, 8GB / 256GB + microSD | 🆕 Vision Booster display, modern A-series hardware         |
| **Moto G53 5G**       | Android 13    | My UX      | API 33 | 🔁 1 Android update typical for Moto G series | 4GB–8GB + 64GB/128GB + microSD     | 💲 Affordable 5G, 120Hz, 5000mAh battery, clean UI           |
| **Moto G Pure**       | Android 11    | My UX      | API 30 | 🔁 1 Android update max                        | 3GB + 32GB + microSD               | 💲 Entry-level, 🪫 2-day battery, USB-C, expandable storage  |

---

## 💻 Jetpack Compose Support by UI

| 🎨 UI           | Android Version | API Level | Jetpack Compose Support | Notes                                                              |
|----------------|------------------|------------|---------------------------|---------------------------------------------------------------------|
| **MIUI 14**    | Android 13        | API 33     | ✅ Supported              | Custom permission dialogs and aggressive battery control—exclude in settings recommended |
| **HyperOS**    | Android 14        | API 34     | ✅ Fully Supported        | Lighter than MIUI, secure and Compose-friendly                     |
| **One UI 6.1** | Android 14        | API 34     | ✅ Fully Supported        | Excellent Material 3 support, smooth animations, stable for dev use |
| **One UI 5.1** | Android 13        | API 33     | ✅ Supported              | Stable with Compose 1.3–1.5, less aggressive power saving           |
| **My UX**      | Android 11–13     | API 30–33  | ✅ Limited Support         | G Pure limited to 1.2–1.3, G53 handles up to 1.5; caution on low-end specs |

---

## 🎨 Recommended Compose Versions by UI

| 🎨 UI          | Recommended Compose Versions      | Notes                                                               |
|----------------|-----------------------------------|---------------------------------------------------------------------|
| **One UI 6.1** | ✅ 1.5–1.6.x                       | Smooth Material 3 and animation support                             |
| **HyperOS**    | ✅ 1.5–1.6.x                       | Stable even on lightweight devices                                  |
| **MIUI 14**    | ⚠️ 1.3–1.5.x                      | Higher versions can be unstable—stick to 1.4–1.5                    |
| **One UI 5.1** | ✅ 1.3–1.5.x                       | Best with Compose 1.3.1 to 1.5; use caution with Material 3         |
| **My UX**      | ✅ 1.2–1.5.x (G Pure: ≤1.3)        | G Pure may lag with 1.6; G53 is stable up to 1.5                    |
| **ColorOS**    | ⚠️ 1.3–1.4.x                      | Watch for Material 3 theme conflicts; 1.4.x is safest               |

---

## 🧱 Jetpack Compose Version Chart

| 📦 Compose Version | 🗓️ Release Date | ✅ Android Support | 🧱 Recommended API Level | 🧩 Material 3 Support | 🌟 Key Features & Notes                                    |
|--------------------|------------------|--------------------|--------------------------|------------------------|-------------------------------------------------------------|
| **1.6.x**          | Q1 2024+         | Android 8–14       | API 26–34                | ✅ Full Support         | Stronger Modifier.animate, full Material 3 integration      |
| **1.5.x**          | Q3 2023+         | Android 8–14       | API 26–34                | ✅ Stable Support        | Works with Compose Compiler 1.5, solid Material 3           |
| **1.4.x**          | Q1 2023+         | Android 8–13       | API 26–33                | ⚠️ Beta-like Support     | Partial Material 3 support; may have UI issues on ColorOS   |
| **1.3.x**          | Q4 2022+         | Android 8–13       | API 26–33                | ⚠️ Limited Support       | Compatibility issues with Material 3 and custom UIs         |
| **≤ 1.2.x**        | Pre-2022         | Android 8–12       | API 26–31                | ❌ Not Supported         | No Material 3; older devices only; lacks modern features     |

---

## 📂 Use Cases

- Select Android devices suitable for Jetpack Compose development
- Determine optimal Compose versions based on UI and OS
- Pre-validate target devices for app testing
- Evaluate long-term support and update guarantees

---

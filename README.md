# Ability Flappy Bird

A Flappy Bird game controlled by EMG (electromyography) signals from the **ABILITY HAND** prosthetic, connected over Bluetooth Low Energy. Includes a **Dual Site Training** mode for rehabilitation exercises requiring selective muscle control.

---

## How it works

1. Connect to the ABILITY HAND over BLE
2. The app reads your EMG thresholds (`RD` = positive channel, `RE` = negative channel)
3. Muscle contraction above the threshold → bird jumps and hovers
4. Release → bird falls
5. **Dual Site Training**: the bird turns red (CH1) or blue (CH2) at random intervals — only the matching muscle contraction controls the bird

---

## Requirements

| Tool | Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.0.21 |
| JDK | 11 or newer |
| Android device or emulator | API 27 (Android 8.1) minimum |

> **BLE note:** Bluetooth Low Energy requires a **physical Android device**. The emulator cannot connect to real BLE peripherals.

---

## Clone & open

```bash
git clone <repo-url>
cd ability_flappy_bird
```

Open **Android Studio** → `File` → `Open` → select the `ability_flappy_bird` folder.

Wait for Gradle sync to finish (bottom status bar).

---

## Build & run

### From Android Studio

1. Plug in your Android device via USB and enable **USB Debugging**
   (`Settings → Developer options → USB Debugging`)
2. Select your device in the top toolbar device dropdown
3. Click **Run** (or press `Shift + F10`)

### From the command line

```bash
# Debug build + install on connected device
./gradlew installDebug

# Or just build the APK without installing
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat` instead of `./gradlew`.

---

## Permissions

The app requests the following at runtime:

| API level | Permissions |
|---|---|
| Android 12+ (API 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` |
| Android 8–11 (API 27–30) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` |

Grant all permissions when prompted on first launch. Without them the BLE scan will not start.

---

## App flow

```
BLE Screen
  ├─ Scan for ABILITY HAND
  ├─ Connect → thresholds read automatically (RD / RE)
  ├─ Select active channel(s): Positive (CH1), Negative (CH2)
  ├─ Toggle Dual Site Training (optional)
  └─ Start Game ──► Flappy Bird Game
                        └─ Back button ──► BLE Screen
```

---

## Dual Site Training

When enabled, the bird is **always** red or blue — never yellow:

| Bird colour | Required channel | Signal |
|---|---|---|
| 🔴 Red | CH1 — Positive | Must exceed positive threshold |
| 🔵 Blue | CH2 — Negative | Must exceed negative threshold |

- Wrong channel fires → **nothing happens**
- After a random window of **3–10 points** the colour switches to the other side
- Colour resets randomly on each new game

---

## Project structure

```
app/src/main/java/com/ability_flappy_bird/ability_flappy_bird/
├── MainActivity.kt      # Game loop, drawing, EMG control logic
├── BleScreen.kt         # BLE scan / connect UI, threshold display
├── BleManager.kt        # BLE GATT client, P2 stream, threshold reads
└── ui/theme/            # Material3 colour scheme and typography
```

---

## BLE protocol (ABILITY HAND)

| Command | Direction | Description |
|---|---|---|
| `RD\n` | Phone → Hand | Read positive EMG threshold |
| `RE\n` | Phone → Hand | Read negative EMG threshold |
| `P2\n` | Phone → Hand | Start EMG data stream |
| `DIR` + float × 2 | Hand → Phone | EMG frame: CH1 (bytes 3–6), CH2 (bytes 7–10), little-endian float |

Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
Write characteristic: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
Notify characteristic: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

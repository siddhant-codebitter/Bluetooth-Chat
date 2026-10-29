# Bluetooth Chat

Chat with people nearby **without internet, Wi-Fi or mobile data**. Bluetooth Chat connects phones to each other over Bluetooth, and phones in between can pass messages along, so you can reach people who are out of your own Bluetooth range.

- **No internet or account needed.** Just pick a name and start chatting.
- **Private.** Messages are encrypted end to end. A phone that passes your message along cannot read it.
- **Stored on your phone.** Chats are saved in the app's private storage on your device only.

---

## Table of contents

1. [Features](#features)
2. [What you need](#what-you-need)
3. [Install the app](#install-the-app)
4. [First-time setup](#first-time-setup)
5. [Connect two phones](#connect-two-phones)
6. [Using the app](#using-the-app)
7. [How messages travel](#how-messages-travel)
8. [Settings](#settings)
9. [Troubleshooting](#troubleshooting)
10. [Privacy and security](#privacy-and-security)
11. [Limitations](#limitations)
12. [Permissions explained](#permissions-explained)
13. [For developers](#for-developers)

---

## Features

- One-to-one chats with phones nearby
- Messages hop through other phones (up to 5 hops) to reach people out of direct range
- Delivery status on every message: **Sending, Sent, Delivered, Failed**
- Messages written while nobody is in range are sent automatically when a path appears
- Runs in the background with a notification, and alerts you to new messages
- Unread badges and a chat list sorted by latest activity
- End-to-end encryption, with chats stored only on your phone
- Simple settings: change your name, switch relaying on or off, clear all chats

## What you need

- **Two or more Android phones** with Bluetooth, running **Android 8.0 (API 26) or newer**
- Bluetooth turned on
- The phones within roughly 10 to 30 metres of each other (less through walls)

> **The Android emulator does not support Bluetooth.** Test on real phones.

## Install the app

### Option A: build it with Android Studio

1. Install [Android Studio](https://developer.android.com/studio).
2. Clone this repository (replace the address with this repository's own):
   ```
   git clone https://github.com/<your-username>/BluetoothChat.git
   ```
3. In Android Studio choose **File > Open** and select the `BluetoothChat` folder. Wait for Gradle to finish syncing.
4. On your phone, enable **Developer options** and **USB debugging** (Settings > About phone > tap *Build number* 7 times, then Settings > System > Developer options).
5. Connect the phone with a USB cable, pick it in the device list at the top of Android Studio, and press **Run**.
6. Repeat for each phone.

### Option B: install an APK

1. In Android Studio choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
2. Android Studio shows a link to the folder with `app-debug.apk`. Copy the file to each phone (USB, Bluetooth, cloud storage, ...).
3. Open the file on the phone and allow **Install unknown apps** if Android asks.

## First-time setup

1. Open **Bluetooth Chat**.
2. Type a **display name**. This is what other people see (up to 24 characters). You can change it later in Settings.
3. Tap **Get started**.
4. Allow the permissions Android asks for:
   - **Nearby devices** (Android 12 and newer), or **Location** (Android 11 and older). Bluetooth needs this to find phones.
   - **Notifications** (Android 13 and newer). Optional, but you will not see new-message alerts without it.
5. If Bluetooth is off, the app asks to turn it on. Tap **Allow**.

> **Android 11 or older:** also switch **Location** on in the quick settings. Without it Android returns no Bluetooth scan results.

You will now see the main screen with the **mesh switch** turned on.

## Connect two phones

This is the most important step. Android only lets phones find each other in two cases:

- The phones are **paired**, or
- At least one phone is **discoverable** at that moment.

### Recommended: pair the phones once

1. On both phones open **Settings > Bluetooth** and pair them with each other.
2. Open Bluetooth Chat on both phones and make sure the mesh switch is on.
3. Within about a minute each phone appears under **Nearby devices** on the other.

After pairing once, the phones reconnect automatically every time they are in range. You do not need to do anything again.

### Without pairing: make a phone discoverable

1. On one phone tap the **three-dot menu** (top right) and choose **Make discoverable**.
2. Confirm Android's prompt. The phone is discoverable for 5 minutes.
3. The other phone finds it during its next scan. Use **Scan now** in the menu to speed this up.

Once the phones have connected, they remember each other and reconnect without discovery.

## Using the app

### Main screen

| Part | What it does |
|---|---|
| **Mesh switch** | Turns Bluetooth Chat on or off. Green dot = running. |
| **Nearby devices** | Phones you can reach now. A green dot means directly connected, an amber dot means reachable through other phones. |
| **Chats** | Your conversations, newest first. Unread messages show a badge. |
| **Three-dot menu** | *Scan now* and *Make discoverable*. |
| **Gear icon** | Opens Settings. |

### Start a chat

1. Tap a phone under **Nearby devices**.
2. Type a message and tap the send button.

You can also open an existing chat from the **Chats** list, even when that person is out of range.

### Message status

| Status | Meaning |
|---|---|
| **Sending** | Saved on your phone, waiting for a path to the other person. It sends automatically when one appears. |
| **Sent** | Handed to a nearby phone. |
| **Delivered** | The other person's phone confirmed it arrived. |
| **Failed** | Could not be delivered within 24 hours. |

The top of a chat shows whether the person is **connected directly**, **reachable via relay**, or **out of range**.

### Delete a chat

Press and hold a chat in the **Chats** list and confirm.

### Keep it running in the background

While the mesh is on, a notification **Bluetooth Chat is running** stays in your notification bar. This is needed so Android does not stop the app. Tap **Stop** in the notification to switch the mesh off. Open the app and flip the switch to start it again.

## How messages travel

```
You  ──►  Phone B  ──►  Phone C
```

If you and Phone C are out of each other's range but both near Phone B, your message goes through B. B can only see that a message passes by, not what it says.

- Phones announce themselves to the network about once a minute so others learn who is nearby.
- A message can pass through up to **5 phones**.
- If nobody is in range, the message waits on your phone and is retried automatically.

## Settings

Open the **gear icon** on the main screen.

| Setting | What it does |
|---|---|
| **Display name** | The name other people see. Others see a new name within about a minute. |
| **Relay messages for others** | When on, your phone helps pass messages between other phones. Turn it off to save battery, but your own chats still work. |
| **Clear all chats** | Deletes every message on your phone. Cannot be undone. |
| **Device ID** | The first characters of your unique ID, useful for identifying your phone. |

## Troubleshooting

| Problem | What to try |
|---|---|
| **No phones appear under Nearby devices** | Make sure the mesh switch is on and Bluetooth is on on **both** phones. Pair the phones in Android's Bluetooth settings, or use **Make discoverable** on one of them. Then tap **Scan now**. |
| **Still nothing on Android 11 or older** | Turn on **Location** in the quick settings. |
| **The mesh switch turns itself off** | A permission was denied. Open Android **Settings > Apps > Bluetooth Chat > Permissions** and allow *Nearby devices* (or *Location* on Android 11 and older), then turn the switch on again. |
| **Messages stay on "Sending"** | The other person is out of range, or their phone has not announced itself yet. Move closer and wait up to a minute. |
| **It works for a while, then stops** | Some phone makers (Xiaomi, Oppo, Vivo, Huawei, Samsung and others) stop background apps to save battery. Open **Settings > Apps > Bluetooth Chat > Battery** and choose **Unrestricted** or **Don't optimise**. |
| **No new-message alerts** | Allow notifications for Bluetooth Chat in Android settings. |
| **A phone connects, drops, then reconnects** | Normal. If two phones try to connect at the same moment, one of the links is closed and the next scan reconnects them. |
| **The app asks to be set up again** | The app's data was cleared or the app was reinstalled. Chats and your identity are not recoverable after that. |

## Privacy and security

- **End-to-end encryption.** Each phone has its own key pair. Two phones agree on a secret key that nobody in between can compute (ECDH, P-256) and encrypt every message with AES-256-GCM. Any change to a message on the way is detected.
- **Your private key stays on your phone**, encrypted with a key held in the Android Keystore.
- **Identity is tied to your key.** Your device ID is derived from your public key, so nobody can pretend to be you using a different key.
- **No server, no account, no tracking.** Nothing leaves your phone except Bluetooth traffic to phones nearby.
- **Local storage only.** Chats are saved in the app's private storage. Cloud backup is disabled for the app. Uninstalling the app deletes them.
- **Notification privacy.** On a locked screen, new-message notifications do not show the message text.

## Limitations

- **Bluetooth range is short**, about 10 to 30 metres per hop. A relay only helps if phones are spaced within range of each other.
- **Relays can see routing information**: who a message is from and to, and its size, but not its content.
- **No forward secrecy.** If someone steals a phone's private key, messages that were recorded earlier could be decrypted.
- **First contact is not verified.** The app proves that a message comes from the owner of a particular ID, but cannot tell you who that person really is. Compare device IDs in person if it matters.
- **Delivery is not guaranteed.** Messages are retried for 24 hours, then marked **Failed**.
- **Small networks only.** Messages are flooded through the mesh, which works well for a handful of phones but not for dozens.
- **About 6 simultaneous connections** per phone, a limit of Android Bluetooth.
- **Messages up to 2000 characters.** Text only, no photos or files.
- **Battery.** Running the mesh and scanning regularly uses more battery. Switch the mesh off when you do not need it.

## Permissions explained

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`) | Find other phones, connect to them, and make your phone discoverable. Android 12+. |
| Location (`ACCESS_FINE_LOCATION`) | Required by Android for Bluetooth scanning on Android 11 and older. The app does not read your location. |
| Notifications (`POST_NOTIFICATIONS`) | Show the "running" notification and new-message alerts. Android 13+. |
| Foreground service (connected device) | Keeps the mesh running while the app is closed. |

## For developers

### Tech stack

- Java, Android SDK (min SDK 26)
- Bluetooth Classic RFCOMM sockets
- Room (SQLite) for chats and known peers
- Material Components, RecyclerView, ViewBinding, LiveData
- Android Keystore, ECDH (P-256), HKDF-SHA256, AES-GCM

### Project structure

```
app/src/main/java/com/example/bluetoothchat/
├── ui/          Activities and adapters (Onboarding, Main, Chat, Settings)
├── model/       Message, Peer, Conversation, Packet
├── db/          AppDatabase, MessageDao, PeerDao
├── bluetooth/   BluetoothConnectionManager (links), DeviceScanner (discovery)
├── mesh/        MeshService (foreground service), MeshRouter (routing), SeenPacketCache
├── crypto/      CryptoManager (encryption), IdentityManager (keys, name, settings)
└── util/        PermissionHelper, TimeUtils, InsetsHelper
```

### How it fits together

- `MeshService` owns the connection manager, scanner and router and keeps them alive as a foreground service.
- `DeviceScanner` finds paired and discoverable phones and asks the connection manager to connect.
- `BluetoothConnectionManager` sends and receives length-prefixed byte frames over RFCOMM.
- `MeshRouter` decides what happens to each packet: handshake (`HELLO`), announcements (`ANNOUNCE`), encrypted messages (`MESSAGE`) and receipts (`ACK`). It delivers, forwards (TTL 5, duplicate suppression) or queues them.
- The UI reads state from `MeshService` through static `LiveData` and from Room through `LiveData`.

### Build notes

- Requires Android Studio with a recent Android Gradle Plugin and JDK 11 or newer.
- The database uses `fallbackToDestructiveMigration()` while in development. Replace it with proper migrations before publishing, otherwise an app update would delete users' chats.
- Bluetooth cannot be tested on the emulator. Use at least two physical phones (three to test relaying).

### Contributing

Issues and pull requests are welcome. Please describe the phones and Android versions you tested on.

### License

No license has been chosen yet. Add a `LICENSE` file to state how others may use the code.

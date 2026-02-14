# AirLens Mobile

**AirLens Mobile** is a standalone Android application that allows you to use one phone as a wireless camera and another phone as a remote monitor/controller. 

> **No PC required!** This is the pure mobile version of the AirLens project.

![License](https://img.shields.io/badge/license-MIT-blue) ![Platform](https://img.shields.io/badge/platform-Android-green)

## Features

* **Android-to-Android**: Use one phone as the Server (Camera) and another as the Client (Controller).
* **Low Latency**: Direct Wi-Fi streaming via TCP Sockets.
* **Remote Control**:
    * **Zoom**: Control camera zoom remotely.
    * **Flash**: Toggle flashlight on/off.
    * **Switch Camera**: Switch between front and back cameras.
    * **Rotation**: Fix orientation (90°/180°/270°) remotely.
* **Intercom**: Push-to-talk voice transmission.
* **Snapshot**: Save high-quality screenshots to your phone gallery.
* **Universal Compatibility**: Works on almost all Android devices (Huawei, Honor, Xiaomi, Realme, OPPO, etc.).

## How to Use

1.  **Download**: Get the latest `AirLens_Mobile.apk` from the [Releases](../../releases) page.
2.  **Install**: Install the same APK on **two Android phones**.
3.  **Connect**: Ensure both phones are on the same Wi-Fi (or one uses the other's Hotspot).
4.  **Assign Roles**:
    * **Phone A**: Tap "我是摄像头 (Server)". Note the IP address displayed.
    * **Phone B**: Tap "我是控制器 (Client)". Enter Phone A's IP and connect.

## Tech Stack

* **Language**: Kotlin
* **Camera API**: CameraX
* **Networking**: Java Sockets (TCP)
* **Image Processing**: Custom YUV-to-NV21 algorithm for broad device compatibility.

## License

MIT License

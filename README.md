# ScreenSharer

ScreenSharer is an experimental Android screen-mirroring app. Install the same APK on an Android phone and a compatible Fire TV. The phone captures its display after you approve Android's screen-capture prompt and streams H.264 video over your local network. The Fire TV app receives and displays that stream.

## Status

This is an early MVP, not a polished or published release. The receiver connects by manually entered phone IP address; automatic device discovery is not implemented. There is no hosted APK release in this repository. The app streams video only, supports one receiver at a time, and has not been verified across Fire TV models.

## Download Or Build

### Use an APK from this workspace

If the APK has already been built, download [app-debug.apk](app/build/outputs/apk/debug/app-debug.apk). Build output is ignored by Git and is not included in a fresh clone, so this link may not work when viewing the repository online.

### Build the APK

Prerequisites:

- JDK 17
- Android SDK Platform 34
- Internet access for the first Gradle dependency download

From PowerShell in the project folder, run:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also open the folder in Android Studio, sync the Gradle project, and build the `app` debug variant. This debug APK is signed with a development key and is intended for testing, not publishing to an app store.

## Install

Install the same APK on both devices. Sideloading requires allowing installation from the app or tool used to open the APK.

To install on an Android phone connected by USB with ADB enabled:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To install on Fire TV over the network:

1. Enable ADB debugging in Fire TV Developer Options and find the TV's IP address in Network settings.
2. Connect the computer and TV to the same local network.
3. Connect and install:

```powershell
adb connect FIRE_TV_IP:5555
adb -s FIRE_TV_IP:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Replace `FIRE_TV_IP` with the TV's address, for example `192.168.1.25`. Fire TV menus vary by Fire OS version. The device must support Android API 23 or later and H.264 decoding.

## Use

1. Connect the phone and Fire TV to the same Wi-Fi network. Avoid guest Wi-Fi, which often blocks devices from reaching each other.
2. On the phone, open ScreenSharer, choose **Share this phone**, and tap **Start sharing**.
3. Approve Android's screen-capture prompt. Leave ScreenSharer running while sharing.
4. On Fire TV, open ScreenSharer, enter the phone's displayed IP address, and choose **Connect**.
5. To stop, use **Stop sharing** in the phone app or its ongoing notification. Use **Disconnect** on the TV to close the receiver.

The phone listens on TCP port `45821`. Both devices must be able to communicate directly over the local network. VPNs, router client isolation, or firewall rules may block the connection.

## Limitations And Privacy

- Video only; system or app audio is not streamed.
- Only one Fire TV receiver can connect at a time.
- The local TCP video stream is not encrypted or authenticated. Use it only on a trusted private network; anyone on that network who can reach the phone's listening port may be able to connect.
- Android and individual apps can prevent capture of protected content. DRM video services may display a black video area; ScreenSharer does not bypass those restrictions.
- Screen-capture choices depend on Android version and device. App-window sharing behavior is not guaranteed by this MVP.
- Fire TV compatibility depends on its Fire OS version and hardware decoder. Some older devices may not install or decode the stream.

## Troubleshooting

- **The TV cannot connect:** confirm the IP address on the phone, verify both devices are on the same non-guest Wi-Fi, and check that router client isolation or a VPN is not blocking local traffic.
- **The phone stays on the waiting message:** start sharing on the phone first, keep the app open, then connect from the TV. Only one receiver can connect.
- **The TV connects but shows a black or frozen image:** try the phone home screen or another non-protected app. Protected content can be blank by design; otherwise disconnect and reconnect both devices.
- **The APK will not install:** verify the Fire TV supports API 23 or later and allow installation from the sideloading app. Check that the APK file transferred completely.
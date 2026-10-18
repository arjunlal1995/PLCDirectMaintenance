# PLC Direct Maintenance (Android / Kotlin)

This is a source project for an Android application with a Compose UI, PLC driver code, and notification/service components. PLC communications must be tested against the specific hardware and network before operational use. The displayed demo alert buttons do not establish that a connected PLC is safe or communicating.

## Build an APK on GitHub (no PC build environment required)

1. Upload the contents of this ZIP to the root of a GitHub repository (the `app`, `gradle`, `.github`, and Gradle build files should be at the repository root).
2. Open the repository's **Actions** tab.
3. Select **Build PLC Direct Maintenance APK** and choose **Run workflow**, or push to the `main`/`master` branch.
4. Wait for the workflow to finish. If it succeeds, open the run and download `plc-direct-maintenance-debug-apk` from **Artifacts**.
5. Extract the downloaded ZIP and install the APK on a compatible Android device. Allow installation from the file manager if Android asks.

The workflow uses Gradle 8.5 and JDK 17 and builds `assembleDebug`. The APK is a debug/testing build, not a Play Store release.

## Build locally in Android Studio

Open this directory in Android Studio and allow Gradle sync. Ensure Android SDK Platform 34 and a compatible Android Gradle Plugin are installed. Then run **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Important limitations

- This archive intentionally uses GitHub Actions' Gradle installation because the original archive did not include the Gradle Wrapper JAR.
- A successful compile does not verify PLC protocol behavior or hardware safety. Validate with the actual PLC, adapter, network configuration, and vendor documentation before field use.
- Keep PLCs isolated from untrusted networks. Do not rely on this app as a safety-rated emergency-stop or protection system.

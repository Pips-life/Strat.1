# Strat.1 Mobile Release Policy

Release artifacts are progressive and immutable.

- Initial: versionName 0.1.0, versionCode 1.
- Every later APK must have a higher versionCode.
- The same applicationId and release signing identity must be retained for upgrades.
- APKs are promoted only after automated build, install, launch, MT5-form validation, update-manifest, and security checks pass.
- A release manifest contains versionName, versionCode, minimumSupportedVersionCode, mandatory flag, notes, APK URL, and SHA-256.
- The app checks the manifest, compares versionCode, verifies the downloaded artifact checksum, and then invokes the Android installation flow.
- If distributed through Google Play, Play's update mechanism remains preferred; direct APK update is a separate distribution channel.

# Third-party licenses

This project began as a fork of **baes-cloud**'s original dashboard, released
under the MIT License. This project's own code, and the modifications made
here, are licensed under the GNU General Public License v3.0 (see
[`LICENSE`](./LICENSE)) — but the MIT License's own terms require its notice
to stay attached to the portions of the code it originally covered, so that
notice is reproduced verbatim below.

## Original base code — MIT License

```
MIT License

Copyright (c) 2026 baes-cloud

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries included in the app (1.5.0)

The APK ships these open-source libraries, each under its own license (unchanged, as published by
its authors). None of them is GPL-incompatible.

| Library | Version | License | Used for |
| :--- | :--- | :--- | :--- |
| [AndroidX Core, Activity, Jetpack Compose](https://developer.android.com/jetpack/androidx) (UI, Foundation, Material 3, Material icons) | see `app/build.gradle.kts` | Apache License 2.0 | The app's screens |
| [Kotlin standard library, kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.8.1 | Apache License 2.0 | Background work |
| [kotlinx.serialization (JSON)](https://github.com/Kotlin/kotlinx.serialization) | 1.7.1 | Apache License 2.0 | `dashboard.json`, device APIs |
| [OkHttp](https://github.com/square/okhttp) and Okio | 4.12.0 | Apache License 2.0 | HTTP / WebSocket (Home Assistant, Zidoo, updates) |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 2.3.1 | BSD 3-Clause | The remote's own web page |
| [ZXing core](https://github.com/zxing/zxing) | 3.5.3 | Apache License 2.0 | QR code of the web page's address |
| [Bouncy Castle (bcprov)](https://www.bouncycastle.org/licence.html) | 1.78.1 | Bouncy Castle License (MIT-style) | Apple TV pairing cryptography |

The TV Helper app (`tvhelper/`) uses only the Android platform. JUnit (Eclipse Public License 1.0)
is used for the unit tests only and is not part of the APK.

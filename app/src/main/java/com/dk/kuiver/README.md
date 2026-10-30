# Vendored kuiver sources

The `com.dk.kuiver` package is a source vendoring of
[kuiver](https://github.com/justdeko/kuiver) v0.5.0 by Justus Kreutzfeld, which is
also [published on Maven Central](https://central.sonatype.com/artifact/io/github/justdeko/kuiver)
under the **Apache License 2.0** (see `LICENSE` below).

## Why vendored instead of depended on

Every kuiver release (0.3.1–0.5.0) ships AAR metadata requiring
`minCompileSdk 37` (and kuiver 0.5.0 additionally requires AGP 9.1 via its
republished JetBrains Compose 1.12 transitives). This project builds with
compileSdk 36 and AGP 8.12, so the published artifacts cannot be consumed here
without a full toolchain migration. The sources are self-contained (only
`androidx.compose.{runtime,foundation,ui}` + Kotlin stdlib, all already used by
the app), so they are compiled as plain app sources instead.

## What was changed vs upstream v0.5.0

- `renderer/PlatformDefaults.kt`: upstream `expect object` + per-platform
  `actual`s merged into a single object holding the Android values.
- Nothing else. All files keep their original contents and package names so a
  future re-vendor is a plain copy.

To update: copy `commonMain/com/dk/kuiver` from the new release over this
directory (keeping the merged `PlatformDefaults.kt`), and re-run the Graph
screen tests.

## License (Apache License 2.0, kuiver)

```
Copyright (c) Justus Kreutzfeld (justdeko)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

The full license text: https://www.apache.org/licenses/LICENSE-2.0

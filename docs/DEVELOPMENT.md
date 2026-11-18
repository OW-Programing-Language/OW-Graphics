# Build and run

Prerequisites: JDK 17+, Maven 3.9+, Git, working OpenGL 3.3 drivers and a desktop session for the GUI demo.

## 1. flOW source dependency

Run in the root of this repository:

```sh
ow deps install
ow check tests/headless_flow.ow
ow run tests/headless_flow.ow
ow check src/main.ow
```

The manifest pins flOW by a real commit SHA. `dependencies.lock.yml` is committed and pins the exact checked-out flOW revision. `ow deps install` validates and uses this lock; do not commit `.ow/deps/`.

## 2. Java / LWJGL runtime provider

`ow-lang` is a sibling checkout of `OW-Graphics`. Install its Maven artifact locally:

```sh
mvn -f ../ow-lang/pom.xml -DskipTests install
mvn -f native-bridge/pom.xml test
mvn -f native-bridge/pom.xml package dependency:copy-dependencies -DoutputDirectory=target/lib
```

For Windows, LWJGL natives are selected automatically by the Maven OS profile; to override a classifier use `-Dlwjgl.natives=natives-windows`. On Apple Silicon use `-Dlwjgl.natives=natives-macos-arm64`.

To run with the extension and its native libraries on Linux/macOS:

```sh
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run src/main.ow
```

Windows PowerShell (semicolon-separated classpath):

```powershell
java -cp "../ow-lang/target/OwIDE.jar;native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar;native-bridge/target/lib/*" dev.oreo.Main run src/main.ow
```

The Java SPI extension is discovered through `META-INF/services/dev.oreo.RuntimeExtension`. Normal `ow run` without it will **fail closed** if an operation calls `extension("graphics.*")`. Do not bypass this with stubs.

## Constraints

- First OpenGL demo is intentionally **one window, one thread, one triangle**. Close by pressing Escape or the window close button.
- GLFW/OpenGL need a real display and GPU-compatible drivers; the portable CI only runs headless lifecycle tests and extension-unit tests.
- Vulkan does not work yet. No backend fallback is silently selected.
- The LWJGL dependency is a Java binding to native OpenGL/GLFW libraries, not a pure-Java software renderer.

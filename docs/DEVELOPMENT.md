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

## 3. Programmable OpenGL triangle

The new demo is [examples/programmable_triangle/main.ow](../examples/programmable_triangle/main.ow). It creates its own GLSL vertex/fragment program, uploads an OW float array (nine coordinates per triangle), binds a vec4 tint uniform, and renders through a typed flOW scene.

```sh
ow check examples/programmable_triangle/main.ow
ow run tests/headless_resources.ow
# Launch with the same JVM classpath as above, replacing src/main.ow:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/programmable_triangle/main.ow
```

The binding accepts **non-indexed GL_TRIANGLES** only: a flat array of x/y/z coordinates, with 9 numbers for each triangle (maximum 300,000 numbers). Shader source must be nonblank and at most 65,536 characters. GPU handles are scoped to the open window; stale and destroyed handles cause errors rather than silently invoking raw GL IDs. A vec4 uniform is set with `shader.set_vec4(handle, name, r, g, b, a)` after `shader.bind(handle)`.

The new headless integration test checks flOW module/service startup without GL. JVM tests validate malformed shader and mesh inputs without opening a window. GUI rendering still requires a machine with an OpenGL 3.3 desktop context and manual validation.

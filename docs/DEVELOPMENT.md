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

## 4. Indexed textured meshes and OW matrices

`examples/textured_quad/main.ow` shows a two-triangle indexed square using a **single shared position+UV vertex buffer and EBO**, a 2×2 RGBA8 texture, sampler uniform, and column-major 4×4 matrix. `Graphics.Transforms` is implemented in OW, separate from the native OpenGL provider; flOW independently manages the Mesh, Shader, Texture, Transform, Renderer and Window modules.

```sh
ow check examples/textured_quad/main.ow
ow run tests/headless_textures.ow
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/textured_quad/main.ow
```

The OpenGL bridge validates **before GPU upload**:

- `mesh.create_indexed(positions, uvs, indices)` takes XYZ triples, exactly two UV coordinates per vertex, and triangle index triples within vertex range; positions, UVs and indices are bounded.
- `texture.create_rgba8(width, height, pixels)` accepts a flat integer array of `width * height * 4` RGBA channels (0–255), row-major with the **bottom row first**. Maximum dimension is 2048 per side. Filtering is nearest-neighbor and wrapping clamps to edges.
- `shaders.set_mat4(program, name, values)` requires precisely 16 finite floats in **column-major** OpenGL order; `shaders.set_int` binds sampler units.
- `transforms.identity()`, `translation(x,y,z)`, `scaling(x,y,z)` and `compose(a,b)` return 16-value OW arrays. Composition is `a * b`, with `b` applied first.

`mvn -f native-bridge/pom.xml test` validates malformed inputs without a GPU. The Mesa/Xvfb CI tests four separate pixel colors of a real indexed textured quad **and** runs a complete OW+flOW native smoke application. Physical GPU and other-OS tests are still required.

**Not implemented:** PNG/JPEG file reading, mipmaps, GPU-managed texture arrays, perspective cameras, lighting, Vulkan, arbitrary native LLVM output, or a full scene graph.

## 5. Phase 4 — true 3D, camera, depth and lighting

Try the [3D cube application](../examples/rotating_cube/main.ow). It runs the same OW `MakeMeshScene(Renderer)` scene functor with additional independent flOW services: `Camera`, `Transforms`, `CubeGeometry` and `Daylight`.

```sh
ow deps install
ow check examples/rotating_cube/main.ow
ow run tests/headless_3d.ow
# Linux/macOS, after building native-bridge as above:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/rotating_cube/main.ow
```

### Coordinate convention and modules

- Right-handed world, +Y up, camera views toward -Z by default.
- `camera.look_at(eyeX,eyeY,eyeZ,targetX,targetY,targetZ,upX,upY,upZ)` creates a normalized view matrix. Eye and target must differ; up cannot be parallel to view.
- `camera.perspective(fovY,aspect,near,far)`: radians, field of view between 0.3 and 2.4, positive aspect and near, far greater than near.
- `transforms.rotation_y(angle)`: angle in radians between -π and π, using an explicit stable finite Taylor approximation because OW does not yet include floating-point trigonometric builtins. The example wraps rotation each frame.
- `transforms.compose(a,b)` multiplies OpenGL column-major 4×4 matrices as `a*b`, applying `b` before `a`.
- `renderer.depth_test(1)` turns on a depth buffer with `GL_LESS`; `depth_test(0)` turns it off. Depth is disabled by default so all earlier 2D demos remain unchanged. `renderer.clear` clears the color and depth buffers.
- `meshes.create_lit_indexed(xyz,uv,normals,indices)` uploads separate XYZ normals to GLSL attribute location 2; the previous 5-float indexed geometry layout remains supported.
- `geometry.positions/uvs/normals/indices` return 24 vertices and 36 indices for a unit cube with flat face normals. `lighting.direction()` and `lighting.levels()` are independent, swappable OW service values.
- Lighting is simple ambient + Lambert diffuse per face. Nonuniform scaling requires inverse-transpose normal matrices (not yet supported); the demo uses rotation only.

### Automated validation

- `tests/headless_3d.ow` verifies the camera, view/project matrices, Y rotation, geometry and daylight modules without any JVM OpenGL plugin.
- `Graphics3DArgumentsTest` verifies rejected bad/zero normals and invalid depth toggles.
- `DepthLightingGpuSmoke` exercises real OpenGL depth occlusion and an actual normal-based light/dark difference using Mesa/Xvfb framebuffer reads.
- `examples/rotating_cube/smoke.ow` exercises a complete OW→flOW→GLFW→OpenGL lit cube draw and resource cleanup; it exits after one frame for CI.

A system with compatible OpenGL 3.3 drivers is still needed to validate actual interactive rendering and performance on Windows/macOS/physical GPU hardware.

**Not implemented:** mouse-orbit controller, point lights, materials/PBR, shadow mapping, scene graph/occlusion culling, skeletal animation, or Vulkan.

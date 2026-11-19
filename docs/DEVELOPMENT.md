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

## 6. Phase 5 — orbit camera, multiple lights and Wavefront OBJ

The example [model_viewer/main.ow](../examples/model_viewer/main.ow) loads [an original small OBJ pyramid](../examples/model_viewer/assets/pyramid.obj), generates normals when they are absent, uploads indexed geometry and renders three additive Lambert sources: two directional lights and a distance-attenuated point light. The world stays right-handed, +Y up.

```bash
ow check examples/model_viewer/main.ow
ow run tests/headless_orbit.ow
mvn -f native-bridge/pom.xml test

# From the OW-Graphics repository root, after building the bridge:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/model_viewer/main.ow
```

Controls: **hold left mouse and drag** to orbit, **scroll** to zoom, **arrow keys** to orbit, **Escape** to close. Cursor deltas initialize to zero on first sample and reset when the window reopens. All GLFW input calls run on the owning window thread. The orbit controller is a one-parameter native OW functor `MakeOrbitController(Camera)`; yaw/pitch/distance belong to the application rather than global state. Pitch and zoom are clamped.

### OBJ import, formats and safety

The `GRAPHICS_MODEL` module exposes two methods:

```ow
set importer GRAPHICS_MODEL: app.service("model")
set geometry: importer.load_obj("examples/model_viewer/assets/pyramid.obj")
# Or: importer.parse_obj("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3")
set meshId int: meshes.create_lit_indexed(geometry[0], geometry[1], geometry[2], geometry[3])
```

`parse_obj` returns four OW arrays: `[xyz, uv, normals, triangleIndices]`. The first importer supports `v`, `vt`, `vn`, `f`, positive and negative indices, missing UV/normal fallbacks and **fan triangulation for convex polygons**. Face corners are expanded into vertices so UV seams and flat normals are retained without accidentally welding incompatible corners.

`load_obj` reads only a `*.obj` **relative to the current working directory**, rejecting parent traversal, absolute paths and symlinks escaping the project. The parser caps source input at 1 MiB and the output at 100,000 rendered vertices. No external HTTP requests, executable asset features, or arbitrary native pointers. Concave n-gons, MTL materials, smoothing groups, PNG/JPEG texture loading and skeletal animation are **not** yet supported.

### Lighting model and tests

`GRAPHICS_LIGHT_RIG` supplies independent key/fill directions, point position, intensities and ambient RGB; `Graphics.StudioLights` is the example preset. The shader uses normal-based diffuse terms, separate warm/cool tones and `1/(1+0.12*d²)` point attenuation. Light parameters are shader uniforms owned by the OW application, not hard-coded Java shader logic.

CI checks `tests/headless_orbit.ow`, CPU-only OBJ validation (including path sandboxing), native GLFW input polling, a multi-light framebuffer test, actual `tests/obj_loader.ow`, and a one-frame `examples/model_viewer/smoke.ow` under Mesa/Xvfb.

**Limitations:** basic fixed camera/orbit controls, no gamepad input, no mouse capture/fps controller, no variable-rate key controls, no OBJ material library/texture file loading, no native OW AOT or Vulkan backend.

## 7. Phase 6 — materials, PNG/JPEG and hierarchical multi-object scenes

[The material scene example](../examples/material_scene/main.ow) loads a real original checkerboard PNG, plus the pyramid OBJ from Phase 5. Both objects use the **same GPU mesh and texture handle**, but distinct material presets and model matrices. A parent transform controls the child model, and the application clears the framebuffer once per frame with `MakeMultiScene(Renderer)`.

```sh
ow deps install
ow run tests/headless_material_scene.ow
ow run tests/headless_multiscene.ow
ow check examples/material_scene/main.ow
# In repository root, build the native bridge and install OW compiler as above
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/material_scene/main.ow
```

The renderer draws two OBJ instances with different copper/azure tints and ambient/diffuse/specular/shininess material values in the shader. The original `GRAPHICS_MESH_SCENE` remains unchanged for all older demos.

### PNG/JPEG safety and API

The `GRAPHICS_TEXTURE` contract now includes `load_image(relativePath)`. Supported local formats: PNG (`.png`) and JPEG (`.jpg`, `.jpeg`) through JDK ImageIO; the file contents must match the extension. Files must reside below the **process working directory**, with absolute paths, parent traversal and escaping symlinks rejected. Compressed file size is limited to 8 MiB and image dimensions to 2048×2048.

Images are decoded on CPU and converted to bottom-row-first RGBA8 for OpenGL; PNG alpha is preserved and JPEG is fully opaque. The resulting GPU texture uses the existing nearest/clamp-to-edge policy. A headless texture module returns 0 for this GPU-only file load, rather than claiming image decoding without an adapter.

### Graph and material contracts

`MakeSceneGraph(Graphics.Transforms)` creates a `GRAPHICS_SCENE_GRAPH` service. `resolve(parents, localMatrices)` expects parent-first nodes; a parent index of `-1` means root, and any other parent must have a smaller index. It returns a list of column-major world matrices, with a maximum of 1024 nodes. The application owns node mesh/material mappings and binds uniforms before each `draw_object(meshHandle)`.

`GRAPHICS_MATERIAL_LIBRARY` exposes `tint(name)` as an RGBA vector and `properties(name)` as `[ambient, diffuse, specular, shininess]`. Example names: `copper`, `azure`, `stone`. These are **Phong-style shader parameters**, not parsed OBJ/MTL material definitions, and users can replace the library module through flOW.

### Tests

The build verifies OW contracts and parent-child transform composition without GPU, decoder safety and both PNG/JPEG via CPU unit tests, and Mesa framebuffer pixels for **two separately tinted textured objects**, plus an end-to-end OW→flOW→OpenGL two-object example. See [Materials & Scenes](MATERIALS-AND-SCENES.md).

**Not implemented:** MTL material files, glTF, gamma-aware image pipelines, PBR maps, batching/instanced draws, scene animations, shadows, or a Vulkan backend.

## 8. Phase 7 — static glTF 2.0, direct-light PBR and instancing

This milestone loads a **real embedded-buffer glTF 2.0 file** in [`gltf_instancing`](../examples/gltf_instancing/main.ow), uploads its indexed geometry, and renders three copies with two OpenGL draw calls. Two copies share a metallic-gold material while the third uses imported `baseColorFactor`, `metallicFactor`, `roughnessFactor`. The middle copy moves along an original pure OW translation keyframe animation.

```sh
ow deps install
ow run tests/headless_pbr_animation.ow
ow check examples/gltf_instancing/main.ow
mvn -f native-bridge/pom.xml test
# From repository root, with the OW runtime and native bridge built:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/gltf_instancing/main.ow
```

`GRAPHICS_GLTF_MODEL.load_gltf("relative/file.gltf")` or `parse_gltf(json)` returns:

```text
[positionsXYZ, uvPairs, normalXYZ, triangleIndices,
 baseColorRGBA, metallicFactor, roughnessFactor]
```

The importer supports **one mesh with one TRIANGLES primitive**, embedded base64 `data:application/octet-stream` buffer only; FLOAT VEC3 positions/normals, FLOAT VEC2 UVs and unsigned BYTE/SHORT/INT indices, with bufferView offsets and strides. If normals are absent, they are reconstructed from triangles; absent UVs default to zeros. It extracts base color and metallic/roughness scalar factors. Its strict bounds are **1 MiB JSON**, **4 MiB decoded buffer**, **100,000 vertices**, and the existing GPU index/position checks. File paths are relative to the working-directory project sandbox.

It **does not yet support** external buffers, GLB, multiple meshes/primitives, glTF scene nodes, skins, morph targets, glTF animation channels, texture extensions or PBR texture maps. Do not claim glTF-compliant importing outside this subset.

`GRAPHICS_KEYFRAME_ANIMATION.translation(times, positions, time)` linearly interpolates keyframe xyz vectors, clamps before/after endpoints, checks increasing time and is pure OW. It accepts max 1024 keys. The example advances time each frame (not a delta-time-correct clock); proper animation scheduling and glTF animation import remain future work.

`GRAPHICS_MESH.draw_instanced(mesh, [modelMatrixA, modelMatrixB, ...])` accepts 1–512 finite 16-number matrices and makes a single native GL instanced draw for each invocation. GLSL must declare `layout(location=3) in mat4 aInstance;` (attribute slots 3,4,5,6). Mesh instance buffers persist between draws, and are cleaned with the mesh/context. This reduces draw-call overhead for repeated geometry; it does **not** automatically batch different shaders/materials/textures.

The PBR-inspired GLSL sample implements **direct-light GGX normal distribution, Schlick Fresnel, Smith geometry and metallic/roughness** with tone mapping. It is a deliberately restricted shading demonstration without image-based lighting, HDR environment maps, shadow maps, color-managed texture pipelines or glTF material texture maps.

Verification includes static glTF CPU parser validation and sandbox tests, OW/flOW keyframes/PBR contract tests, real GPU framebuffer proof of two instances, and full OW→flOW→GLFW→OpenGL instanced example. See [glTF & Instancing](GLTF-AND-INSTANCING.md).

## 9. Phase 8 — static multi-mesh scenes in GLB and glTF

The [GLB scene example](../examples/gltf_scene/main.ow) loads an original, small `multi_scene.glb` binary and its equivalent `multi_scene.gltf` JSON asset. The files contain two mesh definitions, three primitives, three PBR color/factor settings and four scene nodes. Parent/child transforms are composed by the **pure OW scene graph**, not a new Java renderer.

```sh
ow deps install
ow check tests/headless_gltf_scene.ow
ow check examples/gltf_scene/main.ow

# After building the OW runtime and native-bridge:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run tests/headless_gltf_scene.ow
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/gltf_scene/main.ow
```

The new `GRAPHICS_GLTF_SCENE` service exposes `load_scene(relativePath)` for `.gltf` / `.glb`, and `parse_scene(jsonSource)` for JSON. It returns:

```text
[
  primitiveMeshes,    # array of Phase-7 7-field geometry/material arrays
  parentIndices,      # parent-first, -1 = root
  localMatrices,      # 16-float OpenGL column-major matrices
  nodePrimitiveIds    # 0..N primitive indexes per node
]
```

Use `GRAPHICS_SCENE_GRAPH.resolve(parentIndices,localMatrices)` for world transforms, upload **each unique primitive once** with `GRAPHICS_MESH.create_lit_indexed`, and draw its handle from each visible node. This lets multiple nodes reuse the same mesh resource.

The current parser recognizes the active glTF scene, multiple mesh/primitive records, and TRS with quaternion rotation as well as explicit node matrices. Format limits: 1 MiB JSON, 4 MiB BIN data, 64 mesh definitions, 64 primitives total, 1024 nodes and 32 scenes. Only the existing static TRIANGLES accessor subset, one embedded data URI or a GLB BIN chunk is supported. GLB chunks are explicitly validated with version, magic, length and padding; filenames remain sandboxed under the working directory.

**Not implemented:** GLB buffer URIs or external buffer files, KHR extensions, full glTF material/texture maps, skins, morph targets, glTF animation-channel playback, non-triangle primitives, multiple BIN chunks, scene-level batching/occlusion or native Vulkan. The module rejects declared skins and animation tracks rather than pretending to render them.

See [GLB Scenes](GLB-SCENES.md) for the module diagram, examples and current capabilities.

## 10. Phase 9 — animated glTF 2.0 skeleton and GPU skinning

The [animated two-joint example](../examples/gltf_skinning/main.ow) uses an original [binary GLB](../examples/gltf_skinning/assets/wave.glb) and the equivalent [JSON glTF](../examples/gltf_skinning/assets/wave.gltf) with two bones, a skinned quad mesh and three animation tracks. The left vertices are attached to the root, while the right vertices are attached to the moving/rotating child joint. GPU vertex skinning is performed in GLSL using uniform `mat4 uBones[32]`.

```bash
ow deps install
ow check tests/gltf_skinning.ow
ow check examples/gltf_skinning/main.ow
mvn -f native-bridge/pom.xml test
# With native OpenGL provider on the classpath, run from OW-Graphics root:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/gltf_skinning/main.ow
```

`GRAPHICS_GLTF_SKIN.load_rig(path)` accepts project-relative `.gltf`/ `.glb` files; `parse_rig(gltfJson)` accepts the embedded data-URI JSON format. These return a 14-field OW array:

```text
[
 positionsXYZ, uvs, normalsXYZ, triangleIndices,
 jointIds4PerVertex, weights4PerVertex,
 nodeParents, nodeBindTranslations, nodeBindRotations, nodeBindScales,
 skinJointNodeIds, inverseBindMatrices,
 animationChannels, meshNodeId
]
```

Use `sample_palette(rig, timeInSeconds)` to produce a 1..32-element array of OpenGL column-major joint matrices, then `GRAPHICS_SHADER.set_mat4_array(program, "uBones", palette)`. Create geometry once with `GRAPHICS_MESH.create_skinned_indexed(positions,uvs,normals,indices,joints,weights)` and draw normally. The skinned vertex shader binds joints at **location 7**, weights at **location 8**, leaving locations 0/1/2 and instance locations 3–6 unchanged.

Bounded subset: one skin, one skinned mesh/primitive, one animation clip, up to 64 nodes, 32 skin joints, 32 animation channels and 256 keyframes per channel; only JOINTS_0 as UBYTE/USHORT VEC4 and WEIGHTS_0 as FLOAT VEC4, plus FLOAT node translation/rotation/scale keyframes. LINEAR interpolation and STEP are supported; quaternion LINEAR uses spherical interpolation with shortest-path normalization. Unknown required extensions, paths outside the working directory, invalid weights, unsupported interpolation and missing/out-of-range joint references are rejected.

This is **real vertex skinning** in OpenGL 3.3, but not complete glTF 2.0 animation conformance: CUBICSPLINE interpolation, multiple animation clips/skins/meshes, GLB texture data, morphs, normalized integer weights, animation blending and physically correct skinned normal matrices for nonuniform scale are later features. The demo uses frame-based progression; a real elapsed-time scheduler is not part of this PR.

Tests include JUnit for format validation, joint palettes/STEP/SLERP sampling, headless OW/flOW loading and Mesa/Xvfb GPU readback demonstrating that an animated vertex moves while the root-bound vertices remain fixed. See [Skeletal Animation](SKELETAL-ANIMATION.md).

## 11. Phase 10 — multi-clip glTF animation libraries and smooth crossfades

The new [`animation_library`](../examples/animation_library/main.ow) example contains two named clips inside the same original binary GLB: `Wave` (LINEAR translation/quaternion rotation + STEP scale) and `Bounce` (CUBICSPLINE translation and quaternion rotation). It runs a six-second sequence of Wave, a one-second crossfade to Bounce, Bounce and a one-second crossfade back. The transition weight and sampling timelines use actual monotonic elapsed seconds, independent of frame rate.

```sh
ow deps install
ow check tests/animation_library.ow
ow check examples/animation_library/main.ow
mvn -f native-bridge/pom.xml test

# Build native SPI + OW runtime as in previous sections, then run at repo root
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/animation_library/main.ow
```

The `GRAPHICS_ANIMATION_LIBRARY` service offers `load_library(relativePath)`, `parse_library(gltfJson)`, `clip_duration(pack,index)`, `loop_time(pack,index,seconds)`, `sample_clip(pack,index,seconds)`, and `blend_clips(pack,sourceIndex,sourceTime,destIndex,destTime,weight)` where `weight` lies in 0..1.

`load_library` returns `[rig14, clips, names, durationsSeconds]`. `rig14` is the original Phase 9 rig used unchanged for `GRAPHICS_MESH.create_skinned_indexed`. A CUBICSPLINE channel has 5 fields as before, but key values are stored as three component vectors `[inTangent,value,outTangent]` rather than one vector. The sampler applies cubic Hermite with tangents multiplied by the interval duration (as required by glTF), and quaternion outputs are normalized after Hermite. Crossfades blend node-local translations/scales linearly and rotations via shortest-arc quaternion SLERP; only **then** does it compute mesh-local joint palettes.

`GRAPHICS_ANIMATION_CLOCK.now_seconds()` returns a monotonic time source; record a start time and compute `elapsed = clock.now_seconds() - start` each frame. Use `loop_time` only when cyclic playback is intended. Plain `sample_clip` still clamps to the nearest endpoint. No animation state is hidden inside the Java provider, and no timer/background task is created.

The bounded first implementation supports one skinned mesh/skin, up to 16 animation clips, 32 channels per clip, 256 keys per channel, 32 joints, 64 nodes. It does not provide full glTF multi-skin scene animation, animation blend trees, animation events, root motion extraction, CUBICSPLINE tangents across clip loop boundaries, or texture/morph pipelines.

The CI checks the original Phase 1–9 paths, JUnit animation parser/cubic/crossfade tests, OW/flOW CPU integration, plus actual OpenGL/Mesa pixel readbacks comparing Wave, Bounce and a GPU-rendered intermediate blended pose.

See [Animation Library](ANIMATION-LIBRARY.md).

# OW Graphics — architecture

## Ownership and boundaries

The project is a **modular monorepo**, not a set of copy-pasted framework files.

- `dependencies.yml` pins **one external OW source library**: flOW v2. `ow deps install` resolves it to `.ow/deps/flow`; `@flow/src/App.ow` is a real OW dependency import.
- `src/graphics/*/api` contains public module signatures extending flOW's `NATIVE_LIFECYCLE`.
- Each domain's `api/` defines its public contract. Implementation folders (`infra/`, `opengl/`, `headless/`) are selected only by the composition root; scenes import public renderer contracts instead of native code.
- `scene/infra/MakeTriangleScene.ow` and `MakeMeshScene.ow` are **native OW functors**, parameterized by the public renderer contract. OpenGL can be swapped out without changing the scene.
- `native-bridge/` is a separate JVM adapter implementing OW's `RuntimeExtension` SPI, using LWJGL to call **real GLFW/OpenGL native libraries**. OW code never manipulates native pointers.
- `tests/headless_flow.ow` uses an actual headless renderer module. It boots with flOW, including dependency ordering and typed service retrieval, without GLFW or a GPU.

## Dependency graph

```text
Scene (MakeMeshScene functor) --> Renderer --> Window (GLFW)
Shader -------------------------------^
Mesh ---------------------------------^
Input --------------------------------^
```

Arrows mean "depends on". The window must be registered as a provider before dependent modules can start; flOW computes that order even when modules are registered in reverse.

Shader, Mesh and Texture modules each require the Window service; all GPU handles are opaque and validated within the runtime adapter. The headless counterparts have no window dependency. Only one renderer with service name `renderer` should be registered per application. flOW blocks stopping a provider while dependents are active.

## Phase 6 module boundaries

The native `ImageAssetLoader` decodes local PNG/JPEG images into bottom-up RGBA8 arrays, then `OpenGlResources` uploads and owns their OpenGL texture handles. A maximum 8 MiB compressed file and 2048×2048 decoded image are enforced before allocation, and the working-directory sandbox prevents path traversal and symlink escape.

`MakeSceneGraph(Transforms)` is a typed **pure OW functor**. It resolves parent-first local matrices into world matrices with at most 1024 nodes, and rejects non-root backward/cyclic references. The scene graph stores no mesh, shader or texture handles: those remain exclusively application-owned resources. `MakeMultiScene(Renderer)` provides one frame clear and many independent mesh draws so materials can be bound between objects.

The `StudioMaterials` library is a pure OW service returning per-object RGBA tint and ambient/diffuse/specular/shininess parameters. The application passes these values to GLSL; OW Graphics deliberately does not claim MTL or full PBR material support.

## Phase 7: embedded glTF, instancing and keyframe movement

A CPU-only `GltfModelParser` uses bounded JSON and base64 buffers to decode a documented **static glTF 2.0 subset** into ordinary OW arrays and metallic/roughness factors. This is not an implementation of GLB, external URI loading, glTF node scenes, skinning or animation channels. The `GRAPHICS_GLTF_MODEL` source module depends on the runtime SPI but not on the GPU.

`MakeKeyframeAnimation(Transforms)` is a pure-OW flOW functor returning linearly interpolated xyz positions at caller-supplied time. It is independent from glTF import and does not parse glTF animation tracks.

`GRAPHICS_PBR_MATERIAL` returns color and metallic/roughness factors from pure OW. The demo GLSL uses a GGX/Schlick/Smith microfacet approximation with one direct light and simple tone mapping; no IBL environment or complete glTF PBR conformance.

`GRAPHICS_MESH.draw_instanced(handle, modelMatrices)` validates 1–512 column-major transforms then uploads a dynamic GPU mat4 buffer for **one actual glDrawElementsInstanced call**. Each mesh owns its reusable instance VBO, deleted on mesh destruction or context teardown. The shader must declare a `mat4` at vertex attribute location 3 (occupies 3–6); older shaders and single-object draws are unchanged.

## Phase 8: static glTF/GLB scene decoder

The CPU-only `GltfSceneParser` shares the bounded accessor and material parser from Phase 7. It reads an embedded-buffer JSON glTF or **binary GLB 2.0** (one JSON chunk, one BIN chunk), and extracts up to 64 mesh primitives across 64 meshes.

The decoder visits selected scene roots depth-first and returns **parent-first ordered** arrays of local 4x4 matrices and mesh-primitive references. It honors glTF node `matrix` or translation/rotation-quaternion/scale (TRS), rejects cycles or shared children, and limits source scenes to 1024 nodes. The existing `MakeSceneGraph(Transforms)` functor composes those matrices entirely in OW. All GPU upload/draw/destruction remains in the previously implemented `GRAPHICS_MESH`, `GRAPHICS_SHADER`, `GRAPHICS_TEXTURE` and renderer contracts.

No skeletons, skins, glTF animation channels, morph targets, KHR extensions, external buffer URLs or auto-generated shader material maps are claimed.

## Phase 9: glTF animation and GPU skinning

`GltfSkinParser` decodes one bounded GLB/glTF animated rig with one skin, one mesh/primitive and one animation clip. It reads FLOAT TRS keyframes (LINEAR or STEP), performs quaternion SLERP for rotations, and computes `inverse(meshWorld) * jointWorld * inverseBindMatrix` CPU-side for each of up to 32 joints. Raw rig and palette data cross the OW extension boundary as arrays; there is no global native pose registry or unmanaged rig handle. `GRAPHICS_GLTF_SKIN` is the typed flOW adapter.

`GRAPHICS_MESH.create_skinned_indexed` validates four joint influences/weights per vertex, then uploads an interleaved 16-float vertex record (xyz/UV/normal/joints/weights). GLSL receives joint identifiers at vertex attribute **location 7** and weights at **location 8**; locations 3–6 remain reserved for optional instancing mat4. These attributes do not change the old 2D/3D geometry layouts.

`GRAPHICS_SHADER.set_mat4_array(handle,name,matrices)` uploads a single contiguous palette with `glUniformMatrix4fv`, capped at 32 matrices. The OW application controls time, palette uploads and draw lifetime. Mesh, shader and window handle cleanup uses the existing OpenGL resource lifecycle.

## Phase 10: multi-clip animation libraries and elapsed-time playback

The `GltfSkinParser` keeps the Phase 9 **14-field rig** contract intact while decoding up to 16 animation clips from the same glTF 2.0 asset. `GRAPHICS_ANIMATION_LIBRARY` returns a four-field array: `[rig14, clips, names, durations]`. Channel records retain their existing 5-field layout and support LINEAR, STEP and CUBICSPLINE samples. Cubic channels store `[inTangent, value, outTangent]` triples per key, preserving raw derivative magnitudes. Hermite interpolation multiplies tangents by the keyframe time interval, and quaternion outputs are normalized.

`blend_clips` interpolates **per-node translation/scale vectors and rotation quaternions** before computing the skin palette; it does not interpolate 4×4 joint matrices or GPU vertex data. Nodes missing channels use their bind-pose TRS. The same GPU skinned mesh and shader handles are reused during playback.

`GRAPHICS_ANIMATION_CLOCK` exposes an independent monotonic timestamp via `System.nanoTime`; the OW caller subtracts an initial timestamp and uses clip-duration wrapping, not hard-coded frame increments. This clock does not require GLFW or own any OpenGL context.

The new modules are additive: existing `GRAPHICS_GLTF_SKIN.load_rig`, `sample_palette` and `GRAPHICS_MESH.create_skinned_indexed` continue to work unchanged.

## Phase 11: deterministic OW animation controller and bounded CPU services

`MakeAnimationStateMachine(GltfAnimationLibrary)` is a **pure OW/flOW functor**. State is the explicitly owned six-field array `[currentClip,currentSeconds,sourceClip,sourceSeconds,fadeSeconds,fadeDuration]`. The `tick` method receives a monotonic delta and requested target, and `sample` uses the Phase 10 library's local-TRS crossfade. Mid-transition requests are deferred until the active fade finishes. The module never mutates the native JVM provider or opens a window.

`GRAPHICS_ANIMATION_LAYER` implements selective **node-masked additive skeletal animation**: sampled overlay TRS minus an overlay reference pose, with translation differences, scale ratios and quaternion rotation deltas applied to the base pose. The final joint palette is computed *after* layering, preserving the Phase 9 GPU format and vertex shader.

`GRAPHICS_ANIMATION_TIMELINE` is a CPU-only, **polling-based** service for authored marker events and optional selected-node local root-motion deltas. Events are **not** standardized glTF extras and do not automatically invoke callbacks or mutate the game state. Root-motion extraction returns one node's local `xyz` displacement including loop crossings; the game must decide how to apply it to its character transform. Methods cap query size/cycles and validate node/clip indices.

## Lifecycle

`App.boot()` registers all modules, checks contracts and orders startup. `App.stop()` tears down in reverse order. Window creation is explicitly deferred until after boot; this keeps contract checking and non-GPU tests safe. OpenGL resources are released in `graphics.window.close`; opening a new window rebuilds them.

The extension holds a scoped resource registry per interpreter and context, with separate maps for shader programs, vertex/index buffers and textures. The math, camera, orbit controller, cube geometry, material library, scene graph and lighting preset modules run entirely in OW and are independent of the GPU. A new per-vertex normal layout at attribute location 2 enables directional diffuse lighting. Destroyed/stale resource handles are rejected, and remaining GPU resources are released before the window is destroyed. The extension has **one window per OW interpreter**, enforces graphics operations on the creation thread, validates operation names and argument types, and refuses unsupported Vulkan calls. It does not pretend Vulkan is implemented.

## Roadmap

1. [x] Pin flOW and its lockfile to an immutable commit
2. [x] Typed window / renderer / input / scene interfaces and native OW functors
3. [x] GPU-free flOW lifecycle, contract and resource smoke tests
4. [x] JVM native extension with GLFW and an OpenGL 3.3 triangle
5. [x] Custom OW GLSL shaders, vec4 uniforms and non-indexed triangle meshes
5a. [x] Indexed geometry with UV attributes, RGBA8 textures, sampler and mat4 uniforms
6. [x] Mesa/Xvfb software OpenGL framebuffer and end-to-end OW integration tests (including textured quad)
6a. [x] Perspective, look-at, cube geometry, indexed normals, configurable depth testing and lighting
7. [ ] Vulkan backend (surface, swapchain, synchronization)
7a. [x] Phase 5: native GLFW cursor and named inputs, pure OW orbit controller functor, two directional + point lights, sandboxed Wavefront OBJ import
7b. [x] Phase 6: bounded PNG/JPEG loader, typed material library, scene graph and multi-object renderer
7c. [x] Phase 7: restricted static embedded glTF, direct-light metallic/roughness, translation keyframes, GPU instancing
7d. [x] Phase 8: binary GLB, multiple static meshes/primitives and node transforms across scenes
7e. [x] Phase 9: one bounded glTF skeletal rig, animated TRS sampler and real GPU linear-blend skinning
7f. [x] Phase 10: up to 16 clips, CUBICSPLINE Hermite, local TRS crossfade, monotonic-time playback
7g. [x] Phase 11: pure OW state machine, additive skeletal layers, authored markers and local root-motion deltas
8. [ ] MTL/PBR IBL materials, glTF external assets, multi-skin scene animation, shadows and GPU culling
9. [ ] Native C ABI / compiler-level FFI after ow-lang supports a stable general-purpose interface
10. [ ] Windows/macOS physical GPU smoke tests and broader graphics-driver compatibility

`ow build` is **not** a supported way to compile the full demo to a stand-alone native executable yet: OW's LLVM backend supports only a limited static module subset and does not lower the SPI/plugin calls. Use `ow run` with the extension JAR on the JVM classpath.

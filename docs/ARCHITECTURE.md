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

## Lifecycle

`App.boot()` registers all modules, checks contracts and orders startup. `App.stop()` tears down in reverse order. Window creation is explicitly deferred until after boot; this keeps contract checking and non-GPU tests safe. OpenGL resources are released in `graphics.window.close`; opening a new window rebuilds them.

The extension holds a scoped resource registry per interpreter and context, with separate maps for shader programs, vertex/index buffers and textures. The math module runs entirely in OW and is independent of the GPU. Destroyed/stale resource handles are rejected, and remaining GPU resources are released before the window is destroyed. The extension has **one window per OW interpreter**, enforces graphics operations on the creation thread, validates operation names and argument types, and refuses unsupported Vulkan calls. It does not pretend Vulkan is implemented.

## Roadmap

1. [x] Pin flOW and its lockfile to an immutable commit
2. [x] Typed window / renderer / input / scene interfaces and native OW functors
3. [x] GPU-free flOW lifecycle, contract and resource smoke tests
4. [x] JVM native extension with GLFW and an OpenGL 3.3 triangle
5. [x] Custom OW GLSL shaders, vec4 uniforms and non-indexed triangle meshes
5a. [x] Indexed geometry with UV attributes, RGBA8 textures, sampler and mat4 uniforms
6. [x] Mesa/Xvfb software OpenGL framebuffer and end-to-end OW integration tests (including textured quad)
7. [ ] Vulkan backend (surface, swapchain, synchronization)
8. [ ] PNG/JPEG decoding, camera/projection helpers, scene graph, batch drawing
9. [ ] Native C ABI / compiler-level FFI after ow-lang supports a stable general-purpose interface
10. [ ] Windows/macOS physical GPU smoke tests and broader graphics-driver compatibility

`ow build` is **not** a supported way to compile the full demo to a stand-alone native executable yet: OW's LLVM backend supports only a limited static module subset and does not lower the SPI/plugin calls. Use `ow run` with the extension JAR on the JVM classpath.

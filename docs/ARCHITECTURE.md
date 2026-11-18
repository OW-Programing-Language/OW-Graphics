# OW Graphics — architecture

## Ownership and boundaries

The project is a **modular monorepo**, not a set of copy-pasted framework files.

- `dependencies.yml` pins **one external OW source library**: flOW v2. `ow deps install` resolves it to `.ow/deps/flow`; `@flow/src/App.ow` is a real OW dependency import.
- `src/graphics/*/api` contains public module signatures extending flOW's `NATIVE_LIFECYCLE`.
- `src/graphics/*/infra` contains implementations, not imported by consumer code. The composition root chooses implementations.
- `scene/infra/MakeTriangleScene.ow` is a **native OW functor**, parameterized by the public renderer contract. OpenGL can be swapped out without changing the scene.
- `native-bridge/` is a separate JVM adapter implementing OW's `RuntimeExtension` SPI, using LWJGL to call **real GLFW/OpenGL native libraries**. OW code never manipulates native pointers.
- `tests/headless_flow.ow` uses an actual headless renderer module. It boots with flOW, including dependency ordering and typed service retrieval, without GLFW or a GPU.

## Dependency graph

```text
          [Window: GLFW] <-- [Input: GLFW]
                 ^
                 |
          [Renderer: OpenGL] <-- [Scene: functor(Renderer)]
```

The headless module has no window dependency. Only one renderer with service name `renderer` should be registered per application. flOW blocks stopping a provider while dependents are active.

## Lifecycle

`App.boot()` registers all modules, checks contracts and orders startup. `App.stop()` tears down in reverse order. Window creation is explicitly deferred until after boot; this keeps contract checking and non-GPU tests safe. OpenGL resources are released in `graphics.window.close`; opening a new window rebuilds them.

The extension has **one window per OW interpreter**, enforces graphics operations on the creation thread, validates operation names and argument types, and refuses unsupported Vulkan calls. It does not pretend Vulkan is implemented.

## Roadmap

1. [x] External flOW dependency pinned to a commit
2. [x] Typed window / renderer / input / scene interfaces and functor
3. [x] GPU-free headless contract + lifecycle smoke test
4. [x] JVM native extension with GLFW and OpenGL 3.3 triangle
5. [ ] Vulkan backend (separate adapter, surface/swapchain, synchronization)
6. [ ] Textures, shaders, pipelines, camera / matrices, scene graph, batch drawing
7. [ ] Native C ABI / compiler-level FFI when ow-lang supports a stable general-purpose FFI
8. [x] Dependency lockfile pinned to the resolved flOW Git commit
9. [ ] Linux/Windows/macOS GPU smoke tests (CI remains headless)

`ow build` is **not** a supported way to compile the full demo to a stand-alone native executable yet: OW's LLVM backend supports only a limited static module subset and does not lower the SPI/plugin calls. Use `ow run` with the extension JAR on the JVM classpath.

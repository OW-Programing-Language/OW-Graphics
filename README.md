# OW Graphics

**A modular graphics toolkit for the OW programming language, built with flOW.**

OW Graphics provides typed OW modules for windows, input, rendering and scene composition. The first backend targets **OpenGL 3.3** through an installable Java runtime extension using **LWJGL + native GLFW/OpenGL libraries**. Applications can supply their own GLSL shaders, meshes, vec4/sampler/mat4 uniforms, RGBA8 textures, indexed geometry, **perspective cameras**, **depth testing** and **diffuse lighting**, interactive orbit-camera input, a three-light rig and bounded Wavefront OBJ model importing. A headless renderer exercises the same typed contracts without native libraries.

> **Status: early development (v0.1 foundation).** This is not a complete graphics engine. Vulkan, production shader/texture APIs, native AOT output and cross-platform GUI tests are planned rather than claimed as complete.

## Design

```text
dependencies.yml                   # pinned flOW v2 OW source dependency
src/main.ow                        # composition root / OpenGL demo
src/graphics/
  window/{api,infra}/              # lifecycle + GLFW window
  renderer/
    api/                            # typed GRAPHICS_RENDERER signature
    opengl/                         # real OpenGL backend
    headless/                       # GPU-free contract implementation
  input/{api,infra}/               # ESC key / event handling
  scene/{api,infra}/               # typed triangle / mesh scene functors
  shader/{api,opengl,headless}/    # GLSL program lifecycle, uniform vec4
  mesh/{api,opengl,headless}/      # non-indexed and indexed triangle geometry
  texture/{api,opengl,headless}/   # RGBA8 texture ownership and bindings
  math/{api,infra}/               # GPU-independent OW mat4 + Y rotation
  camera/{api,infra}/             # perspective, look-at and typed orbit functor
  geometry/{api,infra}/           # reusable six-face cube geometry
  lighting/{api,infra}/           # daylight and studio multi-light presets
  model/{api,infra}/              # safe Wavefront OBJ importer
native-bridge/                     # Maven Java RuntimeExtension + LWJGL natives
tests/headless_flow.ow             # flOW integration without a display
tests/headless_resources.ow        # GPU-free shader/mesh contract test
examples/programmable_triangle/   # shaders + vertex array in OW
examples/textured_quad/           # indexed geometry + RGBA8 + transforms
examples/rotating_cube/           # actual 3D depth and lit textured cube
examples/model_viewer/            # local OBJ, orbit camera, 3 light sources
docs/{ARCHITECTURE,DEVELOPMENT}.md
```

Each domain owns its public `api` and private `infra`; only the root selects concrete implementations. **No flOW files are copied** into this repository: `ow deps install` fetches `@flow` from the pinned commit declared in `dependencies.yml`.

## Try the no-GPU test

```sh
ow deps install
ow check tests/headless_flow.ow
ow run tests/headless_flow.ow
ow check src/main.ow
ow run tests/headless_3d.ow
ow run tests/headless_orbit.ow
```

The test registers a scene **before** its renderer, checks flOW boot ordering, retrieves typed services, renders via a headless backend and tests protection against disabling a required service.

## Run the OpenGL demo

Build the extension against a sibling checkout of `ow-lang` and then launch the OW interpreter with the provider on the Java classpath. Complete commands (Linux/macOS and Windows): **[Development guide](docs/DEVELOPMENT.md)**.

The original GUI draws a built-in triangle. [`programmable_triangle`](examples/programmable_triangle/main.ow) compiles custom GLSL. [`textured_quad`](examples/textured_quad/main.ow) adds a two-triangle indexed quad with UVs, an RGBA8 texture and model matrix, all configured in OW. [`rotating_cube`](examples/rotating_cube/main.ow) adds a spinning, textured cube with lighting, perspective and actual depth testing. The [`model_viewer`](examples/model_viewer/main.ow) loads a local OBJ and adds left-mouse dragging, scroll zoom, arrow-key orbiting, and two directional lights plus one attenuated point light. Press **Escape** to exit.

## What is functional?

| Area | State |
| --- | --- |
| flOW v2 + `dependencies.yml` | Integrated and SHA-pinned |
| Window, rendering, input and scene OW modules | First implementation |
| OW typed signatures + native functor | Implemented |
| Headless graphics backend and smoke test | Implemented |
| GLFW + OpenGL 3.3 native bridge | Implemented, display test required |
| Vulkan backend | Planned; not yet implemented |
| Custom GLSL programs, RGB uniforms, non-indexed triangle meshes | Implemented (GUI validation pending) |
| RGBA8 textures, indexed UV meshes, column-major mat4 | First implementation; software GPU test |
| 3D look-at/perspective camera, rotation, depth, directional diffuse lighting | Implemented, Mesa software verification |
| Orbit mouse/keyboard input, zoom, 2 directional + 1 point lights | Implemented; headless and software GPU tests |
| Local Wavefront OBJ geometry import (v/vt/vn/f) | Implemented; bounded file parser and triangulation |
| PNG/JPEG and MTL materials, skeletal animation, shadows, PBR | Planned |
| Native OW AOT/FFI integration | Awaiting compiler support |
| Lockfile | Committed and SHA-pinned |

See [architecture](docs/ARCHITECTURE.md) and [development commands](docs/DEVELOPMENT.md).

## Ecosystem

- [OW language](https://github.com/OW-Programing-Language/ow-lang)
- [flOW — OW Modular Framework](https://github.com/OW-Programing-Language/flOW---OW-Modular-Framework)

OW Graphics is a separate library; it does not modify the OW compiler or require changes to flOW to express these first modules.

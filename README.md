# OW Graphics

**A modular graphics toolkit for the OW programming language, built with flOW.**

OW Graphics provides typed OW modules for windows, input, rendering and scene composition. The first backend targets **OpenGL 3.3** through an installable Java runtime extension using **LWJGL + native GLFW/OpenGL libraries**. Applications can supply their own GLSL shaders, meshes, vec4/sampler/mat4 uniforms, RGBA8 textures, indexed geometry, **perspective cameras**, **depth testing** and **diffuse lighting**, interactive orbit-camera input, a three-light rig, bounded Wavefront OBJ model importing, **PNG/JPEG texture loading**, per-object materials, a hierarchical scene graph, static **glTF 2.0** importing, direct-light **GGX metallic-roughness**, translation keyframes, GPU instancing, and **GLB/multi-mesh static scenes with parent-child transforms**. The new animation library also supports **multiple glTF clips, CUBICSPLINE Hermite interpolation, local-TRS crossfades and monotonic-time playback**. A headless renderer exercises the same typed contracts without native libraries.

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
  scene/{api,infra}/               # single/multi-scene renderer and graph functors
  shader/{api,opengl,headless}/    # GLSL program lifecycle, uniform vec4
  mesh/{api,opengl,headless}/      # non-indexed and indexed triangle geometry
  texture/{api,opengl,headless}/   # RGBA8 texture ownership and bindings
  math/{api,infra}/               # GPU-independent OW mat4 + Y rotation
  camera/{api,infra}/             # perspective, look-at and typed orbit functor
  geometry/{api,infra}/           # reusable six-face cube geometry
  lighting/{api,infra}/           # daylight and studio multi-light presets
  model/{api,infra}/              # bounded Wavefront OBJ, glTF 2.0 + GLB scenes
  material/{api,infra}/           # pure OW Phong and metallic-roughness presets
  animation/{api,infra}/          # OW keyframes, glTF skin, multi-clip library + monotonic clock
native-bridge/                     # Maven Java RuntimeExtension + LWJGL natives
tests/headless_flow.ow             # flOW integration without a display
tests/headless_resources.ow        # GPU-free shader/mesh contract test
examples/programmable_triangle/   # shaders + vertex array in OW
examples/textured_quad/           # indexed geometry + RGBA8 + transforms
examples/rotating_cube/           # actual 3D depth and lit textured cube
examples/model_viewer/            # local OBJ, orbit camera, 3 light sources
examples/material_scene/          # real PNG, 2 material instances and 2 hierarchy nodes
examples/gltf_instancing/         # glTF mesh, GGX shading, animation, GPU instancing
examples/gltf_scene/              # binary GLB, multiple meshes/materials + scene nodes
examples/gltf_skinning/           # original 2-joint animated GLB and GPU skinning
examples/animation_library/       # Wave/Bounce GLB, Hermite, crossfades, real-time playback
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
ow run tests/headless_material_scene.ow
ow run tests/headless_multiscene.ow
ow run tests/headless_pbr_animation.ow
# The GLB parser test requires the built Java SPI provider.
```

The test registers a scene **before** its renderer, checks flOW boot ordering, retrieves typed services, renders via a headless backend and tests protection against disabling a required service.

## Run the OpenGL demo

Build the extension against a sibling checkout of `ow-lang` and then launch the OW interpreter with the provider on the Java classpath. Complete commands (Linux/macOS and Windows): **[Development guide](docs/DEVELOPMENT.md)**.

The original GUI draws a built-in triangle. [`programmable_triangle`](examples/programmable_triangle/main.ow) compiles custom GLSL. [`textured_quad`](examples/textured_quad/main.ow) adds a two-triangle indexed quad with UVs, an RGBA8 texture and model matrix, all configured in OW. [`rotating_cube`](examples/rotating_cube/main.ow) adds a spinning, textured cube with lighting, perspective and actual depth testing. The [`model_viewer`](examples/model_viewer/main.ow) loads a local OBJ and adds left-mouse dragging, scroll zoom, arrow-key orbiting, and two directional lights plus one attenuated point light. The new [`material_scene`](examples/material_scene/main.ow) loads a real checkerboard PNG and renders two OBJ models in a parent-child hierarchy, with independent copper and azure Phong-style material settings. The [`gltf_instancing`](examples/gltf_instancing/main.ow) demo loads an embedded-buffer glTF model and uses two GPU draws for three animated/material-different instances with direct-light GGX metallic/roughness shading. The [`gltf_scene`](examples/gltf_scene/main.ow) example loads an actual binary GLB with three mesh primitives, glTF parent/child transforms and distinct per-primitive metallic/roughness values. The [`gltf_skinning`](examples/gltf_skinning/main.ow) example displays a real animated two-joint mesh with GPU vertex skinning. The [`animation_library`](examples/animation_library/main.ow) example plays two glTF clips with cubic Hermite movement, smooth crossfades and elapsed-time scheduling. Press **Escape** to exit.

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
| PNG/JPEG texture import (ImageIO, RGBA8) | Implemented, local-only and bounded |
| Material presets (tint, diffuse, specular, shininess) | Implemented in pure OW, per-object uniforms |
| Parent-child scene graph + multiple draws per frame | Implemented as typed flOW functors |
| Static glTF 2.0 embedded-buffer mesh + base color/metallic/roughness | First strict subset, no skinning |
| Binary GLB, multiple meshes/primitives and static scene node transforms | First bounded implementation; Mesa framebuffer verified |
| glTF skeletal skinning, LINEAR/STEP animation, quaternion SLERP | First bounded implementation; real GPU joint-palette deformation |
| Up to 16 clips, CUBICSPLINE Hermite, TRS crossfade, monotonic animation clock | Implemented as separate typed OW/flOW modules |
| GGX metallic-roughness direct-light shader | First implementation, not a full IBL PBR pipeline |
| Translation keyframes and instanced GPU mat4 draws | Implemented; software framebuffer verified |
| Multiple skins/meshes, animation blending trees, morph targets, texture maps, shadow maps, full PBR IBL | Planned |
| Native OW AOT/FFI integration | Awaiting compiler support |
| Lockfile | Committed and SHA-pinned |

See [architecture](docs/ARCHITECTURE.md), [material and scene guide](docs/MATERIALS-AND-SCENES.md), [glTF and instancing guide](docs/GLTF-AND-INSTANCING.md), [GLB scene guide](docs/GLB-SCENES.md), [skeletal animation guide](docs/SKELETAL-ANIMATION.md), [animation library guide](docs/ANIMATION-LIBRARY.md), and [development commands](docs/DEVELOPMENT.md).

## Ecosystem

- [OW language](https://github.com/OW-Programing-Language/ow-lang)
- [flOW — OW Modular Framework](https://github.com/OW-Programing-Language/flOW---OW-Modular-Framework)

OW Graphics is a separate library; it does not modify the OW compiler or require changes to flOW to express these first modules.

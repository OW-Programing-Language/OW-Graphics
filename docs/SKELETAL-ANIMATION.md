# Phase 9 — glTF skeletal animation and GPU skinning

The `GRAPHICS_GLTF_SKIN` flOW service extends OW Graphics with bounded, animated glTF 2.0 support. Unlike the Phase 8 **static scene** decoder, this module owns no scene handles: it returns a typed OW rig array and computes joint transforms on demand. A single GPU upload of the static mesh is enough to play repeated animation poses.

## Pipeline

```text
Original .glb/.gltf (GLTF2 + embedded buffer)
             |
 GltfSkinParser (CPU Java): joints, weights, inverse binds, channels
             |
             +-- rig OW array (14 fields) -------------------+
             |                                               |
             +-- GltfSkin.sample_palette(rig, seconds)       |
                     |                                     |
              CPU TRS interpolation: LINEAR/STEP, rotation SLERP
                     |
              inverse(meshWorld) * jointWorld * inverseBind
                     |                                     |
              shader.set_mat4_array("uBones", palette)       |
                     |                                     |
                     +--------> OpenGL vertex shader <------+
                                  joints loc=7 / weights loc=8
                                     |
                                skinned vertices
```

## Getting started

From the OW Graphics repository root with flOW installed and the native SPI provider compiled:

```ow
set rig: skin.load_rig("examples/gltf_skinning/assets/wave.glb")
set meshId int: mesh.create_skinned_indexed(
  rig[0], rig[1], rig[2], rig[3], rig[4], rig[5])
set bones: skin.sample_palette(rig, 0.5)
call: shader.set_mat4_array(shaderId, "uBones", bones)
call: renderer.draw_mesh(meshId)
```

See [`examples/gltf_skinning/main.ow`](../examples/gltf_skinning/main.ow) for the complete OW/flOW composition root, animation loop, GLSL skin shader, and explicit GPU cleanup. The original [`wave.glb`](../examples/gltf_skinning/assets/wave.glb) and [`wave.gltf`](../examples/gltf_skinning/assets/wave.gltf) are identical assets with two bones and three animation tracks.

The module uses **linear blend skinning (LBS)**, not CPU deformation. Vertex positions and skinning weights are uploaded to OpenGL once. Each frame only the 1..32 bone palette changes.

## Scope and limits

- **Supported:** glTF 2.0 GLB or embedded-buffer JSON, one skin/mesh TRIANGLES primitive, inverse bind mat4, 1..32 joints, up to four influences/vertex, LINEAR and STEP TRS keyframe channels, quaternion SLERP and column-major matrix composition.
- **Bounded:** up to 64 nodes, 32 channels, 256 keys/channel, 1 MiB JSON and 4 MiB binary; project-relative sandboxed file loading.
- **Not yet supported:** extra skins/meshes/animation clips, multiple animation clips or blending, CUBICSPLINE, normalized integer WEIGHTS_0, JOINTS_1/WEIGHTS_1, morph targets, texture references, automatic mesh-node attachment in a full scene, vertex skinned tangent/normal inverse transpose for nonuniform scaling, Vulkan.
- **Technical:** shaders must declare `layout(location=7) in vec4 aJoints`, `layout(location=8) in vec4 aWeights` and `uniform mat4 uBones[32]`. Joint IDs are integer-valued floats and cast to int in GLSL. Locations 3–6 are reserved for GPU instance matrices.
- **Sampling:** input time is seconds; poses clamp to the first or last key. A future clock can implement proper delta-time playback, repeats and crossfades.

CI tests actual Java CPU parser and animation math, real OW/flOW rig loading without a GPU, and **real Mesa OpenGL framebuffer pixels** to prove bone palette motion changes geometry on screen. The previous Phase 1–8 GPU/CPU suites remain active.

## Phase 10 extension

For multiple clips, glTF CUBICSPLINE interpolation and elapsed-time crossfades, use the separate `GRAPHICS_ANIMATION_LIBRARY` and `GRAPHICS_ANIMATION_CLOCK` modules. These are additive and preserve the original Phase 9 14-field rig API. See [Animation Library](ANIMATION-LIBRARY.md) for supported formats and the complete two-clip example.

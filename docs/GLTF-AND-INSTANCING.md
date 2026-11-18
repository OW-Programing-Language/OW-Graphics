# Phase 7 — glTF 2.0 static subset, direct-light PBR, instanced rendering

OW Graphics remains modular under flOW. This phase is an **incremental, bounded renderer** — not a complete implementation of the glTF 2.0 specification, a full production PBR pipeline, or GPU skeletal animation.

## Module boundaries

```text
GltfModel (CPU Java, no GL) -------------------> typed OW xyz/uv/normal/index arrays
                                                       |
KeyframeAnimation(Transforms) (pure OW) -> model matrices -----> Mesh.draw_instanced
PbrMaterial (pure OW) -----------------------> GLSL uniforms ---> OpenGL 3.3
                                                       |
                        OpenGlResources: one persistent VBO per mesh for mat4 instances
```

- The `GRAPHICS_GLTF_MODEL` contract has `parse_gltf(text)` and `load_gltf(relativePath)`. The results can be passed directly to `GRAPHICS_MESH.create_lit_indexed`.
- The `GRAPHICS_PBR_MATERIAL` contract yields `base_color(name)` and `factors(name)` for `gold`, `ceramic` and `plastic`.
- The `GRAPHICS_KEYFRAME_ANIMATION` pure OW functor is constructed as `MakeKeyframeAnimation(Graphics.Transforms)`. It interpolates independently supplied translations over time. glTF animation clips are **not** decoded or played.
- `GRAPHICS_MESH.draw_instanced(mesh, matrices)` uploads an instance matrix buffer for one draw of 1–512 copies. Instanced shaders require `layout(location=3) in mat4 aInstance;`.

## Try the example

Run `examples/gltf_instancing/main.ow` with the compiled OW runtime + Java SPI/LWJGL provider from the repository root, as described in [Development](DEVELOPMENT.md). The original sample [quad.gltf](../examples/gltf_instancing/assets/quad.gltf) uses one data-URI buffer with positions, normals, UVs, indices and a metallic/roughness material.

The demo draws two gold instances together, then one independent glTF-material instance. The middle object moves vertically using linear OW keyframes. A conventional PNG texture is loaded using the Phase 6 texture loader, not through embedded glTF images.

## Supported and unsupported features

| Feature | Supported |
| --- | --- |
| glTF 2.0 JSON `.gltf`, one mesh + TRIANGLES primitive, embedded `application/octet-stream` buffer | Yes |
| POSITION / NORMAL / TEXCOORD_0, FLOAT vertex attributes, optional generated normals/UV defaults | Yes |
| Unsigned byte/short/int index buffers, optional auto-generated triangle indices | Yes |
| baseColorFactor, metallicFactor, roughnessFactor | Yes |
| External URLs, additional binary buffers, GLB, sparse/normalized accessors | No |
| Multiple meshes/primitives, skin joints, node hierarchy, glTF animations and morph targets | No |
| Full glTF PBR textures, IBL/skyboxes, transmission, shadow mapping and Vulkan | No |

The native parser bounds JSON size to 1 MiB, decoded buffer bytes to 4 MiB and vertex count to 100,000. External resource URIs and file path escape are rejected. The OpenGL instance buffer is reused across frames, avoiding a new VBO allocation per draw call; normal and texture uploads follow existing Phase 4–6 contracts.

Mesa CI checks real framebuffer colors for instanced geometry, OW/flOW source verification, no-GPU glTF parsing and the complete glTF/PBR scene smoke test. Actual hardware driver portability is still an explicit follow-up.

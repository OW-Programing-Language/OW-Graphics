# OW Graphics — Phase 8 GLB and static multi-mesh scenes

The `GRAPHICS_GLTF_SCENE` API adds binary `.glb` files, multiple meshes and primitives, selected glTF scenes and parent/child node transforms. It **extends** the existing strict Phase 7 `GRAPHICS_GLTF_MODEL` entry point rather than changing its return structure.

## Architecture

```text
.gltf (embedded data URI) ----+
                              +--> GltfSceneParser (CPU-only Java, bounded)
.glb (JSON + BIN chunks) -----+              |
                                             v
                  [primitives, parents, local matrices, node primitive IDs]
                        |           |                   |
                        v           v                   |
                 Mesh.create    MakeSceneGraph          |
                   _lit_indexed  (Transforms)           |
                        |           |                   |
                        +---------->+<------------------+
                                     |
                      application binds mesh/material per primitive
                                     |
                  MakeMultiScene(Renderer) --> OpenGL 3.3
```

The native bridge reads and validates geometry, then returns ordinary OW arrays; it never owns an external scene-graph object or invents GPU handles. The pure OW `MakeSceneGraph(Graphics.Transforms)` functor computes model matrices.

## Try the original scene

The source-controlled [binary GLB](../examples/gltf_scene/assets/multi_scene.glb) and [JSON glTF](../examples/gltf_scene/assets/multi_scene.gltf) represent the same scene: two meshes, three primitives and four nodes. A group parent has two child meshes in a deliberately non-ascending node order to test stable parent-first traversal. One additional root shares the blue mesh. Each primitive retains its distinct `baseColorFactor`, metallic and roughness factors.

Run [`examples/gltf_scene/main.ow`](../examples/gltf_scene/main.ow) from the OW-Graphics repository root with the compiled native provider as explained in [Development](DEVELOPMENT.md). The example loads the GLB, uploads the three geometry buffers, resolves all node transforms, and draws the corresponding mesh with its material, reusing shared geometry handles.

### Typed OW service

```ow
set asset: importer.load_scene("examples/gltf_scene/assets/multi_scene.glb")
set primitiveMeshes: asset[0]
set parentIndices: asset[1]
set localMatrices: asset[2]
set nodePrimitiveIds: asset[3]
set worldMatrices: graph.resolve(parentIndices, localMatrices)
```

Nodes are ordered **parent-first**, which is the contract of `GRAPHICS_SCENE_GRAPH`. Inactive scene nodes are not rendered. Empty group nodes return an empty list of primitives. Mesh nodes may list multiple primitive IDs, and multiple nodes can reference the same GPU mesh. The original Phase 7 single primitive importer API still works.

### Safety and feature boundaries

This first scene reader requires glTF 2.0; it only supports one embedded binary buffer or one GLB BIN chunk. It validates container magic/length/chunks, no unrecognized required extensions, project-relative paths, finite accessor values, valid indices/strides, valid node references and acyclic parent-child relationships, and sensible bounded sizes (JSON 1 MiB, BIN 4 MiB, up to 64 meshes, 64 primitives and 1024 nodes).

Implemented transforms include direct 4×4 matrices or translation/quaternion rotation/scale, converted into column-major model matrices. `MakeSceneGraph` composes them with the selected scene parent graph. Nonuniform scaling currently needs a user-provided inverse-transpose normal matrix in GLSL for physically correct lighting; this example uses only translations.

**Not yet supported:** external glTF assets and URLs, multiple GLB BIN chunks, GLB images, GLB node animation channels, skeleton/skin/morph data, non-TRIANGLES primitive modes, complex scene editing, full glTF 2.0 conformance or Vulkan. The next milestone can address glTF animation and skeletal skinning as an explicit, separately testable phase.

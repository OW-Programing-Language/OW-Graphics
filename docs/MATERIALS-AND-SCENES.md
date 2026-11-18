# OW Graphics — materials, images and scene hierarchy

Phase 6 adds three separable layers to the flOW-based OW Graphics architecture:

```text
Local PNG/JPEG -> Java ImageIO -> RGBA8 -> OpenGL texture resource
                                             |
StudioMaterials (pure OW) -> GLSL uniforms --+---> Scene drawing
                                             |
MakeSceneGraph(Transforms) -> model matrices -+
                                             |
Wavefront OBJ -> indexed mesh ---------------+
```

## Example

From the OW Graphics repository root:

```bash
ow deps install
ow run tests/headless_material_scene.ow
ow run tests/headless_multiscene.ow
ow check examples/material_scene/main.ow

# After native-bridge is built against the sibling ow-lang checkout:
java -cp "../ow-lang/target/OwIDE.jar:native-bridge/target/ow-graphics-native-0.1.0-SNAPSHOT.jar:native-bridge/target/lib/*" dev.oreo.Main run examples/material_scene/main.ow
```

The [material scene](../examples/material_scene/main.ow) shows two pyramids. The left uses a copper material; its child uses azure. A real [checker PNG](../examples/material_scene/assets/checker.png) is uploaded once and reused. The parent rotates; the child inherits its transform.

## Typed API

- `GRAPHICS_TEXTURE.load_image(path)` decodes local PNG or JPEG with no external image libraries; returns an opaque OpenGL texture handle. No arbitrary filesystem paths or HTTP URLs.
- `GRAPHICS_MATERIAL_LIBRARY.tint(name)` and `.properties(name)` return shader-ready values. Texture assets and materials are independent.
- `GRAPHICS_SCENE_GRAPH.resolve(parents, locals)` computes `world[child] = world[parent] * local[child]`. Input must already be in parent-first order, and invalid references fail explicitly.
- `GRAPHICS_MULTI_SCENE.begin_frame(r,g,b)` clears once; `.draw_object(mesh)` draws as many objects as needed. The application binds the per-object model matrix, material uniforms and texture, then draws.

### Design restrictions

The scene graph is intentionally a **transform graph**, not a heavyweight entity/component system or automatic renderer: it does not own GPU handles, cameras or material instances. The materials are per-object Phong-style presets, not MTL/PBR. Each texture decode is bounded (8 MiB input, maximum 2048×2048 pixels) and transformed to bottom-up RGBA8; JPEG loses its alpha because that format has none.

A single OpenGL 3.3 window/context and one owner thread are supported, with Mesa/Xvfb CI tests. Larger asset types, scene serialization, instancing and full game-engine asset pipelines are future milestones.

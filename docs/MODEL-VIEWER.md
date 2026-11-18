# OW Graphics — Phase 5 model viewer

The sample [`examples/model_viewer/main.ow`](../examples/model_viewer/main.ow) is an interactive OW program with an OpenGL 3.3 backend. It reads a local Wavefront OBJ (including UVs and normals where present), creates a GPU mesh, and renders it with three lighting sources. The application chooses concrete flOW implementations at the composition root; independent camera and light modules are pure OW code.

**Controls:** left-mouse drag or arrow keys orbit the model, mouse wheel zooms, Escape closes.

## flOW services

- `GRAPHICS_MODEL` — `parse_obj(source)`, `load_obj(relativePath)`; source parsed by the JVM provider but independent of the GL window.
- `GRAPHICS_ORBIT_CONTROLLER` — instantiated as `MakeOrbitController(Graphics.Camera)`, accepting the public `GRAPHICS_CAMERA` contract.
- `GRAPHICS_INPUT` — named keyboard input, mouse-button state, cursor deltas, accumulated scroll. The first cursor sample is `[0,0]`.
- `GRAPHICS_LIGHT_RIG` — independent key/fill directional vectors, point-light location, intensity values and ambient coefficients.
- Existing `GRAPHICS_MESH`, `GRAPHICS_SHADER`, `GRAPHICS_RENDERER`, `GRAPHICS_TEXTURE` services — unchanged ownership of GPU handles.

The `examples/model_viewer/assets/pyramid.obj` file is original, small, editable and intentionally contains a quad base to exercise triangulation.

## Known limits

OBJ support is geometry-only. It does **not** interpret `mtllib`, `usemtl`, texture images, smoothing groups, arbitrary nonconvex polygons, multiple submeshes, or animation. File paths are constrained to the current working directory. The viewer has one render thread and single window; advanced camera input mapping and global scene graph are future work.

See [Development](DEVELOPMENT.md) for commands, supported path rules, testing and backend requirements.

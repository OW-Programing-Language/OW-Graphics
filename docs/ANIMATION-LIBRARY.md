# OW Graphics — Phase 10 animation library and transitions

This module adds **real multi-clip glTF 2.0 playback** to the bounded skeletal skinning system introduced in Phase 9. It does not introduce unmanaged GPU scene objects or replace the public `GRAPHICS_GLTF_SKIN` API.

## Original example

The checked-in original [`wave_bounce.glb`](../examples/animation_library/assets/wave_bounce.glb) and its equivalent [`wave_bounce.gltf`](../examples/animation_library/assets/wave_bounce.gltf) contain a two-bone skinned quad and two named clips.

- **Wave:** LINEAR translation, LINEAR quaternion rotation and STEP scaling
- **Bounce:** CUBICSPLINE translation (nonzero outgoing tangent to demonstrate true Hermite curvature) and CUBICSPLINE quaternion rotation

The [`main.ow`](../examples/animation_library/main.ow) demo plays Wave, transitions into Bounce, plays Bounce, and crossfades back. The wall-clock rate does not depend on FPS; rendering remains a normal OW/flOW→GLSL→OpenGL call. Use Escape to exit.

## Public typed API

```ow
set pack: library.load_library("examples/animation_library/assets/wave_bounce.glb")
set rig: pack[0]
set names: pack[2]
set duration: library.clip_duration(pack, 0)
set pose: library.sample_clip(pack, 0, 0.5)
set mixed: library.blend_clips(pack, 0, 0.5, 1, 0.5, 0.5)
call: shader.set_mat4_array(programId, "uBones", mixed)

set started: clock.now_seconds()
set elapsed: clock.now_seconds() - started
set secondsInClip: library.loop_time(pack, 1, elapsed)
```

`GRAPHICS_ANIMATION_LIBRARY` provides `load_library`, `parse_library`, `clip_duration`, `loop_time`, `sample_clip`, and `blend_clips`. `GRAPHICS_ANIMATION_CLOCK` provides a monotonic `now_seconds()` backed by JVM `System.nanoTime` and **does not require an OpenGL window**.

The returned `pack` is a normal OW array:

```text
[
  rig14,                # legacy geometry/skin/bind/first-animation data
  allClips,             # array of channel arrays
  clipNames,            # names in the original glTF order
  durationSeconds       # maximum keyframe time for each clip
]
```

The imported clip channels retain the 5-field `[targetNode,path,interpolation,times,values]` format. LINEAR and STEP `values` contain a single VEC3 or quaternion VEC4 per keyframe. CUBICSPLINE `values` contain `[inTangent, value, outTangent]` per keyframe; tangents are derivatives in units per second and are scaled by the keyframe interval during sampling. Only quaternion **values**, not tangents, are normalized when decoding. The output of CUBICSPLINE rotation interpolation is normalized again.

Crossfades happen at the **local TRS node level**: translation/scale are linearly blended; rotation uses shortest-arc quaternion SLERP. The engine then builds node world matrices and correct mesh-local skin matrices. It never lerps 4×4 transforms, which would shear rotations.

Use `sample_clip` for clamped single-clip playback and `loop_time` to wrap the timeline for cyclic playback. At an exact STEP keyframe timestamp, the sampler uses the new value. `blend_clips` accepts a weight between 0 (source pose) and 1 (destination pose). Time inputs are elapsed seconds, not frames.

## Boundaries

This milestone accepts 1..16 clips on **one skin and one skinned mesh/primitive**, with 1..32 channels/clip, 1..256 keys/channel, 1..32 joints and 1..64 nodes, using the same bounded GLB/embedded-buffer reader as Phase 9. Still unsupported: multiple skinned meshes/skins per document, animation blending trees/events, per-node constraints/IK, morph targets, glTF external resources, multi-clip crossfade root-motion logic, normalized integer skin weights, and Vulkan.

CPU JUnit tests confirm Hermite values differ from linear interpolation, quaternion normalization, clip bounds, legacy API compatibility and correct local TRS blending. Mesa/Xvfb tests render distinct bone poses and read back pixels to verify intermediate crossfade movement. The OW/flOW smoke example checks the native modules and real GPU resource lifecycle.

## Phase 11 controller

The optional typed `GRAPHICS_ANIMATION_STATE_MACHINE`, `GRAPHICS_ANIMATION_LAYER` and `GRAPHICS_ANIMATION_TIMELINE` modules build on this library. They implement deterministic OW transitions, an additive clip overlay on selected joints, application-authored markers and loop-aware selected-node local translation extraction. They do **not** alter the existing library's return format. See [Animation Controller](ANIMATION-CONTROLLER.md).

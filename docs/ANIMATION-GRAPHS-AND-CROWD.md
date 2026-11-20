# OW Graphics — Phase 12 transition graphs and multi-character animation

Phase 12 builds on the existing OW/flOW animation modules, glTF 2.0 skeletons, GPU skinning and animation controller. Its goal is to support **real reusable character logic** without creating Java-side mutable actor registries.

## Modules

```text
GRAPHICS_ANIMATION_LIBRARY + GLTF skin/clip data (Phase 10)
             |
             v
MakeAnimationStateMachine(Library) (Phase 11, pure OW)
             |
             v
MakeAnimationGraph(StateMachine)    trigger -> rule -> desired clip + fade
             |                      (first match, wildcard -1)
             v
MakeActorCrowd(Graph)               N actor state arrays (max 32)
       |                             |               |
       v                             v               v
local root_delta (Timeline)      loop_time (Library) translation matrix (Transforms)
       |                             |               |
       +-> world translation         +-> palette anchor offset
                  \                 /
                   +-> actor.model + actor.palette
                                 |
                   shader.set_mat4(uModel) + set_mat4_array(uBones)
                                 |
                   GPU draws shared mesh handle once for each actor
```

Only the functors and per-actor bookkeeping run in pure OW; the existing bounded glTF clip sampling and root-delta functions are in the CPU-only native SPI. No new backend, plugin registry, animation thread or GPU resource owner is introduced.

## Transition graph

A `GRAPHICS_ANIMATION_GRAPH` uses an **ordered rule array** whose entries contain:

```text
[fromClipIndex, toClipIndex, eventTrigger, crossfadeSeconds]
```

A rule matches the active clip and supplied trigger. The first matching rule wins. `fromClipIndex = -1` accepts any clip. An unmatched trigger keeps the current clip; during an ongoing fade, requests are deferred just as in Phase 11. There is no expression language or implicit global condition system.

```ow
set machine GRAPHICS_ANIMATION_STATE_MACHINE: MakeAnimationStateMachine(Graphics.GltfAnimationLibrary)
set graph GRAPHICS_ANIMATION_GRAPH: MakeAnimationGraph(machine)
set rules: [[0, 2, "walk", 0.5], [2, 0, "stop", 0.25], [0 - 1, 1, "bounce", 0.1]]
set state: graph.initial(library, 0)
set state: graph.advance(library, state, rules, "walk", 0.016)
set pose: graph.sample(library, state)
```

## Actor crowd

`GRAPHICS_ANIMATION_CROWD` is created with `MakeActorCrowd(graph)` and requires the library, timeline and transform modules used elsewhere in the project. A single actor is an explicit four-field OW array:

```text
[
  phase11ControllerState,      # 6 fields for active clip, fade and times
  worldXYZ,                    # accumulated translation, model matrix derived from it
  rootNodeID,                  # must be an ancestor of ALL skin joints
  paletteRootOffsetXYZ         # source local translation subtracted from palette
]
```

```ow
set crowd GRAPHICS_ANIMATION_CROWD: MakeActorCrowd(graph)
set actorA: crowd.spawn(library, 0, [0.0 - 0.7, 0.0, 0.0], 0)
set actorB: crowd.spawn(library, 2, [0.0, 0.0, 0.0], 0)
set batch: [actorA, actorB]
set batch: crowd.advance_all(library, batch, rules, ["walk", ""], 0.016)
set modelB: crowd.model(batch[1])
set bonesB: crowd.palette(library, batch[1])
call: shader.set_mat4(shaderId, "uModel", modelB)
call: shader.set_mat4_array(shaderId, "uBones", bonesB)
call: renderer.draw_mesh(meshId)
```

Each actor has independent animation time, position and transition state while sharing the **same rig, skinned mesh buffer and GLSL shader**. The application renders one draw per actor, binding a new model matrix and bone palette each time. This is not one GL instanced draw and is not multi-rig animation import.

### Avoiding double-applied root motion

The prior timeline's `root_delta` extracts one node's **local XYZ** displacement, including clip wraps. Adding it directly to a world model while also sampling the animated root bone would move the character twice. The Phase 12 actor controller handles that by accumulating deltas into worldXYZ during stable playback, and subtracting the matching local root translation offset from all matrices in its palette. During crossfade it **freezes world displacement** but blends the source/destination root offsets before anchoring. This is suitable only when the root is a common ancestor of all skin joints and its motion is translation-only; rotating roots, moving mesh nodes, nonuniform ancestor transforms and motion matching require a different transform-space solution.

## Example and validation

[`examples/animation_crowd/main.ow`](../examples/animation_crowd/main.ow) renders **three independent animated actors** with separate triggers and state transitions using the Phase 11 original [`three_clips.glb`](../examples/animation_controller/assets/three_clips.glb). The demo uses a *single GPU mesh* and per-actor uniforms.

[`tests/animation_crowd.ow`](../tests/animation_crowd.ow) checks per-actor isolation, first-match transitions, deferred fades, root-motion accumulation and anchored palette consistency under real OW/flOW evaluation. The end-to-end OW GPU smoke covers multiple draws and teardown. `AnimationCrowdGpuSmoke` reads actual Mesa framebuffer pixels to prove that three colored actors occupy distinct positions and changing one position does not relocate the others.

Limits: max **32 actors per batch**, **128 transition rules**, one skinned mesh/rig shared by the batch, up to 32 joints per actor and 16 clips per rig (original Phase 10 limits). No GPU-instanced independent bone-palette indexing, multi-skeleton asset import, general root rotation compensation, collision detection, IK, physics integration, culling, skeleton retargeting or asynchronous animation jobs.

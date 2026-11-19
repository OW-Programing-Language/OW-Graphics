# OW Graphics — Phase 11 animation controller

The Phase 11 controller adds useful game-layer animation behavior while preserving Phase 10's glTF clips, quaternion interpolation, GPU palette layout, and the OW/flOW architecture.

## Module composition

```text
MonotonicClock (existing Phase 10) --------> deltaSeconds
                                              |
GltfAnimationLibrary (existing Phase 10) ----+----> MakeAnimationStateMachine
  (clips, durations, sample, crossfade)               pure OW: initial / tick / sample
                                              |                 |
                                              +------> additive layer (CPU) --> GPU joint palette
                                              |
                                              +------> timeline marker polling + root local deltas
```

The animation controller itself is a **pure OW functor**, constructed as `MakeAnimationStateMachine(Graphics.GltfAnimationLibrary)` and registered as a typed flOW service. It owns no native GPU handles, mutable singletons or background timers. The six-element state `[activeClip, activeSeconds,sourceClip,sourceSeconds,fadeElapsed,fadeDuration]` is updated explicitly every frame.

### State machine

```ow
set state: machine.initial(library, 0)
# Each frame: pass elapsed time from the monotonic clock, target clip, fade seconds
set state: machine.tick(library, state, 2, 0.016, 0.5)
set pose: machine.sample(library, state)
call: shader.set_mat4_array(shaderId, "uBones", pose)
```

New clips begin at zero seconds and advance during the first tick. Mid-crossfade requests are deferred until the current transition finishes. Each source/destination clip loops independently during crossfades using the library's `loop_time`. A zero-duration fade switches immediately. This is a deterministic, bounded first state machine, **not** a declarative transition-rule DSL or animation behavior tree.

### Masked additive layers

```ow
set pose: layer.additive(library, 2, 0.5, 0, 0.5, 0.0, 0.35, [1])
```

This samples Walk as the base and Wave as an overlay, using Wave at time 0 as its reference pose. Only glTF node 1 receives the overlay (in this fixture, a child bone). Translation differences and scale ratios are applied with weight 0.35; quaternion deltas are blended from identity with shortest-arc SLERP. The final normal skinning palette is computed after layering, rather than interpolating GPU matrices. Calling `additive` is independent of the state-machine `sample` output; additive-over-crossfaded-base mixing is not yet a combined pipeline.

### Authored events and root motion

```ow
set markers: [[2, 0.25, "left-step"], [2, 0.75, "right-step"]]
set events: timeline.events_between(library, 2, markers, 0.8, 1.3)
set dx: timeline.root_delta(library, 2, 0, 0.75, 1.25)
```

These markers are authored explicitly by the game, not a standardized feature of glTF files. They use a half-open time interval `(previous,current]` and support multiple loop crossings. Results are grouped by cycle and original marker order; sort by returned absolute time if globally chronological order is necessary.

`root_delta` returns **only the local translation displacement of one selected glTF node**, including wrap crossings. It is not automatically applied to a world transform; it does not extract joint rotations, retarget animations, or remove the translation from the skinned pose. A game must decide whether to apply it as character motion and must avoid applying the same motion twice.

### Example, testing and limits

See [`examples/animation_controller/main.ow`](../examples/animation_controller/main.ow), [`three_clips.glb`](../examples/animation_controller/assets/three_clips.glb), and [`tests/animation_controller.ow`](../tests/animation_controller.ow). The original fixture adds a third Walk clip animating the parent/root bone along X to the previous two Wave/Bounce animations. The example alternates clips through a pure OW state machine, applies a masked Wave overlay on Walk, and polls step markers.

Bounded input: one GLB skin/mesh, max 32 bones and 64 nodes; 16 clips, 32 tracks per clip, 256 keys per track; additive mask contains at most 64 unique node IDs; markers max 128, crossings max 128 loops and results max 128 events. Times are nonnegative, ordered and bounded in query duration. There is no generalized node graph editor, event subscription bus, automatic root-motion integration, multi-character animation pooling, IK, morph animation or Vulkan.

The tests verify CPU animation math, strict bad-data rejection, the typed OW/flOW functor lifecycle, headless state transitions, and real Mesa/OpenGL pixel motion for overlays and the Walk root joint.

## Phase 12 expansion

`MakeAnimationGraph(StateMachine)` now adds prioritized, configurable trigger transitions without rewriting the pure OW state machine. `MakeActorCrowd(Graph)` adds independently updated actor timelines and a bounded translation-only solution to applying root-motion deltas exactly once. The original Phase 11 APIs remain unchanged. See [Animation Graphs and Crowds](ANIMATION-GRAPHS-AND-CROWD.md) for the actor state contract and the real multi-actor OpenGL example.

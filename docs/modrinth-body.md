## Feature Matrix (0.34.2)

| Feature | Status |
|---|---|
| **Rail placement preview** | Curve-exact - sampled from the same Bezier geometry as real rails (64 segments): what you see is exactly what gets built; `RailAngle.fromAngle` bytecode-verified against YMTR jar. **0.34.2 fix**: the preview injection never actually fired since 0.33 (mixin descriptor used the vanilla-MTR `class_1297` where YMTR's own `EntitySeat` is required, plus the `remap` flag was wrong for this project's Mojang-mappings classpath) - now bytecode-proven on both sides: compile-time annotation-processor validation against the remapped dev jar passes, and the refmap entry resolves to the exact runtime descriptor `(Lmtr/entity/EntitySeat;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V` of production YMTR |
| **4.0-format vehicle packs** | Full 3-level schema: geometry / bogies / coupling faces (gangway+barrier) / multi-model layers / legacy fields |
| **Multi-model layer rendering** | `VehicleResource.models` iterates every layer; each layer binds its own `textureResource` buffer. Train bodies and formation preview no longer drop all-but-one layer (old code only drew `models.get(0)`) |
| **Custom formations** | Full pipeline - formation editor on the siding screen: pick cars, head-to-head flips (per-car and Flip All), coupling-face validation with visible rejection feedback, pack-color swatch icons, selected-vehicle detail bar, paginated lists on both sides |
| **Vehicle rendering** | bbmodel native per-face UV (no CubeListBuilder bake): each face drawn with its own bbmodel UV, no overlapping boxes, no 6× vertices, through YMTR's own train rendering pipeline |
| **Door animation** | Smooth interpolation - door parts slide at real door-mechanism rates (open 0.5/s, close 0.8/s), per-train state, baked into the quad render path |
| **Connection faces** | Gangway (bellows) and barrier (end walls) rendered procedurally from pack properties - side, top and bottom textures |
| **BVE sound** | **Every key of BveTs/BveTs sound.cfg plays live (case-insensitive marker)**: 12 events - Motor (speed-tabled pitch/volume), Brake (speed-tabled, layered), Run, Flange, Joint (node-pass probability), Brake-release vent, Departure brake-vent (standstill-to-moving edge), Emergency (rapid-decel edge), Door Open, Door Close (real closing-edge detection), constant Noise, brake Shoe |
| **Live speed sampling** | TrainClient.speed drives all sound tables in real time |
| **Offline parser self-test** | `gradle parserSelfTest` - headless regression over sound.cfg parsing, CSV speed-table interpolation, and formation rules (70 checks, zero deps, no client) |

**Dual-pack coexistence promise**: 4.0-format packs are handled by MTR Next; 3.2-format packs are left entirely to YMTR (zero interference). No 4.0 packs loaded = zero UI changes. Formation persistence is sidecar NBT - remove the mod and your world opens fine.

## Verified

- `parserSelfTest`: PASS=70 FAIL=0 (sound.cfg / speed-table / formation rules)
- 38 real-server smoke tests (YMTR coexistence), zero mixin errors
- 3-way live test: YMTR + MTR Turbo + MTR Next in one server, zero conflicts
- Four-way coexistence test: YMTR + MTR Turbo + MTR Next + ANTE + ERIDU dual-format pack, zero conflicts
- Format proven against the ERIDU-DG1000A reference pack (every sound key exercised)
- All hooks bytecode-verified; four review-driven quality passes over the sound hot path (reflection dedup, early termination, single config fetch, single speed fetch)
- Vehicle pack hardening: blank/missing `id` rejected instead of collapsing to "unknown"; malformed model layers (string/number/null) skipped per-layer instead of killing the whole vehicle; 9-digit hex colors fall back to white instead of silent truncation; `length:"abc"` throws `NumberFormatException` isolated per vehicle
- **0.34.2**: rail-preview mixin chain now bytecode-proven end-to-end - compile-time target validation passes (warning gone), refmap contains the exact production descriptor, and the runtime signature matches via `javap` against both the remapped dev jar and the production YMTR jar

## Usage

1. Install YMTR 3.6.x (or MTR 3.2.x) + this mod
2. Drop in any 4.0-format vehicle pack (vehicles section in mtr_custom_resources.json)
3. The siding screen gets a formation button - compose your consist
4. Hold the rail node item with the first node selected - see the exact rail preview
5. Save - the siding dispatches your formation; bodies, faces and all twelve sound events render/play

Links: [GitHub](https://github.com/szkele1145/mtr-next) | [MTR Turbo](https://modrinth.com/mod/mtr-turbo) | [MTR-ANTE Continued](https://modrinth.com/mod/mtr-ante-continue)

## Feature Matrix (0.26.0)

| Feature | Status |
|---|---|
| **Rail placement preview** | Curve-exact - sampled from the same Bezier geometry as real rails (64 segments): what you see is exactly what gets built |
| **4.0-format vehicle packs** | Full 3-level schema: geometry / bogies / coupling faces (gangway+barrier) / multi-model layers / legacy fields |
| **Custom formations** | Full pipeline - formation editor on the siding screen: pick cars, head-to-head flips (per-car and Flip All), coupling-face validation with visible rejection feedback, pack-color swatch icons, selected-vehicle detail bar, paginated lists on both sides |
| **Vehicle rendering** | bbmodel bridge: BlockBench cubes bake to MC ModelParts with pack textures, through YMTR's own train rendering pipeline |
| **Door animation** | Smooth interpolation - door-named parts slide at real door-mechanism rates (open 0.5/s, close 0.8/s), per-train state |
| **Connection faces** | Gangway (bellows) and barrier (end walls) rendered procedurally from pack properties - side, top and bottom textures |
| **BVE sound** | **Every key of BveTs sound.cfg plays live**: 12 events - Motor (speed-tabled pitch/volume), Brake (speed-tabled, layered), Run, Flange, Joint (node-pass probability), Brake-release vent, Departure brake-vent (standstill-to-moving edge), Emergency (rapid-decel edge), Door Open, Door Close (real closing-edge detection), constant Noise, brake Shoe |
| **Live speed sampling** | TrainClient.speed drives all sound tables in real time |

**Dual-pack coexistence promise**: 4.0-format packs are handled by MTR Next; 3.2-format packs are left entirely to YMTR (zero interference). No 4.0 packs loaded = zero UI changes. Formation persistence is sidecar NBT - remove the mod and your world opens fine.

## Verified

- 38 real-server smoke tests (YMTR coexistence), zero mixin errors
- 3-way live test: YMTR + MTR Turbo + MTR Next in one server, zero conflicts
- Format proven against the ERIDU-DG1000A reference pack (every sound key exercised)
- All hooks bytecode-verified; four review-driven quality passes over the sound hot path (reflection dedup, early termination, single config fetch, single speed fetch)

## Usage

1. Install YMTR 3.6.x (or MTR 3.2.x) + this mod
2. Drop in any 4.0-format vehicle pack (vehicles section in mtr_custom_resources.json)
3. The siding screen gets a formation button - compose your consist
4. Hold the rail node item with the first node selected - see the exact rail preview
5. Save - the siding dispatches your formation; bodies, faces and all twelve sound events render/play

Links: [GitHub](https://github.com/szkele1145/mtr-next) | [MTR Turbo](https://modrinth.com/mod/mtr-turbo) | [MTR-ANTE Continued](https://modrinth.com/mod/mtr-ante-continue)

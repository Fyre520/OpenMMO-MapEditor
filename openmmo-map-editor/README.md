# OpenMMO Map Editor

A desktop editor for maps stored in the PRET `pokeemerald` and `pokefirered` decomps.

## Start

From this directory:

```powershell
.\gradlew.bat run
```

The editor discovers decomp projects in the parent `decomp` directory. Both Gen III
(`pokeemerald`, `pokefirered`) and Gen IV (`pokeheartgold`, `pokeplatinum`) decomps are recognized.
You can also pass one explicitly:

```powershell
.\gradlew.bat run --args="D:\openmmo-map-editor\decomp\pokeemerald"
```

## DS (Gen IV) maps

DS support is intentionally OpenMMO-only. The ROM is a read-only source for vanilla assets and map
data; the editor writes project overrides and runtime exports, never a patched or repacked `.nds`.

Open a HeartGold/SoulSilver or Diamond/Pearl/Platinum decomp and the DS project appears in the map
tree. Selecting a DS map switches the editor to the **3D map view** (software-rendered, no OpenGL
required):

- **Left click** paints the active tile onto the active layer.
- **Shift + drag** orbits the camera, **right drag** pans, **mouse wheel** zooms.
- The toolbar selects the tile, layer (0-7), height offset, and paint mode
  (tile / collision / permission / height). Toggle the grid and collision overlay.
- The **Header** tab edits map header fields (weather, map type, matrix, world-map coordinates,
  music, flags) — saved back to `map_headers.h`.
- The **Events** tab edits objects (NPCs), warps, coordinate triggers, and background-sound events —
  saved back to the zone-event JSON files.

Grid layouts, heights, collisions, and permissions persist per map under `.openmmo/nds/` inside the
decomp. When a matching `.nds` ROM is available (e.g. `openmmo/roms`), the editor also loads each
map's **real 32×32 terrain/collision grid** from the ROM (`land_data` for Platinum, the maps
archive for HeartGold) and decodes the map's **NSBMD model into 3D geometry**, so vanilla maps
render their actual layout and building/wall shapes in the 3D view. Use
**File → Export DS Map / Export All DS Maps** to write OpenMMO NDS-style runtime JSON (`romType`,
`mapMatrixId`, `ndsMapCells`, `borderConnections`, events, and `downloadData: true`).

Use **File → New Map** and choose an open DS region/game to create a Gen IV map. The DS form assigns
a unique map ID and footprint, can place the map in an unused part of the region's world matrix,
and can copy header/area settings from an existing map. New maps are stored under
`.openmmo/nds/maps/<MAP_NAME>/`; they do not alter the source ROM.

An external `.nsbmd` model and optional separate `.nsbtx` texture pack can be selected while
creating the map, or applied later with **File → Import DS Map Model**. Embedded TEX0 textures are
supported. Imported models replace the selected map's rendered terrain, are fitted to its footprint,
and remain project-local so the same override is restored when the project is reopened.

The **Props** panel beside the DS viewport exposes the map's original ROM buildings as editable
instances. Select one in the list or switch to **Select Object / Move Prop** and click it in the viewport;
drag to move, use the transform controls for precise position/rotation/scale, duplicate it, or press
Delete to remove it. Choose a ROM prop from the catalog and click **Place at center**, then drag the
selected instance into position; or use **Import** to add
a reusable external NSBMD/NSBTX prop model. Selecting a catalog entry shows a textured preview;
middle-drag it to rotate and use the wheel to zoom. Prop edits persist in the map's `props.json` override.

The ROM catalog uses inspected names and functional categories for both HGSS and Platinum while
keeping the original numeric ID beside every entry. Use the search box to filter by a name such as
`tree` or `bridge`, by a category such as `furniture`, or directly by ROM number. Platinum's
Underground traps, treasures, statues, and helper models are identified separately from map scenery.

Choose **Remove Scenery Object** and click the visible object to remove trees, rocks, fences, and
similar scenery. The mode handles both placed ROM props and objects baked into the terrain model.
**Clear collision with object** also makes its base footprint passable; **Restore last object**
restores the model/prop and its previous collision values. These reversible removals are stored
with the map's OpenMMO override.

Baked terrain scenery can also be selected in **Select Object / Move Prop**, dragged to a new
position, or removed with Delete. Its translation is stored in the OpenMMO map override; moving it
does not silently rewrite collision, which remains directly editable in the collision view.

Enable **Transparent collision view** to fade the complete map to 12%, show the collision grid, and
switch directly to Collision mode. Set **Value** to `0` for passable or the desired byte value, then
paint through the faded geometry.

## Editing

- Left-drag paints the selected metatile.
- Drag across metatiles to select rectangular patterns.
- Right-click picks a metatile from the map.
- Collision and elevation modes paint exact values.
- `Ctrl+Z` and `Ctrl+Y` undo or redo tile edits.
- `Ctrl+S` saves the current map.
- Event rows can be edited by double-clicking them.
- Runtime overrides duplicate maps without changing their originals.

## Prefabs

Open the **Prefabs** tab beside the metatile picker. Create a named prefab from the current rectangular selection, then select it to stamp the complete pattern onto the map.

Prefabs include the current collision and elevation values. They are stored in the decomp's Porymap-compatible `prefabs.json` file. Use **Import** to add an existing Porymap prefab file.

## Runtime export

Use **File → Export Runtime Map**. Select the root folder configured through `OPENMMO_CUSTOM_MAPS_PATH`.

The editor writes recursive runtime JSON such as:

```text
hoenn/townsandroutes/LittlerootTown.json
```

Restart the game server after changing exported maps.

## Runtime override copies

Use **File → Duplicate as Runtime Override** to create an editable variant. The copy receives independent map and layout files while exporting to the original bank and map ID.

Warps, events, encounters, and script references remain connected to the original map data. Export All automatically prefers the override over its untouched source.

## Validation

```powershell
.\gradlew.bat build
.\gradlew.bat smokeTest
.\gradlew.bat ndsEditingDiagnostic
```

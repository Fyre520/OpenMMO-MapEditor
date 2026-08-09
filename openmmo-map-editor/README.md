# OpenMMO Map Editor

A desktop editor for maps stored in the PRET `pokeemerald` and `pokefirered` decomps.

## Start

From this directory:

```powershell
.\gradlew.bat run
```

The editor discovers decomps in the parent `decomp` directory. You can also pass one explicitly:

```powershell
.\gradlew.bat run --args="D:\openmmo-map-editor\decomp\pokeemerald"
```

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
```

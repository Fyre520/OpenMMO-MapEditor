# OpenMMO Map Editor

Desktop map editor for OpenMMO and PRET Gen III decomps.

It edits native `map.bin`, `map.json`, tileset, event, warp, collision, and elevation data. Runtime exports use OpenMMO's JSON format rather than TMX.

## Clone

The Emerald and FireRed decomps are included as submodules:

```powershell
git clone --recurse-submodules https://github.com/LananaHWP/OpenMMO-MapEditor.git
cd OpenMMO-MapEditor\openmmo-map-editor
```

## Run

```powershell
.\gradlew.bat run
```

The editor automatically discovers decomps in the repository's `decomp` directory.

See [the editor documentation](openmmo-map-editor/README.md) for editing, prefab, export, and validation instructions.

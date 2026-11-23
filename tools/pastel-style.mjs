// Generates the app's pastel map style from scratch, using the OpenFreeMap "liberty" style's
// sources, layer names and filters as reference (https://tiles.openfreemap.org/styles/liberty).
//
//   node tools/pastel-style.mjs
//
// Output: shared/src/commonMain/composeResources/files/pastel-style.json
// Fewer layers and labels than liberty on purpose: the 3D world overlay is the star.

import { writeFileSync } from "node:fs";

const OUT = new URL("../shared/src/commonMain/composeResources/files/pastel-style.json", import.meta.url);

const c = {
  background: "#fbf6ec",
  grass: "#dcf0cc",
  wood: "#c9e6b6",
  park: "#d3eec4",
  sand: "#f7ecd0",
  residential: "#f7f0e4",
  school: "#fbe8e3",
  hospital: "#fde4e8",
  cemetery: "#e1ecd6",
  pitch: "#cdeac0",
  water: "#bfe3f5",
  waterway: "#a9d8f0",
  aeroway: "#ece7f3",
  runway: "#ddd6ea",
  roadMotorway: "#ffe2b3",
  roadMotorwayCase: "#f1cf98",
  roadMajor: "#fff4d6",
  roadMajorCase: "#eedfbd",
  roadMinor: "#fffdf8",
  roadCase: "#ebe2d2",
  path: "#e8dccb",
  rail: "#ddd3e6",
  building: "#f2dfe9",
  buildingTall: "#dcd6f2",
  boundary: "#c8b9dc",
  label: "#6b6377",
  labelWater: "#5f8fae",
  halo: "#ffffff",
};

const src = "openmaptiles";
const name = ["coalesce", ["get", "name:latin"], ["get", "name"]];
const polygon = ["match", ["geometry-type"], ["MultiPolygon", "Polygon"], true, false];
const line = ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false];
const notTunnel = ["!=", ["get", "brunnel"], "tunnel"];

/** Width that grows exponentially with zoom, like real roads. */
const width = (base, stops) => ["interpolate", ["exponential", base], ["zoom"], ...stops.flat()];

const fill = (id, sourceLayer, color, filter, extra = {}) => ({
  id, type: "fill", source: src, "source-layer": sourceLayer,
  ...(filter ? { filter } : {}), ...extra,
  paint: { "fill-color": color, "fill-antialias": true, ...(extra.paint || {}) },
});

const landuse = (cls, color, minzoom) =>
  fill(`landuse_${cls}`, "landuse", color, ["==", ["get", "class"], cls], minzoom ? { minzoom } : {});
const landcover = (cls, color) => fill(`landcover_${cls}`, "landcover", color, ["==", ["get", "class"], cls]);

/** A road class drawn as casing + fill. */
const road = (id, classes, fillColor, caseColor, fillW, caseW, minzoom = 0) => {
  const filter = ["all", line, notTunnel, ["match", ["get", "class"], classes, true, false]];
  const common = { type: "line", source: src, "source-layer": "transportation", filter, minzoom,
    layout: { "line-cap": "round", "line-join": "round" } };
  return [
    { id: `${id}_casing`, ...common, paint: { "line-color": caseColor, "line-width": caseW } },
    { id, ...common, paint: { "line-color": fillColor, "line-width": fillW } },
  ];
};

const label = (id, sourceLayer, filter, size, extra = {}) => ({
  id, type: "symbol", source: src, "source-layer": sourceLayer, filter,
  ...(extra.minzoom ? { minzoom: extra.minzoom } : {}),
  ...(extra.maxzoom ? { maxzoom: extra.maxzoom } : {}),
  layout: {
    "text-field": name,
    "text-font": [extra.font || "Noto Sans Regular"],
    "text-size": size,
    "text-max-width": 8,
    ...(extra.layout || {}),
  },
  paint: {
    "text-color": extra.color || c.label,
    "text-halo-color": c.halo,
    "text-halo-width": 1.6,
    "text-halo-blur": 0.5,
  },
});

const layers = [
  { id: "background", type: "background", paint: { "background-color": c.background } },

  // Ground
  landuse("residential", c.residential),
  landcover("grass", c.grass),
  landcover("wood", c.wood),
  landcover("sand", c.sand),
  fill("park", "park", c.park, polygon),
  landuse("cemetery", c.cemetery),
  landuse("school", c.school),
  landuse("hospital", c.hospital),
  landuse("pitch", c.pitch),
  fill("aeroway_area", "aeroway", c.aeroway, polygon, { minzoom: 11 }),
  {
    id: "aeroway_runway", type: "line", source: src, "source-layer": "aeroway", minzoom: 11,
    filter: ["all", line, ["==", ["get", "class"], "runway"]],
    paint: { "line-color": c.runway, "line-width": width(1.5, [[11, 3], [17, 50]]) },
  },

  // Water
  fill("water", "water", c.water, notTunnel),
  {
    id: "waterway", type: "line", source: src, "source-layer": "waterway", filter: notTunnel,
    paint: { "line-color": c.waterway, "line-width": width(1.3, [[8, 0.5], [20, 8]]) },
  },

  // Roads (minor first so major roads draw on top)
  {
    id: "road_path", type: "line", source: src, "source-layer": "transportation", minzoom: 15,
    filter: ["all", line, ["match", ["get", "class"], ["path", "pedestrian"], true, false]],
    paint: { "line-color": c.path, "line-width": width(1.2, [[15, 1], [20, 3]]), "line-dasharray": [2, 1.5] },
  },
  ...road("road_minor", ["minor", "service", "track"], c.roadMinor, c.roadCase,
    width(1.55, [[13, 1.5], [20, 18]]), width(1.55, [[13, 2.5], [20, 21]]), 12),
  ...road("road_secondary", ["secondary", "tertiary"], c.roadMajor, c.roadMajorCase,
    width(1.4, [[8, 1], [20, 22]]), width(1.4, [[8, 1.6], [20, 26]]), 8),
  ...road("road_primary", ["primary", "trunk"], c.roadMajor, c.roadMajorCase,
    width(1.4, [[6, 1], [20, 26]]), width(1.4, [[6, 1.6], [20, 30]]), 6),
  ...road("road_motorway", ["motorway"], c.roadMotorway, c.roadMotorwayCase,
    width(1.4, [[5, 1], [20, 28]]), width(1.4, [[5, 1.6], [20, 32]]), 5),
  {
    id: "rail", type: "line", source: src, "source-layer": "transportation", minzoom: 12,
    filter: ["all", line, ["match", ["get", "class"], ["rail", "transit"], true, false]],
    paint: { "line-color": c.rail, "line-width": width(1.4, [[12, 1], [20, 4]]) },
  },

  // Low pastel buildings. Heights are squashed to 40% so they read as a cute base layer under the
  // 3D world overlay. The app may later add a filter here to hide buildings under landmark models.
  {
    id: "building", type: "fill", source: src, "source-layer": "building", minzoom: 13, maxzoom: 14.5,
    paint: { "fill-color": c.building, "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0, 14, 1] },
  },
  {
    id: "building-3d", type: "fill-extrusion", source: src, "source-layer": "building", minzoom: 14,
    filter: ["!", ["has", "hide_3d"]],
    paint: {
      "fill-extrusion-color": ["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 6], 0, c.building, 40, c.buildingTall],
      "fill-extrusion-height": ["*", 0.4, ["coalesce", ["get", "render_height"], 6]],
      "fill-extrusion-base": ["*", 0.4, ["coalesce", ["get", "render_min_height"], 0]],
      "fill-extrusion-opacity": 0.85,
    },
  },

  // Country borders only
  {
    id: "boundary_country", type: "line", source: src, "source-layer": "boundary",
    filter: ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
    paint: { "line-color": c.boundary, "line-width": width(1.3, [[3, 1], [10, 2.5]]), "line-dasharray": [3, 2] },
  },

  // Labels (few)
  label("water_name", "water_name", ["match", ["geometry-type"], ["MultiPoint", "Point"], true, false],
    12, { font: "Noto Sans Italic", color: c.labelWater }),
  label("road_name", "transportation_name",
    ["match", ["get", "class"], ["primary", "secondary", "tertiary", "trunk", "minor"], true, false],
    width(1.2, [[14, 10], [19, 14]]), { minzoom: 15, layout: { "symbol-placement": "line", "text-rotation-alignment": "map" } }),
  label("place_village", "place", ["match", ["get", "class"], ["village", "suburb", "neighbourhood"], true, false],
    width(1.2, [[10, 10], [16, 14]]), { minzoom: 11, maxzoom: 16 }),
  label("place_town", "place", ["==", ["get", "class"], "town"],
    width(1.2, [[7, 11], [14, 16]]), { minzoom: 7, maxzoom: 15 }),
  label("place_city", "place", ["==", ["get", "class"], "city"],
    width(1.2, [[4, 12], [12, 20]]), { font: "Noto Sans Bold", minzoom: 4, maxzoom: 14 }),
  label("place_country", "place", ["==", ["get", "class"], "country"],
    width(1.2, [[2, 11], [6, 16]]), { font: "Noto Sans Bold", maxzoom: 8 }),
];

const style = {
  version: 8,
  name: "FilamentIso Pastel",
  sources: {
    [src]: {
      type: "vector",
      url: "https://tiles.openfreemap.org/planet",
      attribution: "<a href=\"https://openfreemap.org\">OpenFreeMap</a> © <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap contributors</a>",
    },
  },
  glyphs: "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  layers,
};

writeFileSync(OUT, JSON.stringify(style, null, 1) + "\n");
console.log(`Wrote ${layers.length} layers to ${OUT.pathname}`);

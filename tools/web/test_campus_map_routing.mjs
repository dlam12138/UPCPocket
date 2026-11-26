import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { resolveCampusPlace, searchCampusPlaces } from "../../web/campus-map-routing.js";

const asset = new URL("../../web/data/campus_map.json", import.meta.url);
const { campuses } = JSON.parse(readFileSync(asset, "utf8"));
const [tdw, gzk] = campuses;

test("course rooms distinguish numeric single-character aliases from bare aliases", () => {
  const room = tdw.places.find(place => place.name === "基础实验楼D区");
  for (const value of ["微601", "微（６０１）教室", "微（６０１）教室（示例备注）", "微601（唐岛湾校区）"]) {
    assert.deepEqual(resolveCampusPlace(campuses, value, "gzk"), { campus: tdw, place: room }, value);
  }

  for (const value of ["微", "微报告厅", "601", "南教（示例备注）", "未名楼601（示例备注）"]) {
    assert.equal(resolveCampusPlace(campuses, value, "tdw"), null, value);
  }
});

test("course room hints cannot silently change the campus or building", () => {
  for (const value of [
    "南教110（基础实验楼D601）",
    "微601（南教110）",
    "微601（古镇口校区）",
    "微601（东营科教园区）",
    "微601（唐岛湾校区）（古镇口校区）",
  ]) {
    assert.equal(resolveCampusPlace(campuses, value, "tdw"), null, value);
  }

  const duplicate = {
    ...gzk,
    places: [...gzk.places, { id: "other-lab", kind: "teaching", name: "另一实验楼", aliases: ["微"] }],
  };
  assert.equal(resolveCampusPlace([tdw, duplicate], "微601", "tdw"), null);
});

test("exact place selection, preferred campus and general search remain available", () => {
  assert.equal(resolveCampusPlace(campuses, "sport-entry-1", "gzk").place.name, "体育馆东南门");
  assert.equal(resolveCampusPlace(campuses, "南门", "gzk").campus.id, "gzk");
  assert.equal(searchCampusPlaces(tdw, "体育馆")[0].name, "体育馆");
});

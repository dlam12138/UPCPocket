// Campus data uses WGS84 [longitude, latitude]. Distances are local planar estimates in metres.
const DEGREE_METRES = 111195;
const WALK_SPEED = 1.25;
const BIKE_SPEED = 3.8;
const ROOM_NUMBER = /^[a-z]?\d{2,5}(?:教室|机房|室)?$/u;
const TRAILING_PLACE_NOTE = /\(([^()]+)\)\s*$/u;
const graphs = new WeakMap();

function longitudeScale(latitude) {
  return Math.cos(latitude * Math.PI / 180);
}

export function mapDistance(a, b) {
  const east = (b[0] - a[0]) * longitudeScale((a[1] + b[1]) / 2);
  return DEGREE_METRES * Math.hypot(east, b[1] - a[1]);
}

function projection(point, a, b) {
  const scale = longitudeScale((a[1] + b[1]) / 2);
  const dx = (b[0] - a[0]) * scale;
  const dy = b[1] - a[1];
  const lengthSquared = dx * dx + dy * dy;
  const along = lengthSquared
    ? Math.max(0, Math.min(1, ((point[0] - a[0]) * scale * dx + (point[1] - a[1]) * dy) / lengthSquared))
    : 0;
  const projected = [a[0] + (b[0] - a[0]) * along, a[1] + (b[1] - a[1]) * along];
  return { point: projected, distance: mapDistance(point, projected), along };
}

function insideArea(point, polygon) {
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i];
    const b = polygon[j];
    if (projection(point, a, b).distance < 1) return true;
    if ((a[1] > point[1]) !== (b[1] > point[1]) &&
        point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0]) {
      inside = !inside;
    }
  }
  return inside;
}

export function isInsideCampus(campus, point) {
  return campus.areas.some(area => insideArea(point, area));
}

function campusGraph(campus) {
  if (graphs.has(campus)) return graphs.get(campus);
  const points = campus.network.nodes.map(node => node.point);
  const index = new Map(campus.network.nodes.map((node, position) => [node.id, position]));
  const streets = new Map();
  const edges = [];
  for (const entry of campus.network.edges) {
    const from = index.get(entry.from);
    const to = index.get(entry.to);
    if (from === undefined || to === undefined || from === to || (!entry.walk && !entry.bike)) continue;
    const low = Math.min(from, to);
    const high = Math.max(from, to);
    const key = low + ":" + high;
    if (!streets.has(key)) streets.set(key, { low, high, edges: [] });
    const street = streets.get(key);
    const edge = { from, to, walk: entry.walk, bike: entry.bike, street };
    street.edges.push(edge);
    edges.push(edge);
  }
  const graph = { points, edges, streets: Array.from(streets.values()) };
  graphs.set(campus, graph);
  return graph;
}

function snap(graph, point, mode, limit) {
  let closest;
  for (const street of graph.streets) {
    if (!street.edges.some(edge => edge[mode])) continue;
    const candidate = projection(point, graph.points[street.low], graph.points[street.high]);
    if (candidate.distance <= limit && (!closest || candidate.distance < closest.distance)) {
      closest = { ...candidate, street };
    }
  }
  return closest;
}

function connectedEntrances(campus, place) {
  const host = place.parentId ?? place.id;
  return (place.kind === "entrance" ? [place] : campus.places.filter(candidate => candidate.parentId === host))
    .filter(candidate => candidate.kind === "entrance" && candidate.confidence === "surveyed" &&
      candidate.point && candidate.accessPoint && isInsideCampus(campus, candidate.point) &&
      isInsideCampus(campus, candidate.accessPoint));
}

function canReachDoor(campus, from, door) {
  const distance = mapDistance(from, door);
  if (distance < 0.5) return true;
  const buildings = campus.geometry.filter(shape => shape.kind === "building");
  const steps = Math.ceil(distance / 1.5);
  // Only draw the surveyed final approach when it does not cut through a building footprint.
  for (let i = 0; i <= steps; i++) {
    const point = from.map((coordinate, axis) => coordinate + (door[axis] - coordinate) * i / steps);
    if (!isInsideCampus(campus, point) || buildings.some(shape => insideArea(point, shape.points))) return false;
  }
  return true;
}

function placeTargets(campus, place) {
  const entrances = connectedEntrances(campus, place);
  if (entrances.length || place.kind === "entrance") {
    return entrances.map(entrance => ({ point: entrance.accessPoint, door: entrance.point, name: entrance.name }));
  }
  const host = campus.places.find(candidate => candidate.id === place.parentId) ?? place;
  return host.point ? [{ point: host.point }] : [];
}

function sourceSnap(campus, graph, origin, mode) {
  if (!isInsideCampus(campus, origin.point)) return null;
  if (origin.place) {
    const choices = placeTargets(campus, origin.place).map(target => ({
      target,
      snap: snap(graph, target.point, mode, target.door && mode === "walk" ? 2 : 65),
    })).filter(choice => choice.snap);
    choices.sort((a, b) => a.snap.distance - b.snap.distance);
    if (choices.length) return choices[0].snap;
  }
  return snap(graph, origin.point, mode, origin.place ? 65 : 50);
}

function connectGraph(graph, start, goal, mode, finalMode) {
  const mixed = mode !== finalMode;
  const size = graph.points.length;
  const source = mixed ? size * 2 : size;
  const target = source + 1;
  const adjacency = Array.from({ length: target + 1 }, () => []);
  function connect(from, to, a, b, travel) {
    const metres = mapDistance(a, b);
    const seconds = travel ? metres / (travel === "walk" ? WALK_SPEED : BIKE_SPEED) : 0;
    adjacency[from].push({ to, a, b, travel, metres, seconds });
  }
  function roads(travel, offset) {
    for (const edge of graph.edges) {
      if (edge[travel]) connect(edge.from + offset, edge.to + offset,
        graph.points[edge.from], graph.points[edge.to], travel);
    }
  }
  roads(mode, 0);
  if (mixed) {
    roads(finalMode, size);
    // One transition, from cycling to walking, at a vertex on the public road graph.
    for (let i = 0; i < size; i++) connect(i, i + size, graph.points[i], graph.points[i], null);
  }
  for (const edge of start.street.edges) {
    if (edge[mode]) connect(source, edge.to, start.point, graph.points[edge.to], mode);
  }
  const goalOffset = mixed ? size : 0;
  for (const edge of goal.street.edges) {
    if (edge[finalMode]) connect(edge.from + goalOffset, target, graph.points[edge.from], goal.point, finalMode);
  }
  // A point exactly at a junction belongs to its other incident streets too.
  for (const vertex of [start.street.low, start.street.high]) {
    if (mapDistance(start.point, graph.points[vertex]) < 0.05) {
      connect(source, vertex, start.point, graph.points[vertex], mode);
    }
  }
  for (const vertex of [goal.street.low, goal.street.high]) {
    if (mapDistance(goal.point, graph.points[vertex]) < 0.05) {
      connect(vertex + goalOffset, target, graph.points[vertex], goal.point, finalMode);
    }
  }
  if (!mixed && start.street === goal.street && start.street.edges.some(edge =>
    edge[mode] && (edge.from === start.street.low ? start.along <= goal.along : start.along >= goal.along))) {
    connect(source, target, start.point, goal.point, mode);
  }
  return { adjacency, source, target };
}

function shortestPath({ adjacency, source, target }) {
  const costs = Array(adjacency.length).fill(Infinity);
  const previous = Array(adjacency.length);
  const visited = new Uint8Array(adjacency.length);
  costs[source] = 0;

  // The largest bundled campus has fewer than 1,600 vertices.
  for (;;) {
    let current = -1;
    let bestCost = Infinity;
    for (let i = 0; i < costs.length; i++) {
      if (!visited[i] && costs[i] < bestCost) {
        current = i;
        bestCost = costs[i];
      }
    }
    if (current === target) break;
    if (current === -1) return null;
    visited[current] = 1;
    for (const edge of adjacency[current]) {
      const cost = bestCost + edge.seconds;
      if (cost < costs[edge.to]) {
        costs[edge.to] = cost;
        previous[edge.to] = { from: current, edge };
      }
    }
  }

  const result = [];
  for (let node = target; node !== source; node = previous[node].from) result.push(previous[node].edge);
  return result.reverse();
}

function makeRoute(graph, start, goal, destination, mode, finalMode, entrance) {
  const edges = shortestPath(connectGraph(graph, start, goal, mode, finalMode));
  if (!edges) return null;
  const segments = edges.filter(edge => edge.travel && edge.metres > 0.05);
  if (entrance) {
    const metres = mapDistance(goal.point, destination.door);
    if (metres > 0.05) segments.push({
      a: goal.point, b: destination.door, metres, seconds: metres / WALK_SPEED, travel: "walk",
    });
  }
  let metres = 0;
  let bikeMetres = 0;
  let seconds = 0;
  for (const segment of segments) {
    segment.start = metres;
    metres += segment.metres;
    seconds += segment.seconds;
    if (segment.travel === "bike") bikeMetres += segment.metres;
  }
  return {
    segments, start: start.point, end: entrance ? destination.door : goal.point,
    entrance: entrance ? destination.name : null, metres, bikeMetres, seconds,
    approximate: !entrance && Boolean(destination.door || goal.distance > 8),
  };
}

export function planCampusRoute(campus, origin, place, mode) {
  if (!isInsideCampus(campus, origin.point)) return { error: "起点在当前校区地图范围外" };
  const targets = placeTargets(campus, place).filter(target => isInsideCampus(campus, target.point));
  if (!targets.length) return { error: "目的地缺少可用的校内道路位置" };
  const graph = campusGraph(campus);
  const start = sourceSnap(campus, graph, origin, mode);
  if (!start) return { error: "起点附近没有已收录的可用道路" };
  const choices = [];
  for (const target of targets) {
    const needsWalking = mode === "bike" && target.door;
    if (needsWalking) {
      const arrival = snap(graph, target.point, "walk", 2);
      if (arrival) {
        const mixed = makeRoute(graph, start, arrival, target, mode, "walk", canReachDoor(campus, arrival.point, target.door));
        if (mixed?.bikeMetres > 0) choices.push(mixed);
      }
    }
    const direct = snap(graph, target.point, mode, target.door && mode === "walk" ? 2 : 65);
    if (direct) {
      const reachesDoor = target.door && (mode === "walk"
        ? canReachDoor(campus, direct.point, target.door) : mapDistance(target.door, direct.point) < 0.05);
      const route = makeRoute(graph, start, direct, target, mode, mode, Boolean(reachesDoor));
      if (route) choices.push(route);
    }
  }
  choices.sort((a, b) => Number(a.approximate) - Number(b.approximate) || a.seconds - b.seconds);
  return choices[0] ?? { error: "没有找到连通路线，请尝试步行或另选起终点" };
}

function placeKey(value) {
  return value.normalize("NFKC").toLowerCase().replace(/[\s·()\-]/gu, "");
}

export function searchCampusPlaces(campus, value) {
  const query = placeKey(value);
  if (!query) return [];
  return campus.places.map(place => {
    const terms = [place.name, ...place.aliases, place.region ?? ""].map(placeKey);
    const rank = terms[0] === query ? 0 : terms.includes(query) ? 1
      : terms.some(term => term.startsWith(query)) ? 2
      : terms.some(term => term.includes(query)) ? 3 : Infinity;
    return { place, rank };
  }).filter(result => Number.isFinite(result.rank))
    .sort((a, b) => a.rank - b.rank)
    .map(result => result.place);
}

export function resolveCampusPlace(campuses, value, preferredCampus) {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const result = ({ campus, place }) => ({ campus, place });
  const places = campuses.flatMap(campus => campus.places.map(place => ({
    campus, place, name: placeKey(place.name), aliases: place.aliases.map(placeKey),
  })));
  const direct = places.find(entry => entry.place.id === trimmed);
  if (direct) return result(direct);
  function exactMatches(term) {
    const names = places.filter(entry => entry.name === term);
    if (names.length || term.length < 2) return names;
    return places.filter(entry => entry.aliases.includes(term));
  }

  function roomMatches(term) {
    let longest = 0;
    const matches = [];
    for (const entry of places) {
      if (entry.place.kind === "entrance") continue;
      const prefix = [entry.name, ...entry.aliases].reduce((length, name) => {
        if (name && term.startsWith(name) && ROOM_NUMBER.test(term.slice(name.length))) return Math.max(length, name.length);
        return length;
      }, 0);
      if (!prefix || prefix < longest) continue;
      if (prefix > longest) matches.length = 0;
      longest = prefix;
      matches.push(entry);
    }
    return matches;
  }

  const exact = exactMatches(placeKey(trimmed));
  if (exact.length === 1) return result(exact[0]);
  if (exact.length > 1) {
    const preferred = exact.filter(entry => entry.campus.id === preferredCampus);
    return preferred.length === 1 ? result(preferred[0]) : null;
  }

  let candidate = trimmed.normalize("NFKC");
  const notes = [];
  for (;;) {
    const rooms = roomMatches(placeKey(candidate));
    if (rooms.length > 1) return null;
    if (rooms.length === 1) {
      const [match] = rooms;
      const compatible = notes.every(note => {
        const namedCampuses = campuses.filter(campus => note.includes(campus.name));
        if (!namedCampuses.length && /校区|东营|科教园区/u.test(note)) return false;
        if (namedCampuses.some(campus => campus !== match.campus)) return false;
        const regions = ["东区", "西区"].filter(region => note === region || (namedCampuses.length && note.includes(region)));
        if (regions.some(region => region !== match.place.region)) return false;
        const annotated = exactMatches(note);
        return (annotated.length ? annotated : roomMatches(note)).every(entry => entry === match);
      });
      return compatible ? result(match) : null;
    }
    const note = candidate.match(TRAILING_PLACE_NOTE);
    if (!note) return null;
    notes.push(placeKey(note[1]));
    candidate = candidate.slice(0, note.index).trimEnd();
    if (candidate.split("(").length !== candidate.split(")").length) return null;
  }
}

function bearing(a, b) {
  return Math.atan2((b[0] - a[0]) * longitudeScale((a[1] + b[1]) / 2), b[1] - a[1]) * 180 / Math.PI;
}

function turn(a, b) {
  const angle = (bearing(b.a, b.b) - bearing(a.a, a.b) + 540) % 360 - 180;
  if (Math.abs(angle) < 28) return null;
  if (Math.abs(angle) > 150) return "掉头";
  return angle > 0 ? "右转" : "左转";
}

export function routeProgress(route, point, prior = 0) {
  let match;
  for (const [index, segment] of route.segments.entries()) {
    if (segment.start + segment.metres < prior - 22) continue;
    const projected = projection(point, segment.a, segment.b);
    const progress = segment.start + segment.metres * projected.along;
    const score = projected.distance + Math.abs(progress - prior) * 0.02;
    if (!match || score < match.score) match = { index, score, progress, gap: projected.distance };
  }
  if (!match) return { gap: mapDistance(point, route.end), progress: 0, remaining: route.metres };
  const progress = Math.max(prior, match.progress);
  const remaining = Math.max(0, route.metres - progress);
  const nextIndex = route.segments.findIndex(segment => segment.start + segment.metres > progress + 0.5);
  let cue;
  for (let i = Math.max(1, nextIndex + 1); i < route.segments.length; i++) {
    const before = route.segments[i - 1];
    const next = route.segments[i];
    const distance = next.start - progress;
    if (distance > 100) break;
    const instruction = before.travel === "bike" && next.travel === "walk" ? "下车步行" : turn(before, next);
    if (instruction) {
      cue = { instruction, distance };
      break;
    }
  }
  return { progress, gap: match.gap, remaining, cue };
}

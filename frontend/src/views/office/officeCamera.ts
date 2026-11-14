// Where the office camera goes when asked, and where the agents' name tags sit on the
// screen. Pure functions over three's maths: the scene feeds them its camera and its
// orbit target, the tests feed them cameras of their own.
import * as THREE from "three";

export type OfficeCameraView = "front" | "top" | "side";

/**
 * What the camera is asked to do: frame the whole office (from a chosen side, if given),
 * go to one agent's desk, zoom by a factor, orbit by angles (radians), move along the
 * floor by a fraction of the distance. `instant` jumps there (the first framing, and
 * always with reduced motion).
 */
export type OfficeCameraAsk =
  | { kind: "fit"; view?: OfficeCameraView; instant?: boolean }
  | { kind: "focus"; id: number; instant?: boolean }
  | { kind: "zoom"; factor: number }
  | { kind: "orbit"; azimuth: number; polar: number }
  | { kind: "pan"; x: number; y: number };
export type OfficeCameraRequest = OfficeCameraAsk & { seq: number };

/** The floor the desks stand on, centred on the origin. */
export interface Room { width: number; depth: number }
export interface Desk { x: number; z: number; rot: number }
export interface Goal { target: THREE.Vector3; distance: number; direction?: THREE.Vector3 }
export type ScreenPoint = { x: number; y: number } | null;

export const MIN_DISTANCE = 3;
/** The camera never goes down to the floor's horizon. */
export const MAX_POLAR = Math.PI * 0.46;
const MIN_POLAR = 0.02;
/** How high above the floor a name tag sits: just over the robot's head. */
export const TAG_HEIGHT = 1.95;
/** How far behind its desk's centre a robot sits, on its chair. */
const SEAT_BACK = 0.98;
/** What has to stay in frame: the floor, up to the robots' heads. */
const ROOM_HEIGHT = 1.9;
const FOCUS_HEIGHT = 1.1;
const FOCUS_DISTANCE = 6.5;
/** How far past the desks the view may wander before it stops. */
const ROOM_MARGIN = 1.5;

export const VIEW_DIRECTION: Record<OfficeCameraView, THREE.Vector3> = {
  front: new THREE.Vector3(0, 0.62, 0.72).normalize(),
  top: new THREE.Vector3(0, 1, 0.02).normalize(),
  side: new THREE.Vector3(0.72, 0.62, 0).normalize(),
};

export function maxDistance(room: Room): number {
  return Math.max(room.width, room.depth) * 3 + 10;
}

/** Where a robot sits: its desk's centre, moved back to the chair, at height `y`. */
export function seat(desk: Desk, y = 0): THREE.Vector3 {
  return new THREE.Vector3(desk.x - SEAT_BACK * Math.sin(desk.rot), y, desk.z - SEAT_BACK * Math.cos(desk.rot));
}

/** The unit vector from the target to the camera, into `out`; a sane one when they coincide. */
export function offsetDirection(position: THREE.Vector3, target: THREE.Vector3, out: THREE.Vector3): THREE.Vector3 {
  out.copy(position).sub(target);
  if (out.lengthSq() < 1e-6) out.copy(VIEW_DIRECTION.front);
  return out.normalize();
}

/** Keeps a point over the office: inside the floor, between the floor and the ceiling. */
export function clampToRoom(point: THREE.Vector3, room: Room): THREE.Vector3 {
  const x = room.width / 2 + ROOM_MARGIN, z = room.depth / 2 + ROOM_MARGIN;
  point.x = THREE.MathUtils.clamp(point.x, -x, x);
  point.y = THREE.MathUtils.clamp(point.y, 0, 3);
  point.z = THREE.MathUtils.clamp(point.z, -z, z);
  return point;
}

function withPolar(direction: THREE.Vector3, min: number, max: number): THREE.Vector3 {
  const s = new THREE.Spherical().setFromVector3(direction);
  s.phi = THREE.MathUtils.clamp(s.phi, min, max);
  s.radius = 1;
  return new THREE.Vector3().setFromSpherical(s).normalize();
}

/**
 * Where the camera stands to see the whole floor from `direction`, around the middle of
 * the room: every corner of the box the desks fill lands inside the frame, whatever its
 * proportions.
 */
export function frameRoom(room: Room, direction: THREE.Vector3, fov: number, aspect: number): Goal {
  const back = direction.clone().normalize();
  const right = new THREE.Vector3(0, 1, 0).cross(back);
  if (right.lengthSq() < 1e-6) right.set(1, 0, 0);
  right.normalize();
  const up = back.clone().cross(right);
  const target = new THREE.Vector3(0, ROOM_HEIGHT / 2, 0);
  const tanV = Math.tan(THREE.MathUtils.degToRad(fov) / 2);
  const tanH = tanV * (Number.isFinite(aspect) && aspect > 0 ? aspect : 1);
  const p = new THREE.Vector3();
  let distance = 0;
  for (const x of [-room.width / 2, room.width / 2]) {
    for (const y of [0, ROOM_HEIGHT]) {
      for (const z of [-room.depth / 2, room.depth / 2]) {
        p.set(x, y, z).sub(target);
        // A corner nearer the eye needs the camera further back to fit.
        distance = Math.max(distance, Math.max(Math.abs(p.dot(right)) / tanH, Math.abs(p.dot(up)) / tanV) + p.dot(back));
      }
    }
  }
  return { target, distance: Math.max(MIN_DISTANCE, distance * 1.04), direction: back };
}

/**
 * Where a request sends the camera. Zoom, orbit and pan build on the goal in flight, so
 * pressing a key again goes further instead of starting over.
 */
export function answer(asked: OfficeCameraAsk, inFlight: Goal | null, camera: THREE.PerspectiveCamera, target: THREE.Vector3,
                       aspect: number, room: Room, seats: Map<number, THREE.Vector3>): Goal | null {
  const dir = inFlight?.direction?.clone() ?? offsetDirection(camera.position, target, new THREE.Vector3());
  const distance = inFlight?.distance ?? camera.position.distanceTo(target);
  const center = inFlight?.target.clone() ?? target.clone();
  switch (asked.kind) {
    case "fit":
      return frameRoom(room, asked.view ? VIEW_DIRECTION[asked.view] : dir, camera.fov, aspect);
    case "focus": {
      const at = seats.get(asked.id);
      if (!at) return null;
      // From the side the camera looks now, but never straight down: the robot shows.
      return { target: new THREE.Vector3(at.x, FOCUS_HEIGHT, at.z), distance: FOCUS_DISTANCE, direction: withPolar(dir, 0.45, MAX_POLAR) };
    }
    case "zoom":
      return { target: center, distance: THREE.MathUtils.clamp(distance * asked.factor, MIN_DISTANCE, maxDistance(room)),
        direction: inFlight?.direction };
    case "orbit": {
      const s = new THREE.Spherical().setFromVector3(dir);
      s.theta += asked.azimuth;
      s.phi = THREE.MathUtils.clamp(s.phi + asked.polar, MIN_POLAR, MAX_POLAR);
      return { target: center, distance, direction: new THREE.Vector3().setFromSpherical(s).normalize() };
    }
    case "pan": {
      // Along the floor: right as on the screen, forward away from the eye.
      const right = new THREE.Vector3(0, 1, 0).cross(dir);
      if (right.lengthSq() < 1e-6) right.set(1, 0, 0);
      right.normalize();
      const forward = new THREE.Vector3(-dir.x, 0, -dir.z);
      if (forward.lengthSq() < 1e-8) forward.set(0, 0, -1);
      forward.normalize();
      const moved = center.addScaledVector(right, asked.x * distance).addScaledVector(forward, asked.y * distance);
      return { target: clampToRoom(moved, room), distance, direction: inFlight?.direction };
    }
  }
}

/**
 * Where each name tag goes on the canvas, in CSS pixels snapped to the device's pixels
 * (null when its agent is behind the camera), into `out`. The camera is read as it is
 * now: the orbit controls move it without bringing its matrices along, and a tag placed
 * with last frame's matrices trails behind its robot while the view moves.
 */
export function placeTags(camera: THREE.Camera, anchors: Map<number, THREE.Vector3>, width: number, height: number, dpr: number,
                          out: Map<number, ScreenPoint>): Map<number, ScreenPoint> {
  camera.updateMatrixWorld();
  const v = new THREE.Vector3();
  const snap = (value: number) => Math.round(value * dpr) / dpr;
  out.clear();
  for (const [id, anchor] of anchors) {
    v.copy(anchor).project(camera);
    out.set(id, v.z > 1 || v.z < -1 ? null : { x: snap(((v.x + 1) / 2) * width), y: snap(((1 - v.y) / 2) * height) });
  }
  return out;
}

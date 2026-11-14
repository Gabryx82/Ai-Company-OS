import { describe, expect, it } from "vitest";
import * as THREE from "three";
import { MAX_POLAR, VIEW_DIRECTION, answer, frameRoom, placeTags, seat, type Goal, type Room, type ScreenPoint } from "./officeCamera";

const room: Room = { width: 23.2, depth: 16.4 };

function cameraAt(goal: Goal, aspect: number): THREE.PerspectiveCamera {
  const camera = new THREE.PerspectiveCamera(42, aspect, 0.1, 400);
  camera.position.copy(goal.direction!).multiplyScalar(goal.distance).add(goal.target);
  camera.lookAt(goal.target);
  camera.updateMatrixWorld();
  return camera;
}

function polar(direction: THREE.Vector3): number {
  return new THREE.Spherical().setFromVector3(direction).phi;
}

describe("the office camera", () => {
  it("frames the whole floor from each side, whatever the shape of the stage", () => {
    for (const view of ["front", "top", "side"] as const) {
      for (const aspect of [0.6, 1, 1.7, 2.6]) {
        const camera = cameraAt(frameRoom(room, VIEW_DIRECTION[view], 42, aspect), aspect);
        let widest = 0;
        for (const x of [-room.width / 2, room.width / 2]) {
          for (const y of [0, 1.9]) {
            for (const z of [-room.depth / 2, room.depth / 2]) {
              const p = new THREE.Vector3(x, y, z).project(camera);
              expect(Math.max(Math.abs(p.x), Math.abs(p.y)), `${view} at ${aspect}`).toBeLessThanOrEqual(1);
              widest = Math.max(widest, Math.abs(p.x), Math.abs(p.y));
            }
          }
        }
        // ...and not from so far that the office is a speck.
        expect(widest, `${view} at ${aspect}`).toBeGreaterThan(0.85);
      }
    }
  });

  it("never takes the camera down to the floor's horizon, however far it is turned", () => {
    const goal = frameRoom(room, VIEW_DIRECTION.front, 42, 1.6);
    const camera = cameraAt(goal, 1.6);
    let inFlight: Goal | null = null;
    for (let i = 0; i < 12; i++) {
      inFlight = answer({ kind: "orbit", azimuth: 0.1, polar: 0.3 }, inFlight, camera, goal.target, 1.6, room, new Map());
    }
    expect(polar(inFlight!.direction!)).toBeLessThanOrEqual(MAX_POLAR + 1e-9);
  });

  it("moves along the floor and stops at the edge of the office", () => {
    const goal = frameRoom(room, VIEW_DIRECTION.top, 42, 1.6);
    const camera = cameraAt(goal, 1.6);
    let inFlight: Goal | null = null;
    for (let i = 0; i < 40; i++) {
      inFlight = answer({ kind: "pan", x: 0.08, y: 0.08 }, inFlight, camera, goal.target, 1.6, room, new Map());
    }
    expect(inFlight!.target.y).toBeCloseTo(goal.target.y, 6);
    // Up on the screen, seen from above, is the back of the room; right is right.
    expect(inFlight!.target.z).toBeLessThan(-room.depth / 2);
    expect(inFlight!.target.x).toBeGreaterThan(room.width / 2);
    expect(inFlight!.target.x).toBeLessThanOrEqual(room.width / 2 + 1.5);
    expect(inFlight!.target.z).toBeGreaterThanOrEqual(-room.depth / 2 - 1.5);
  });

  it("goes to an agent's chair, whichever way its desk faces", () => {
    expect(seat({ x: 2, z: -1.05, rot: 0 }).toArray()).toEqual([2, 0, -2.0300000000000002]);
    const facing = seat({ x: 2, z: 1.05, rot: Math.PI });
    expect(facing.x).toBeCloseTo(2, 6);
    expect(facing.z).toBeCloseTo(2.03, 6);
    const camera = cameraAt(frameRoom(room, VIEW_DIRECTION.top, 42, 1.6), 1.6);
    const goal = answer({ kind: "focus", id: 7 }, null, camera, new THREE.Vector3(0, 0.95, 0), 1.6, room, new Map([[7, facing]]));
    expect(goal!.target.x).toBeCloseTo(2, 6);
    expect(goal!.target.z).toBeCloseTo(2.03, 6);
    // From straight above, the camera tilts enough to see the robot and not only its head.
    expect(polar(goal!.direction!)).toBeGreaterThanOrEqual(0.45 - 1e-9);
    expect(answer({ kind: "focus", id: 8 }, null, camera, new THREE.Vector3(), 1.6, room, new Map())).toBeNull();
  });

  it("puts each name tag over its agent as the camera is now, not as it was last drawn", () => {
    const camera = new THREE.PerspectiveCamera(42, 1.5, 0.1, 400);
    camera.position.set(0, 20, 25);
    camera.lookAt(0, 0, 0);
    camera.updateMatrixWorld();
    // The orbit controls move the camera between two frames and leave its matrices behind.
    camera.position.set(18, 12, 10);
    camera.lookAt(0, 0, 0);
    const anchors = new Map([[1, new THREE.Vector3(3, 1.95, -2)], [2, new THREE.Vector3(40, 14, 20)]]);
    const placed = placeTags(camera, anchors, 900, 600, 1.25, new Map<number, ScreenPoint>());

    const now = new THREE.PerspectiveCamera(42, 1.5, 0.1, 400);
    now.position.set(18, 12, 10);
    now.lookAt(0, 0, 0);
    now.updateMatrixWorld();
    const expected = anchors.get(1)!.clone().project(now);
    const tag = placed.get(1)!;
    expect(Math.abs(tag!.x - ((expected.x + 1) / 2) * 900)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(tag!.y - ((1 - expected.y) / 2) * 600)).toBeLessThanOrEqual(0.5);
    // Whole device pixels: the text stays sharp as it moves.
    expect(Number.isInteger(Math.round(tag!.x * 1.25 * 1000) / 1000)).toBe(true);
    // An agent behind the camera has no place on the screen.
    expect(placed.get(2)).toBeNull();
  });
});

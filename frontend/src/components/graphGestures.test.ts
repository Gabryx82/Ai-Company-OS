import { describe, expect, it } from "vitest";
import { horizontalSwipe, twoFingers, wheelView } from "./graphGestures";

const wheel = (deltaX: number, deltaY: number, extra: { ctrlKey?: boolean; shiftKey?: boolean; deltaMode?: number } = {}) =>
  ({ deltaX, deltaY, deltaMode: extra.deltaMode ?? 0, ctrlKey: extra.ctrlKey ?? false, shiftKey: extra.shiftKey ?? false });

describe("finger gestures on a graph", () => {
  it("reads two fingers sideways as a move along x, and up and down as a zoom", () => {
    expect(horizontalSwipe(wheel(30, 4))).toBe(30);
    expect(horizontalSwipe(wheel(-30, 4))).toBe(-30);
    expect(horizontalSwipe(wheel(4, 30))).toBeNull();
    expect(horizontalSwipe(wheel(0, 0))).toBeNull();
    // A pinch on a touchpad arrives with Ctrl: it zooms, whatever its direction.
    expect(horizontalSwipe(wheel(30, 4, { ctrlKey: true }))).toBeNull();
    // Shift + mouse wheel is a sideways scroll; lines count as 16 pixels.
    expect(horizontalSwipe(wheel(0, 3, { shiftKey: true, deltaMode: 1 }))).toBe(48);
  });

  it("moves a 2D view along x only, the map following the fingers", () => {
    const v = { x: 10, y: 20, k: 1.5 };
    expect(wheelView(v, wheel(40, 2), 100, 50, 0.1, 6)).toEqual({ x: -30, y: 20, k: 1.5 });
    expect(wheelView(v, wheel(-40, 2), 100, 50, 0.1, 6)).toEqual({ x: 50, y: 20, k: 1.5 });
  });

  it("zooms a 2D view around the pointer when the fingers go up or down", () => {
    const v = { x: 0, y: 0, k: 1 };
    const zoomed = wheelView(v, wheel(0, -100), 100, 0, 0.1, 6);
    expect(zoomed.k).toBeGreaterThan(1);
    // The world point under the pointer (100, 0) is still under it.
    expect((100 - zoomed.x) / zoomed.k).toBeCloseTo(100);
    expect(wheelView(v, wheel(0, 10000), 0, 0, 0.1, 6).k).toBe(0.1);
  });

  it("carries the map with two fingers on a touch screen, and zooms with their spread", () => {
    const v = { x: 0, y: 0, k: 1 };
    const moved = twoFingers(v, [{ x: -10, y: 0 }, { x: 10, y: 0 }], [{ x: 50, y: 0 }, { x: 70, y: 0 }], 0.1, 6);
    expect(moved).toEqual({ x: 60, y: 0, k: 1 });
    const pinched = twoFingers(v, [{ x: -10, y: 0 }, { x: 10, y: 0 }], [{ x: -20, y: 0 }, { x: 20, y: 0 }], 0.1, 6);
    expect(pinched.k).toBe(2);
  });
});

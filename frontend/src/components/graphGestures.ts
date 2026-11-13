// What the operator's fingers mean on a graph canvas, the same in the 3D ecosystem
// and in the Second Brain. Pure functions: the views feed them the events.
//  - two fingers to the right or left on a touchpad (or Shift + wheel): move the view
//    along x, the map following the fingers;
//  - two fingers up and down: zoom, as the wheel; a pinch arrives as wheel + Ctrl;
//  - on a touch screen, two fingers drag and pinch together.

export interface WheelInput { deltaX: number; deltaY: number; deltaMode: number; ctrlKey: boolean; shiftKey?: boolean }
export type View2D = { x: number; y: number; k: number };
export type Point = { x: number; y: number };

const PER_MODE = [1, 16, 400];

/**
 * The pixels of a sideways swipe, positive when the fingers go left (the map goes
 * left with them); null when the wheel means a zoom or a pinch.
 */
export function horizontalSwipe(e: WheelInput): number | null {
  if (e.ctrlKey) return null;
  const scale = PER_MODE[e.deltaMode] ?? 1;
  const dx = e.deltaX * scale, dy = e.deltaY * scale;
  // A mouse wheel with Shift held is a sideways scroll, wherever the browser puts it.
  if (e.shiftKey && dx === 0 && dy !== 0) return dy;
  if (dx === 0 || Math.abs(dx) <= Math.abs(dy)) return null;
  return dx;
}

/**
 * A wheel event on a 2D view: sideways it moves along x, otherwise it zooms around
 * the pointer (`px`, `py` measured from the canvas centre).
 */
export function wheelView(v: View2D, e: WheelInput, px: number, py: number, min: number, max: number): View2D {
  const swipe = horizontalSwipe(e);
  if (swipe !== null) return { ...v, x: v.x - swipe };
  const delta = e.deltaY * (PER_MODE[e.deltaMode] ?? 1);
  const k = clampK(v.k * Math.exp(-delta * (e.ctrlKey ? 0.01 : 0.0016)), min, max);
  return { k, x: px - (px - v.x) * (k / v.k), y: py - (py - v.y) * (k / v.k) };
}

/**
 * Two fingers on a touch screen, from one position to the next (both measured from
 * the canvas centre): the point between them carries the map, their spread zooms it.
 */
export function twoFingers(v: View2D, before: [Point, Point], after: [Point, Point], min: number, max: number): View2D {
  const m0 = mid(before), m1 = mid(after);
  const d0 = spread(before), d1 = spread(after);
  const k = d0 > 1 && d1 > 1 ? clampK(v.k * (d1 / d0), min, max) : v.k;
  // The world point under the fingers stays under them.
  const wx = (m0.x - v.x) / v.k, wy = (m0.y - v.y) / v.k;
  return { k, x: m1.x - wx * k, y: m1.y - wy * k };
}

function mid([a, b]: [Point, Point]): Point {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

function spread([a, b]: [Point, Point]): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function clampK(k: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, k));
}

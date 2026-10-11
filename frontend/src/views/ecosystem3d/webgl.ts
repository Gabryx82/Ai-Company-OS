/** Whether this browser can draw WebGL at all: without it the 3D view says so instead of failing (ADR-037). */
export function webglAvailable(): boolean {
  try {
    const canvas = document.createElement("canvas");
    return !!(canvas.getContext("webgl2") ?? canvas.getContext("webgl"));
  } catch {
    return false;
  }
}

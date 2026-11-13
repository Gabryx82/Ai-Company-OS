import { useEffect, useRef } from "react";

/**
 * The assistant's symbol: a glass sphere with clouds of blue light flowing inside it,
 * drawn by one fragment shader (no 3D library on the home page). `energy` from 0 to 1
 * makes it turn faster and glow while the assistant is thinking. Without WebGL, or with
 * reduced motion, it is a still frame or a gradient of the same colours.
 */
const VERTEX = `attribute vec2 p; void main() { gl_Position = vec4(p, 0.0, 1.0); }`;

const FRAGMENT = `
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uEnergy;

float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
  return v;
}

void main() {
  vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / (0.5 * min(uRes.x, uRes.y));
  float r = 0.72;
  float d = length(uv);
  float t = uTime * (0.28 + 0.9 * uEnergy);

  vec3 glowCol = vec3(0.16, 0.45, 1.0);
  float glow = exp(-max(d - r, 0.0) * 7.5) * (0.32 + 0.4 * uEnergy);
  vec3 col = glowCol * glow;
  float alpha = glow * 0.85;

  if (d < r + 0.01) {
    vec2 s = uv / r;
    vec3 n = vec3(s, sqrt(max(0.0, 1.0 - dot(s, s))));
    float a = t * 0.55;
    mat2 rot = mat2(cos(a), -sin(a), sin(a), cos(a));
    vec2 q = rot * (n.xy / (0.55 + n.z * 0.8));
    float f1 = fbm(q * 1.5 + vec2(t * 0.35, -t * 0.25));
    float f2 = fbm(q * 2.4 - vec2(t * 0.2, t * 0.45) + f1 * 1.6);
    vec2 w = q + vec2(f2 - 0.5, f1 - 0.5) * 1.3;

    vec3 deep = vec3(0.01, 0.025, 0.08);
    float depth = 0.3 + 0.7 * n.z;

    // Three clouds of light drifting inside the glass, warped by the flow, blending where they meet.
    vec2 c1 = vec2(sin(t * 0.9) * 0.55, cos(t * 0.7) * 0.45);
    vec2 c2 = vec2(cos(t * 0.6 + 2.1) * 0.6, sin(t * 0.8 + 1.3) * 0.5);
    vec2 c3 = vec2(sin(t * 0.5 + 4.0) * 0.5, cos(t * 1.1 + 0.4) * 0.55);
    float k = 3.2 - 0.6 * uEnergy;
    float b1 = exp(-dot(w - c1, w - c1) * k);
    float b2 = exp(-dot(w - c2, w - c2) * k * 1.2);
    float b3 = exp(-dot(w - c3, w - c3) * k * 0.9);
    vec3 light = vec3(0.04, 0.32, 1.0) * b1 * 1.25
               + vec3(0.0, 0.72, 1.0) * b2 * 0.95
               + vec3(0.42, 0.18, 1.0) * b3 * 0.9;
    // Thin bright filaments where the flow folds, the Siri-like shimmer.
    float fil = pow(1.0 - abs(f2 * 2.0 - 1.0), 1.6) * (b1 + b2 + b3);
    light += vec3(0.55, 0.85, 1.0) * fil * 0.3;
    light *= depth * (0.8 + 0.35 * uEnergy);
    vec3 body = deep + (vec3(1.0) - exp(-light * 1.1)) * 0.92;

    float fres = pow(1.0 - n.z, 2.4);
    body += vec3(0.2, 0.5, 1.0) * fres * 0.55;
    vec3 L = normalize(vec3(-0.45, 0.6, 0.75));
    float spec = pow(max(dot(reflect(-L, n), vec3(0.0, 0.0, 1.0)), 0.0), 36.0);
    body += vec3(0.9, 0.95, 1.0) * spec * 0.55;
    float rim = smoothstep(r + 0.01, r - 0.012, d);
    col = mix(col, body, rim);
    alpha = mix(alpha, 1.0, rim);
  }
  gl_FragColor = vec4(col, alpha);
}`;

function compile(gl: WebGLRenderingContext, type: number, source: string) {
  const shader = gl.createShader(type)!;
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (gl.getShaderParameter(shader, gl.COMPILE_STATUS)) return shader;
  console.warn("Assistant orb shader:", gl.getShaderInfoLog(shader));
  return null;
}

export function AssistantOrb({ energy = 0, size = 148 }: { energy?: number; size?: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const target = useRef(energy);
  target.current = energy;

  useEffect(() => {
    const el = canvas.current;
    if (!el) return;
    let gl: WebGLRenderingContext | null = null;
    try {
      gl = el.getContext("webgl", { premultipliedAlpha: false, alpha: true, antialias: true });
    } catch {
      gl = null;
    }
    if (!gl || gl.isContextLost()) { el.dataset.fallback = "1"; return; }
    delete el.dataset.fallback;
    const vs = compile(gl, gl.VERTEX_SHADER, VERTEX);
    const fs = compile(gl, gl.FRAGMENT_SHADER, FRAGMENT);
    if (!vs || !fs) { el.dataset.fallback = "1"; return; }
    const program = gl.createProgram()!;
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    gl.useProgram(program);
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(program, "p");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    const uRes = gl.getUniformLocation(program, "uRes");
    const uTime = gl.getUniformLocation(program, "uTime");
    const uEnergy = gl.getUniformLocation(program, "uEnergy");
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    const still = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
    let raf = 0;
    let visible = true;
    let shown = 0;
    let clock = 4.2;
    let last = performance.now();
    const draw = (now: number) => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = Math.round(el.clientWidth * dpr), h = Math.round(el.clientHeight * dpr);
      if (el.width !== w || el.height !== h) { el.width = w; el.height = h; }
      const dt = Math.min((now - last) / 1000, 0.1);
      last = now;
      shown += (target.current - shown) * Math.min(1, dt * 3);
      if (!still) clock += dt;
      gl!.viewport(0, 0, w, h);
      gl!.clearColor(0, 0, 0, 0);
      gl!.clear(gl!.COLOR_BUFFER_BIT);
      gl!.uniform2f(uRes, w, h);
      gl!.uniform1f(uTime, clock);
      gl!.uniform1f(uEnergy, shown);
      gl!.drawArrays(gl!.TRIANGLE_STRIP, 0, 4);
      if (visible && !still) raf = requestAnimationFrame(draw);
    };
    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      cancelAnimationFrame(raf);
      if (visible) { last = performance.now(); raf = requestAnimationFrame(draw); }
    });
    observer.observe(el);
    // The first frame at once, so the symbol is there before the first animation frame (or with motion off).
    draw(performance.now());
    return () => {
      cancelAnimationFrame(raf);
      observer.disconnect();
    };
  }, []);

  return <canvas ref={canvas} className="assistant-orb" style={{ width: size, height: size }} aria-hidden="true" />;
}

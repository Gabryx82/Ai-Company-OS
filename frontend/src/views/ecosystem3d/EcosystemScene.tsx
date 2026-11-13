// The 3D scene of the agent ecosystem (ADR-037), drawn with three.js through
// React Three Fiber. Built to stay fluid with many nodes:
//  - one instanced mesh per shape (one draw call per category), one for the
//    pointer's hit spheres, one line set for every edge, one point set for the
//    glows and one for the signals that run along the edges;
//  - everything that moves is written in a single frame loop into preallocated
//    buffers; React re-renders only when the data or the view change;
//  - every geometry, material and texture made here is disposed on unmount,
//    and the WebGL context goes with the canvas when the tab is left.
// The look is soft and translucent: pastel glass lit by a studio environment,
// fresnel bubbles around agents and clusters, hairline edges that let the map
// show through, a polar floor that gives the space a ground.
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";
import { CATEGORY, SUBAGENT_COLOR, agentOf, type Category, type EcoNode, type Shape, type VisibleGraph } from "./ecosystemModel";
import { bounds, nodeRadius, spawnPoint, type Vec3 } from "./ecosystemLayout";
import { horizontalSwipe } from "../../components/graphGestures";

/** Where the camera looks from when asked for a view. */
export type CameraView = "front" | "top" | "side";

/**
 * What the camera is asked to do: frame the whole map (from a chosen side, if given),
 * fly to one node, zoom by a factor, orbit by angles (radians), pan by a fraction of
 * the distance. `instant` jumps there (the first framing, and always with reduced motion).
 */
export type CameraAsk =
  | { kind: "fit"; instant?: boolean; view?: CameraView }
  | { kind: "focus"; id: string; instant?: boolean }
  | { kind: "zoom"; factor: number }
  | { kind: "orbit"; azimuth: number; polar: number }
  | { kind: "pan"; x: number; y: number };
export type CameraRequest = CameraAsk & { seq: number };

export interface SceneProps {
  visible: VisibleGraph;
  positions: Map<string, Vec3>;
  /** The node under the pointer or selected, and its neighbours: everything else is dimmed. */
  focusId: string | null;
  focusSet: Set<string> | null;
  /** Search hits, when a search is typed. */
  matches: Set<string> | null;
  selected: string | null;
  labels: "auto" | "all";
  animate: boolean;
  autoRotate: boolean;
  camera: CameraRequest | null;
  onHover: (id: string | null) => void;
  onSelect: (id: string | null) => void;
  onActivate: (id: string) => void;
  /** A double click on empty space. */
  onBackground?: () => void;
}

/** The haze the map fades into: the middle of the stage's gradient (ecosystem3d.css). */
const BACKGROUND = "#141d30";
const SEGMENTS = 8;
const MAX_PARTICLES = 1400;
const MAX_LABELS = 320;
const MIN_DISTANCE = 2.5;
const MAX_DISTANCE = 900;
const WHITE = new THREE.Color("#ffffff");

const VIEW_DIRECTION: Record<CameraView, THREE.Vector3> = {
  front: new THREE.Vector3(0, 0.32, 1).normalize(),
  top: new THREE.Vector3(0, 1, 0.04).normalize(),
  side: new THREE.Vector3(1, 0.32, 0).normalize(),
};

function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619);
  return (h >>> 0) / 4294967295;
}

function makeGeometry(shape: Shape): THREE.BufferGeometry {
  switch (shape) {
    case "icosahedron": return new THREE.IcosahedronGeometry(1, 1);
    case "octahedron": return new THREE.OctahedronGeometry(1.15, 0);
    case "hexagon": return new THREE.CylinderGeometry(0.95, 0.95, 0.8, 6);
    case "torus": return new THREE.TorusGeometry(0.85, 0.3, 16, 40);
    case "tetrahedron": return new THREE.TetrahedronGeometry(1.25, 0);
    case "book": return new THREE.BoxGeometry(1.25, 1.55, 0.42);
    case "knot": return new THREE.TorusKnotGeometry(0.62, 0.2, 80, 10);
    case "cone": return new THREE.ConeGeometry(0.8, 1.6, 24);
    case "dodecahedron": return new THREE.DodecahedronGeometry(1.05, 0);
    case "cube": return new THREE.BoxGeometry(1.3, 1.3, 1.3);
    case "capsule": return new THREE.CapsuleGeometry(0.5, 0.9, 8, 16);
    case "card": return new THREE.BoxGeometry(1.5, 1.0, 0.16);
    case "pyramid": return new THREE.ConeGeometry(0.95, 1.5, 4);
    case "coin": return new THREE.CylinderGeometry(0.95, 0.95, 0.3, 32).rotateX(Math.PI / 2);
    case "sphere": return new THREE.IcosahedronGeometry(0.95, 3);
  }
}

/**
 * Pastel glass: a clear-coated, slightly see-through surface lit by the environment,
 * with a soft inner light in the node's own colour and a brighter rim where it turns
 * away from the eye -- the shape reads at any distance without shouting.
 */
function glassMaterial(): THREE.MeshPhysicalMaterial {
  const material = new THREE.MeshPhysicalMaterial({
    color: "#ffffff", roughness: 0.3, metalness: 0, clearcoat: 0.8, clearcoatRoughness: 0.2,
    sheen: 0.35, sheenRoughness: 0.6, sheenColor: new THREE.Color("#dfe8ff"),
    transparent: true, opacity: 0.86, envMapIntensity: 0.9,
  });
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uGlow = { value: 0.1 };
    shader.uniforms.uRim = { value: 0.5 };
    shader.fragmentShader = `uniform float uGlow;\nuniform float uRim;\n${shader.fragmentShader.replace("#include <emissivemap_fragment>",
      `#include <emissivemap_fragment>
#ifdef USE_COLOR
  float aicosRim = pow(1.0 - clamp(dot(normal, normalize(vViewPosition)), 0.0, 1.0), 2.6);
  totalEmissiveRadiance += vColor.rgb * (uGlow + aicosRim * uRim);
#endif`)}`;
  };
  material.customProgramCacheKey = () => "aicos-eco-glass";
  return material;
}

/** A soap bubble around agents and clusters: clear in the middle, a tinted rim. */
function bubbleMaterial(): THREE.ShaderMaterial {
  return new THREE.ShaderMaterial({
    uniforms: { uOpacity: { value: 0.32 } },
    vertexShader: `
      varying vec3 vNormalV;
      varying vec3 vView;
      varying vec3 vTint;
      void main() {
        vec4 mv = modelViewMatrix * instanceMatrix * vec4(position, 1.0);
        vNormalV = normalize(normalMatrix * mat3(instanceMatrix) * normal);
        vView = normalize(-mv.xyz);
        #ifdef USE_INSTANCING_COLOR
          vTint = instanceColor;
        #else
          vTint = vec3(1.0);
        #endif
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: `
      uniform float uOpacity;
      varying vec3 vNormalV;
      varying vec3 vView;
      varying vec3 vTint;
      void main() {
        float facing = abs(dot(normalize(vNormalV), normalize(vView)));
        float rim = pow(1.0 - facing, 2.4);
        vec3 tint = mix(vTint, vec3(1.0), 0.5);
        gl_FragColor = vec4(tint, rim * uOpacity + 0.02);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    transparent: true, depthWrite: false, side: THREE.DoubleSide,
  });
}

/** The ground: a polar grid that fades out, and a faint pool of light under the map. */
function floorMaterial(): THREE.ShaderMaterial {
  return new THREE.ShaderMaterial({
    uniforms: { uColor: { value: new THREE.Color("#a3b6da") }, uRings: { value: 9 } },
    vertexShader: `
      varying vec2 vUv;
      void main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
    fragmentShader: `
      uniform vec3 uColor;
      uniform float uRings;
      varying vec2 vUv;
      void main() {
        vec2 p = vUv * 2.0 - 1.0;
        float r = length(p);
        if (r > 1.0) discard;
        float d = r * uRings;
        float ring = 1.0 - min(abs(fract(d - 0.5) - 0.5) / max(fwidth(d), 1e-4), 1.0);
        float a = atan(p.y, p.x) / 6.2831853 * 24.0;
        float spoke = (1.0 - min(abs(fract(a - 0.5) - 0.5) / max(fwidth(a), 1e-4), 1.0)) * smoothstep(0.12, 0.35, r);
        float fade = smoothstep(1.0, 0.15, r);
        float pool = 0.08 * (1.0 - smoothstep(0.0, 0.85, r));
        gl_FragColor = vec4(uColor, (max(ring, spoke * 0.5) * 0.15 + pool) * fade);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    transparent: true, depthWrite: false,
  });
}

/** Soft round points with a size each, added to what is behind them: glows and signals. */
function pointsMaterial(intensity: number): THREE.ShaderMaterial {
  return new THREE.ShaderMaterial({
    uniforms: { uScale: { value: 300 }, uIntensity: { value: intensity } },
    vertexShader: `
      attribute float size;
      attribute vec3 color;
      varying vec3 vColor;
      uniform float uScale;
      void main() {
        vColor = color;
        vec4 mv = modelViewMatrix * vec4(position, 1.0);
        gl_PointSize = size * uScale / max(0.001, -mv.z);
        gl_Position = projectionMatrix * mv;
      }`,
    fragmentShader: `
      varying vec3 vColor;
      uniform float uIntensity;
      void main() {
        float d = length(gl_PointCoord - vec2(0.5));
        float a = smoothstep(0.5, 0.0, d);
        a = a * a;
        gl_FragColor = vec4(vColor * a * uIntensity, a);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  });
}

const capacityFor = (n: number) => Math.max(16, 2 ** Math.ceil(Math.log2(Math.max(1, n))));

export default function EcosystemScene(props: SceneProps) {
  const tooltip = useRef<HTMLDivElement>(null);
  const nodeClick = useRef(0);
  const [hovered, setHovered] = useState<string | null>(null);
  const hoveredNode = hovered ? props.visible.nodes.find((n) => n.id === hovered) ?? null : null;
  const onHover = (id: string | null) => {
    setHovered(id);
    props.onHover(id);
  };
  const follow = (event: ReactPointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    if (tooltip.current) {
      tooltip.current.style.transform = `translate(${event.clientX - box.left + 14}px, ${event.clientY - box.top + 14}px)`;
    }
  };

  return (
    <div className="eco-canvas-wrap" onPointerMove={follow} style={{ cursor: hovered ? "pointer" : "grab" }}
         onDoubleClick={() => { if (performance.now() - nodeClick.current > 80) props.onBackground?.(); }}>
      <Canvas dpr={[1, 2]} frameloop={props.animate ? "always" : "demand"}
              camera={{ fov: 45, near: 0.1, far: 5000, position: [0, 26, 70] }}
              gl={{ antialias: true, powerPreference: "high-performance", toneMapping: THREE.NeutralToneMapping }}
              onPointerMissed={() => props.onSelect(null)}>
        <Backdrop />
        <fog attach="fog" args={[BACKGROUND, 140, 620]} />
        <ambientLight intensity={0.3} />
        <hemisphereLight args={["#e6eeff", "#1d2638", 0.75]} />
        <directionalLight position={[50, 80, 40]} intensity={1.1} color="#fff5e8" />
        <directionalLight position={[-60, 18, -45]} intensity={0.45} color="#bcd0f7" />
        <StudioLight />
        <Dust />
        <Graph {...props} onHover={onHover} onNodeDoubleClick={() => { nodeClick.current = performance.now(); }} />
      </Canvas>
      <div ref={tooltip} className="eco-tooltip" hidden={!hoveredNode} role="tooltip">
        {hoveredNode && (
          <>
            <span className="eco-dot" style={{ background: CATEGORY[hoveredNode.category].color }} />
            <strong>{hoveredNode.label}</strong>
            <small>{hoveredNode.subtitle}</small>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * The night haze behind the map, painted by the scene itself: an opaque canvas
 * keeps its last frame when nothing moves (a transparent one can come back blank
 * while the frame loop is idle). The same gradient as the stage in ecosystem3d.css.
 */
function Backdrop() {
  const { scene } = useThree();
  useEffect(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 512;
    canvas.height = 512;
    const ctx = canvas.getContext("2d");
    if (!ctx) { scene.background = new THREE.Color(BACKGROUND); return; }
    const base = ctx.createRadialGradient(256, 205, 0, 256, 205, 400);
    base.addColorStop(0, "#1c283d");
    base.addColorStop(0.5, BACKGROUND);
    base.addColorStop(1, "#0c1220");
    ctx.fillStyle = base;
    ctx.fillRect(0, 0, 512, 512);
    const glow = ctx.createRadialGradient(256, 195, 0, 256, 195, 230);
    glow.addColorStop(0, "rgba(160, 182, 226, 0.12)");
    glow.addColorStop(1, "rgba(160, 182, 226, 0)");
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, 512, 512);
    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    scene.background = texture;
    return () => {
      if (scene.background === texture) scene.background = null;
      texture.dispose();
    };
  }, [scene]);
  return null;
}

/** A studio light the glass reflects: made once, from a procedural room. */
function StudioLight() {
  const { gl, scene } = useThree();
  useEffect(() => {
    const pmrem = new THREE.PMREMGenerator(gl);
    const room = new RoomEnvironment();
    const map = pmrem.fromScene(room, 0.04).texture;
    scene.environment = map;
    scene.environmentIntensity = 0.55;
    return () => {
      if (scene.environment === map) scene.environment = null;
      map.dispose();
      pmrem.dispose();
      room.dispose();
    };
  }, [gl, scene]);
  return null;
}

/** Far, still dust: depth without cost. */
function Dust() {
  const geometry = useMemo(() => {
    const g = new THREE.BufferGeometry();
    const n = 1100;
    const p = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const u = hash(`s${i}`) * 2 - 1, theta = hash(`t${i}`) * Math.PI * 2, r = 420 + hash(`r${i}`) * 900;
      const s = Math.sqrt(1 - u * u);
      p.set([r * s * Math.cos(theta), r * u, r * s * Math.sin(theta)], i * 3);
    }
    g.setAttribute("position", new THREE.BufferAttribute(p, 3));
    return g;
  }, []);
  const material = useMemo(() => new THREE.PointsMaterial({ color: "#aebfdf", size: 1.4, sizeAttenuation: false, transparent: true,
    opacity: 0.26, depthWrite: false, fog: false }), []);
  useEffect(() => () => { geometry.dispose(); material.dispose(); }, [geometry, material]);
  return <points geometry={geometry} material={material} />;
}

interface Item {
  node: EcoNode;
  radius: number;
  phase: number;
  base: THREE.Color;
}

/** A label keeps a world size, but stays between `minPx` and `maxPx` tall on screen: readable far, not huge near. */
interface LabelEntry { sprite: THREE.Sprite; key: string; lift: number; height: number; aspect: number; minPx: number; maxPx: number }

function Graph(props: SceneProps & { onNodeDoubleClick: () => void }) {
  const { visible, positions } = props;
  const { camera, gl, invalidate, scene } = useThree();

  // --- what does not change between renders ---
  const geometries = useMemo(() => {
    const map = new Map<Shape, THREE.BufferGeometry>();
    for (const c of Object.values(CATEGORY)) if (!map.has(c.shape)) map.set(c.shape, makeGeometry(c.shape));
    return map;
  }, []);
  const shellGeometry = useMemo(() => new THREE.IcosahedronGeometry(1, 4), []);
  const floorGeometry = useMemo(() => new THREE.PlaneGeometry(1, 1), []);
  const hitGeometry = useMemo(() => new THREE.IcosahedronGeometry(1, 0), []);
  const solid = useMemo(glassMaterial, []);
  const shell = useMemo(bubbleMaterial, []);
  const floor = useMemo(floorMaterial, []);
  const hitMaterial = useMemo(() => new THREE.MeshBasicMaterial({ visible: false }), []);
  const glowMaterial = useMemo(() => pointsMaterial(0.26), []);
  const signalMaterial = useMemo(() => pointsMaterial(0.85), []);
  // Colours with alpha: an edge fades instead of darkening, and the map shows through it.
  const lineMaterial = useMemo(() => new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false }), []);
  const labels = useMemo(() => new LabelCache(), []);
  const labelGroup = useMemo(() => new THREE.Group(), []);
  useEffect(() => {
    scene.add(labelGroup);
    return () => {
      scene.remove(labelGroup);
      for (const g of geometries.values()) g.dispose();
      shellGeometry.dispose(); floorGeometry.dispose(); hitGeometry.dispose();
      solid.dispose(); shell.dispose(); floor.dispose(); hitMaterial.dispose();
      glowMaterial.dispose(); signalMaterial.dispose(); lineMaterial.dispose(); labels.dispose();
    };
  }, [scene, labelGroup, geometries, shellGeometry, floorGeometry, hitGeometry, solid, shell, floor, hitMaterial, glowMaterial,
    signalMaterial, lineMaterial, labels]);

  // --- what the view shows now ---
  const items: Item[] = useMemo(() => visible.nodes.map((node) => {
    const sub = node.category === "agent" && node.size < 1;
    return { node, radius: nodeRadius(node), phase: hash(node.id) * Math.PI * 2,
      base: new THREE.Color(sub ? SUBAGENT_COLOR : CATEGORY[node.category].color) };
  }), [visible]);
  const indexOf = useMemo(() => new Map(items.map((it, i) => [it.node.id, i])), [items]);
  const byCategory = useMemo(() => {
    const map = new Map<Category, number[]>();
    items.forEach((it, i) => (map.get(it.node.category) ?? map.set(it.node.category, []).get(it.node.category)!).push(i));
    return [...map.entries()];
  }, [items]);
  const shells = useMemo(() => items.flatMap((it, i) => (it.node.category === "agent" || it.node.isGroup ? [i] : [])), [items]);
  const edges = useMemo(() => visible.edges.map((e) => ({ edge: e, a: indexOf.get(e.source)!, b: indexOf.get(e.target)! }))
    .filter((e) => e.a !== undefined && e.b !== undefined), [visible, indexOf]);

  // --- buffers, sized by power of two so a few more nodes reuse them ---
  const nodeCapacity = capacityFor(items.length);
  const edgeCapacity = capacityFor(edges.length);
  const lineGeometry = useMemo(() => {
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.BufferAttribute(new Float32Array(edgeCapacity * SEGMENTS * 6), 3).setUsage(THREE.DynamicDrawUsage));
    g.setAttribute("color", new THREE.BufferAttribute(new Float32Array(edgeCapacity * SEGMENTS * 8), 4).setUsage(THREE.DynamicDrawUsage));
    return g;
  }, [edgeCapacity]);
  const glowGeometry = useMemo(() => pointGeometry(nodeCapacity), [nodeCapacity]);
  const signalGeometry = useMemo(() => pointGeometry(MAX_PARTICLES), []);
  useEffect(() => () => lineGeometry.dispose(), [lineGeometry]);
  useEffect(() => () => glowGeometry.dispose(), [glowGeometry]);
  useEffect(() => () => signalGeometry.dispose(), [signalGeometry]);

  // The floor sits under the map, wide enough for it, and glides there when the map changes.
  const ground = useMemo(() => {
    const points = [...positions.values()];
    if (points.length === 0) return null;
    const { center, radius } = bounds(points);
    let low = Infinity;
    for (const p of points) low = Math.min(low, p[1]);
    return { x: center[0], y: low - Math.max(4, radius * 0.22), z: center[2], size: Math.max(60, radius * 3.4) };
  }, [positions]);
  const floorMesh = useRef<THREE.Mesh>(null);

  const meshes = useRef(new Map<Category, THREE.InstancedMesh>());
  const shellMesh = useRef<THREE.InstancedMesh | null>(null);
  const hitMesh = useRef<THREE.InstancedMesh>(null);

  // --- animated state, kept across renders ---
  const current = useRef(new Map<string, THREE.Vector3>());
  const grown = useRef(new Map<string, number>());
  const shown = useRef<THREE.Vector3[]>([]);
  const labelFade = useRef(new Map<string, number>());
  const colors = useRef<{ node: THREE.Color[]; glow: number[]; edge: Float32Array; dirty: boolean }>({ node: [], glow: [], edge: new Float32Array(0), dirty: true });
  const settled = useRef(false);

  // New nodes grow out of what they hang from; gone ones are forgotten. Before the next frame draws.
  useLayoutEffect(() => {
    const now = current.current;
    for (const id of [...now.keys()]) if (!indexOf.has(id)) { now.delete(id); grown.current.delete(id); labelFade.current.delete(id); }
    for (const it of items) {
      if (now.has(it.node.id)) continue;
      const from = spawnPoint(visible, it.node.id, (id) => {
        const p = now.get(id);
        return p ? [p.x, p.y, p.z] : undefined;
      }) ?? positions.get(it.node.id) ?? [0, 0, 0];
      now.set(it.node.id, new THREE.Vector3(...(props.animate ? from : positions.get(it.node.id) ?? from)));
      grown.current.set(it.node.id, props.animate ? 0 : 1);
    }
    shown.current = items.map(() => new THREE.Vector3());
    settled.current = false;
    invalidate();
  }, [items, indexOf, visible, positions, props.animate, invalidate]);

  // Colours change with the focus, the search and the data: computed once, written in the next frame.
  useLayoutEffect(() => {
    const background = new THREE.Color(BACKGROUND);
    const { focusSet, focusId, matches, selected } = props;
    const node: THREE.Color[] = [];
    const glow: number[] = [];
    items.forEach((it) => {
      const id = it.node.id;
      const c = it.base.clone();
      let g = it.node.category === "agent" ? 1 : 0.65;
      if (it.node.status === "inactive") { c.lerp(new THREE.Color("#7a8699"), 0.6); g *= 0.4; }
      if (it.node.status === "warning") g *= 1.2;
      const dimmed = (focusSet && !focusSet.has(id)) || (!focusSet && matches && !matches.has(id));
      if (dimmed) { c.lerp(background, 0.72); g *= 0.1; }
      if (id === selected || id === focusId) { c.lerp(WHITE, 0.18); g *= 1.7; }
      if (matches?.has(id) && !dimmed) g *= 1.4;
      node.push(c);
      glow.push(g);
    });
    const edge = new Float32Array(edges.length * 2);
    edges.forEach(({ edge: e }, i) => {
      // The edge's opacity: owner to subagent strongest, membership faintest; focus and search lift theirs.
      let k = e.kind === "subagent" ? 0.62 : e.kind === "member" || e.kind === "offers" ? 0.16 : 0.26;
      if (focusSet) {
        if (e.source === focusId || e.target === focusId) k = 0.85;
        else if (focusSet.has(e.source) && focusSet.has(e.target)) k = 0.22;
        else k = 0.035;
      } else if (matches) {
        k = matches.has(e.source) || matches.has(e.target) ? 0.5 : 0.05;
      }
      edge[i * 2] = k;
      edge[i * 2 + 1] = k;
    });
    colors.current = { node, glow, edge, dirty: true };
    invalidate();
  }, [items, edges, props.focusSet, props.focusId, props.matches, props.selected, invalidate]); // eslint-disable-line react-hooks/exhaustive-deps

  // Signals: one per edge, more along the focused node's edges.
  const signals = useMemo(() => {
    const list: { edge: number; phase: number; speed: number }[] = [];
    edges.forEach(({ edge: e }, i) => {
      const focused = props.focusId !== null && (e.source === props.focusId || e.target === props.focusId);
      const copies = focused ? 3 : props.focusSet ? 0 : e.kind === "subagent" ? 2 : 1;
      for (let k = 0; k < copies; k++) list.push({ edge: i, phase: hash(`${e.id}#${k}`), speed: 0.1 + hash(`${e.id}$${k}`) * 0.14 });
    });
    return list.slice(0, MAX_PARTICLES);
  }, [edges, props.focusId, props.focusSet]);

  // Labels: agents, clusters, what is in focus, the search hits; all of them on demand or when the scene is small.
  const labelled = useMemo(() => {
    const few = items.length <= 90;
    const chosen = items.filter((it) => props.labels === "all" || few || it.node.category === "agent" || it.node.isGroup
      || props.focusSet?.has(it.node.id) || props.matches?.has(it.node.id));
    return chosen.slice(0, MAX_LABELS);
  }, [items, props.labels, props.focusSet, props.matches]);
  const labelSprites = useRef(new Map<string, LabelEntry>());
  useEffect(() => {
    const live = labelSprites.current;
    const wanted = new Map(labelled.map((it) => [it.node.id, it]));
    for (const [id, entry] of live) {
      if (wanted.has(id) && entry.key === labelKey(wanted.get(id)!.node, props.visible)) continue;
      labelGroup.remove(entry.sprite);
      entry.sprite.material.dispose();
      labels.release(entry.key);
      live.delete(id);
    }
    for (const [id, it] of wanted) {
      if (live.has(id)) continue;
      const key = labelKey(it.node, props.visible);
      const texture = labels.acquire(key, () => labelText(it.node, props.visible), CATEGORY[it.node.category].color);
      if (!texture) continue;
      const material = new THREE.SpriteMaterial({ map: texture.texture, transparent: true, depthTest: false, depthWrite: false, fog: false });
      const sprite = new THREE.Sprite(material);
      const height = it.node.category === "agent" ? 1.0 : it.node.isGroup ? 0.78 : 0.6;
      sprite.scale.set(height * texture.aspect, height, 1);
      sprite.center.set(0.5, 0);
      sprite.renderOrder = 10;
      labelGroup.add(sprite);
      live.set(id, { sprite, key, lift: it.radius + 0.3, height, aspect: texture.aspect,
        minPx: it.node.category === "agent" ? 16 : it.node.isGroup ? 14 : 13,
        maxPx: it.node.category === "agent" ? 24 : it.node.isGroup ? 20 : 18 });
    }
    invalidate();
  }, [labelled, labelGroup, labels, props.visible, invalidate]);
  useEffect(() => () => {
    for (const entry of labelSprites.current.values()) { entry.sprite.material.dispose(); labels.release(entry.key); }
    labelSprites.current.clear();
  }, [labels]);

  // --- camera: orbit, pan, zoom towards the pointer; and a goal it glides to when asked ---
  const controls = useMemo(() => new OrbitControls(camera, gl.domElement), [camera, gl]);
  const goal = useRef<Goal | null>(null);
  useEffect(() => {
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.rotateSpeed = 0.6;
    controls.zoomSpeed = 0.9;
    controls.screenSpacePanning = true;
    controls.zoomToCursor = true;
    controls.minDistance = MIN_DISTANCE;
    controls.maxDistance = MAX_DISTANCE;
    controls.autoRotateSpeed = 0.35;
    const stop = () => { goal.current = null; };
    const change = () => invalidate();
    controls.addEventListener("start", stop);
    controls.addEventListener("change", change);
    return () => {
      controls.removeEventListener("start", stop);
      controls.removeEventListener("change", change);
      controls.dispose();
    };
  }, [controls, invalidate]);
  useEffect(() => { controls.autoRotate = props.autoRotate; invalidate(); }, [controls, props.autoRotate, invalidate]);

  // Two fingers to the right or left on a touchpad (or Shift + wheel): the view moves along x,
  // the map following the fingers at the depth of the target. Caught on the way down to the
  // canvas, before the orbit controls would read it as a zoom; up and down still zoom.
  useEffect(() => {
    const host = gl.domElement.parentElement ?? gl.domElement;
    const right = new THREE.Vector3();
    const onWheel = (event: WheelEvent) => {
      const dx = horizontalSwipe(event);
      if (dx === null) return;
      event.preventDefault();
      event.stopPropagation();
      const fov = THREE.MathUtils.degToRad((camera as THREE.PerspectiveCamera).fov);
      const perPixel = (2 * camera.position.distanceTo(controls.target) * Math.tan(fov / 2)) / Math.max(1, gl.domElement.clientHeight);
      right.setFromMatrixColumn(camera.matrix, 0).multiplyScalar(dx * perPixel);
      camera.position.add(right);
      controls.target.add(right);
      goal.current = null;
      controls.update();
      invalidate();
    };
    host.addEventListener("wheel", onWheel, { capture: true, passive: false });
    return () => host.removeEventListener("wheel", onWheel, { capture: true });
  }, [gl, camera, controls, invalidate]);
  // A request is answered in the frame loop, once the canvas has its size: framing needs the real aspect.
  const pending = useRef<CameraRequest | null>(null);
  const request = props.camera;
  useEffect(() => {
    if (!request) return;
    pending.current = request;
    invalidate();
  }, [request?.seq]); // eslint-disable-line react-hooks/exhaustive-deps

  // --- the one frame loop ---
  const matrix = useMemo(() => new THREE.Matrix4(), []);
  const quaternion = useMemo(() => new THREE.Quaternion(), []);
  const euler = useMemo(() => new THREE.Euler(), []);
  const scaleVector = useMemo(() => new THREE.Vector3(), []);
  const temp = useMemo(() => ({ a: new THREE.Vector3(), b: new THREE.Vector3(), c: new THREE.Vector3(), d: new THREE.Vector3(), color: new THREE.Color() }), []);

  useFrame((state, delta) => {
    const dt = Math.min(delta, 0.1);
    const t = state.clock.elapsedTime;
    const animate = props.animate;
    const k = animate ? 1 - Math.exp(-dt * 4.5) : 1;
    let moving = props.autoRotate;
    // Point sizes are in world units: pixels per unit at distance 1, for this viewport.
    const perUnit = (state.size.height * state.viewport.dpr) / (2 * Math.tan(THREE.MathUtils.degToRad((camera as THREE.PerspectiveCamera).fov) / 2));
    glowMaterial.uniforms.uScale.value = perUnit;
    signalMaterial.uniforms.uScale.value = perUnit;

    // Positions: glide to the layout, grow in, float gently.
    items.forEach((it, i) => {
      const id = it.node.id;
      const p = current.current.get(id);
      const target = positions.get(id);
      if (!p || !target) return;
      temp.a.set(target[0], target[1], target[2]);
      if (p.distanceToSquared(temp.a) > 1e-5) { p.lerp(temp.a, k); moving = true; }
      const g = grown.current.get(id) ?? 1;
      if (g < 0.999) { grown.current.set(id, g + (1 - g) * k); moving = true; } else grown.current.set(id, 1);
      const out = shown.current[i];
      if (!out) return;
      out.copy(p);
      if (animate) {
        const amp = it.node.category === "agent" ? 0.1 : 0.16;
        out.x += Math.sin(t * 0.5 + it.phase) * amp;
        out.y += Math.sin(t * 0.7 + it.phase * 1.3) * amp;
        out.z += Math.cos(t * 0.45 + it.phase) * amp;
      }
    });

    // The floor.
    const fm = floorMesh.current;
    if (fm && ground) {
      temp.a.set(ground.x, ground.y, ground.z);
      if (fm.position.distanceToSquared(temp.a) > 1e-4 || Math.abs(fm.scale.x - ground.size) > 0.01) {
        const kf = fm.scale.x === 1 ? 1 : k;
        fm.position.lerp(temp.a, kf);
        const size = fm.scale.x + (ground.size - fm.scale.x) * kf;
        fm.scale.set(size, size, 1);
        moving = true;
      }
    }

    const paint = colors.current.dirty;
    // Shapes, one instanced mesh per category.
    for (const [category, indices] of byCategory) {
      const mesh = meshes.current.get(category);
      if (!mesh) continue;
      indices.forEach((i, slot) => {
        const it = items[i];
        if (!shown.current[i]) return;
        const s = it.radius * (grown.current.get(it.node.id) ?? 1)
          * (it.node.id === props.selected ? 1.22 + (animate ? Math.sin(t * 3) * 0.05 : 0) : it.node.id === props.focusId ? 1.12 : 1)
          * (props.matches?.has(it.node.id) && animate ? 1 + Math.sin(t * 4 + it.phase) * 0.08 : 1);
        euler.set(animate ? Math.sin(t * 0.3 + it.phase) * 0.35 : 0, (animate ? t * 0.3 : 0) + it.phase, 0);
        quaternion.setFromEuler(euler);
        matrix.compose(shown.current[i], quaternion, scaleVector.setScalar(s));
        mesh.setMatrixAt(slot, matrix);
        if (paint) mesh.setColorAt(slot, colors.current.node[i] ?? it.base);
      });
      mesh.count = indices.length;
      mesh.instanceMatrix.needsUpdate = true;
      if (paint && mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    }

    // The bubbles of agents and clusters.
    const shellM = shellMesh.current;
    if (shellM) {
      shells.forEach((i, slot) => {
        const it = items[i];
        if (!shown.current[i]) return;
        const s = it.radius * 1.6 * (grown.current.get(it.node.id) ?? 1);
        euler.set(0, it.phase, 0);
        quaternion.setFromEuler(euler);
        matrix.compose(shown.current[i], quaternion, scaleVector.setScalar(s));
        shellM.setMatrixAt(slot, matrix);
        if (paint) shellM.setColorAt(slot, colors.current.node[i] ?? it.base);
      });
      shellM.count = shells.length;
      shellM.instanceMatrix.needsUpdate = true;
      if (paint && shellM.instanceColor) shellM.instanceColor.needsUpdate = true;
    }

    // Hit spheres: generous, so small nodes are easy to point at.
    const hit = hitMesh.current;
    if (hit) {
      items.forEach((it, i) => {
        if (!shown.current[i]) return;
        matrix.compose(shown.current[i], quaternion.identity(), scaleVector.setScalar(Math.max(it.radius * 1.35, 0.75)));
        hit.setMatrixAt(i, matrix);
      });
      hit.count = items.length;
      hit.instanceMatrix.needsUpdate = true;
      hit.boundingSphere = null;
    }

    // Edges: a gentle curve towards the centre, from owner to owned; colour and alpha per vertex.
    const linePos = lineGeometry.getAttribute("position") as THREE.BufferAttribute;
    const lineCol = lineGeometry.getAttribute("color") as THREE.BufferAttribute;
    const lp = linePos.array as Float32Array;
    const lc = lineCol.array as Float32Array;
    edges.forEach(({ a, b }, e) => {
      const pa = shown.current[a], pb = shown.current[b];
      if (!pa || !pb) return;
      control(pa, pb, temp.c);
      let o = e * SEGMENTS * 6;
      let oc = e * SEGMENTS * 8;
      for (let s = 0; s < SEGMENTS; s++) {
        bezier(pa, temp.c, pb, s / SEGMENTS, temp.a);
        bezier(pa, temp.c, pb, (s + 1) / SEGMENTS, temp.b);
        lp[o] = temp.a.x; lp[o + 1] = temp.a.y; lp[o + 2] = temp.a.z;
        lp[o + 3] = temp.b.x; lp[o + 4] = temp.b.y; lp[o + 5] = temp.b.z;
        if (paint) {
          const ka = colors.current.edge[e * 2] ?? 0.25;
          const ca = colors.current.node[a] ?? items[a].base, cb = colors.current.node[b] ?? items[b].base;
          for (const [offset, u] of [[0, s / SEGMENTS], [4, (s + 1) / SEGMENTS]] as const) {
            temp.color.copy(ca).lerp(cb, u).lerp(WHITE, 0.2);
            lc[oc + offset] = temp.color.r; lc[oc + offset + 1] = temp.color.g; lc[oc + offset + 2] = temp.color.b;
            // A little fainter at the ends, where the edge meets the glass.
            lc[oc + offset + 3] = ka * (0.6 + 0.4 * Math.sin(Math.PI * u));
          }
        }
        o += 6;
        oc += 8;
      }
    });
    lineGeometry.setDrawRange(0, edges.length * SEGMENTS * 2);
    linePos.needsUpdate = true;
    if (paint) lineCol.needsUpdate = true;

    // Glows: a soft halo, not a flare.
    const gp = glowGeometry.getAttribute("position") as THREE.BufferAttribute;
    const gc = glowGeometry.getAttribute("color") as THREE.BufferAttribute;
    const gs = glowGeometry.getAttribute("size") as THREE.BufferAttribute;
    items.forEach((it, i) => {
      const p = shown.current[i];
      if (!p) return;
      gp.setXYZ(i, p.x, p.y, p.z);
      const g = (colors.current.glow[i] ?? 0.6) * (grown.current.get(it.node.id) ?? 1);
      const c = colors.current.node[i] ?? it.base;
      gc.setXYZ(i, c.r * g, c.g * g, c.b * g);
      gs.setX(i, it.radius * (it.node.category === "agent" ? 3.0 : 2.6));
    });
    glowGeometry.setDrawRange(0, items.length);
    gp.needsUpdate = true; gc.needsUpdate = true; gs.needsUpdate = true;

    // Signals running along the edges.
    const sp = signalGeometry.getAttribute("position") as THREE.BufferAttribute;
    const sc = signalGeometry.getAttribute("color") as THREE.BufferAttribute;
    const ss = signalGeometry.getAttribute("size") as THREE.BufferAttribute;
    let count = 0;
    if (animate) {
      for (const signal of signals) {
        const link = edges[signal.edge];
        if (!link) continue;
        const pa = shown.current[link.a], pb = shown.current[link.b];
        if (!pa || !pb) continue;
        const u = (t * signal.speed + signal.phase) % 1;
        control(pa, pb, temp.c);
        bezier(pa, temp.c, pb, u, temp.a);
        sp.setXYZ(count, temp.a.x, temp.a.y, temp.a.z);
        const c = colors.current.node[link.b] ?? items[link.b].base;
        const fade = Math.sin(u * Math.PI) * Math.min(1, (colors.current.edge[signal.edge * 2] ?? 0.25) * 2.4);
        sc.setXYZ(count, c.r * fade, c.g * fade, c.b * fade);
        ss.setX(count, 0.34);
        count++;
      }
    }
    signalGeometry.setDrawRange(0, count);
    sp.needsUpdate = true; sc.needsUpdate = true; ss.needsUpdate = true;

    // Labels above their nodes; those outside the focus or the search step back.
    for (const [id, entry] of labelSprites.current) {
      const i = indexOf.get(id);
      if (i === undefined || !shown.current[i]) continue;
      const g = grown.current.get(id) ?? 1;
      entry.sprite.position.copy(shown.current[i]);
      entry.sprite.position.y += entry.lift * g;
      const back = props.focusSet ? !props.focusSet.has(id) : props.matches ? !props.matches.has(id) : false;
      const want = back ? 0.16 : 1;
      const was = labelFade.current.get(id) ?? want;
      const now = Math.abs(want - was) < 0.01 ? want : was + (want - was) * k;
      if (now !== want) moving = true;
      labelFade.current.set(id, now);
      entry.sprite.material.opacity = g * now;
      const pxToWorld = (state.viewport.dpr * camera.position.distanceTo(entry.sprite.position)) / perUnit;
      const h = Math.min(Math.max(entry.height, entry.minPx * pxToWorld), entry.maxPx * pxToWorld);
      entry.sprite.scale.set(h * entry.aspect, h, 1);
    }

    if (paint) colors.current.dirty = false;

    // The camera's goal.
    const asked = pending.current;
    if (asked && state.size.width > 0 && state.size.height > 0) {
      pending.current = null;
      const next = answer(asked, goal.current, camera as THREE.PerspectiveCamera, controls.target,
        state.size.width / state.size.height, positions, items);
      if (next) goal.current = next;
      const instant = (asked.kind === "fit" || asked.kind === "focus") && asked.instant;
      if (goal.current && (instant || !animate)) {
        const dir = (goal.current.direction ?? offsetDirection(camera.position, controls.target, temp.d)).clone();
        controls.target.copy(goal.current.target);
        camera.position.copy(dir.multiplyScalar(goal.current.distance).add(controls.target));
        goal.current = null;
      }
      moving = true;
    }
    if (goal.current) {
      const kc = 1 - Math.exp(-dt * 3.6);
      const want = goal.current;
      controls.target.lerp(want.target, kc);
      const distance = camera.position.distanceTo(controls.target);
      const dir = offsetDirection(camera.position, controls.target, temp.d);
      if (want.direction) dir.lerp(want.direction, kc).normalize();
      const nextDistance = distance + (want.distance - distance) * kc;
      camera.position.copy(dir).multiplyScalar(nextDistance).add(controls.target);
      if (Math.abs(nextDistance - want.distance) < 0.03 && controls.target.distanceTo(want.target) < 0.03
          && (!want.direction || dir.angleTo(want.direction) < 0.002)) goal.current = null;
      moving = true;
    }
    controls.update();
    if (!animate && (moving || !settled.current)) {
      settled.current = !moving;
      invalidate();
    }
  });

  // --- pointer ---
  const idAt = (event: { instanceId?: number }) =>
    event.instanceId === undefined ? null : items[event.instanceId]?.node.id ?? null;
  const hoveredRef = useRef<string | null>(null);

  return (
    <>
      <mesh ref={floorMesh} geometry={floorGeometry} material={floor} rotation-x={-Math.PI / 2} renderOrder={-1}
            visible={ground !== null} raycast={() => null} />
      {byCategory.map(([category, indices]) => (
        <instancedMesh key={`${category}:${capacityFor(indices.length)}`} frustumCulled={false}
                       args={[geometries.get(CATEGORY[category].shape)!, solid, capacityFor(indices.length)]}
                       ref={(mesh: THREE.InstancedMesh | null) => {
                         if (mesh) { meshes.current.set(category, mesh); colors.current.dirty = true; } else meshes.current.delete(category);
                       }} />
      ))}
      {shells.length > 0 && (
        <instancedMesh key={`shell:${capacityFor(shells.length)}`} frustumCulled={false} renderOrder={2}
                       args={[shellGeometry, shell, capacityFor(shells.length)]}
                       ref={(mesh: THREE.InstancedMesh | null) => { shellMesh.current = mesh; colors.current.dirty = true; }} />
      )}
      <instancedMesh key={`hit:${nodeCapacity}`} ref={hitMesh} frustumCulled={false} args={[hitGeometry, hitMaterial, nodeCapacity]}
                     onPointerOver={(e) => {
                       e.stopPropagation();
                       const id = idAt(e);
                       if (id && id !== hoveredRef.current) { hoveredRef.current = id; props.onHover(id); }
                     }}
                     onPointerOut={(e) => {
                       const id = idAt(e);
                       if (id && id === hoveredRef.current) { hoveredRef.current = null; props.onHover(null); }
                     }}
                     onClick={(e) => { e.stopPropagation(); const id = idAt(e); if (id) props.onSelect(id); }}
                     onDoubleClick={(e) => {
                       e.stopPropagation();
                       props.onNodeDoubleClick();
                       const id = idAt(e);
                       if (id) props.onActivate(id);
                     }} />
      <lineSegments geometry={lineGeometry} material={lineMaterial} frustumCulled={false} renderOrder={1} />
      <points geometry={glowGeometry} material={glowMaterial} frustumCulled={false} renderOrder={3} />
      <points geometry={signalGeometry} material={signalMaterial} frustumCulled={false} renderOrder={3} />
    </>
  );
}

interface Goal { target: THREE.Vector3; distance: number; direction?: THREE.Vector3 }

/** The unit vector from the target to the camera, into `out`; a sane one when they coincide. */
function offsetDirection(position: THREE.Vector3, target: THREE.Vector3, out: THREE.Vector3): THREE.Vector3 {
  out.copy(position).sub(target);
  if (out.lengthSq() < 1e-6) out.set(0, 0.35, 1);
  return out.normalize();
}

/**
 * Where a request sends the camera. Zoom, orbit and pan build on the goal in flight,
 * so pressing a key again goes further instead of starting over.
 */
function answer(asked: CameraAsk, inFlight: Goal | null, camera: THREE.PerspectiveCamera, target: THREE.Vector3, aspect: number,
                positions: Map<string, Vec3>, items: Item[]): Goal | null {
  const dir = inFlight?.direction?.clone() ?? offsetDirection(camera.position, target, new THREE.Vector3());
  const distance = inFlight?.distance ?? camera.position.distanceTo(target);
  const center = inFlight?.target.clone() ?? target.clone();
  switch (asked.kind) {
    case "fit": {
      const from = asked.view ? VIEW_DIRECTION[asked.view] : undefined;
      return { ...framing(camera, aspect, [...positions.values()], from), direction: from?.clone() };
    }
    case "focus": {
      const p = positions.get(asked.id);
      if (!p) return null;
      const node = items.find((it) => it.node.id === asked.id)?.node;
      return { target: new THREE.Vector3(...p), distance: node?.category === "agent" ? 18 : node?.isGroup ? 14 : 9,
        direction: inFlight?.direction };
    }
    case "zoom":
      return { target: center, distance: THREE.MathUtils.clamp(distance * asked.factor, MIN_DISTANCE, MAX_DISTANCE),
        direction: inFlight?.direction };
    case "orbit": {
      const s = new THREE.Spherical().setFromVector3(dir);
      s.theta += asked.azimuth;
      s.phi = THREE.MathUtils.clamp(s.phi + asked.polar, 0.04, Math.PI - 0.04);
      return { target: center, distance, direction: new THREE.Vector3().setFromSpherical(s).normalize() };
    }
    case "pan": {
      const right = new THREE.Vector3(0, 1, 0).cross(dir);
      if (right.lengthSq() < 1e-6) right.set(1, 0, 0);
      right.normalize();
      const up = dir.clone().cross(right).normalize();
      return { target: center.addScaledVector(right, asked.x * distance).addScaledVector(up, asked.y * distance), distance,
        direction: inFlight?.direction };
    }
  }
}

/**
 * Where the camera goes to show the map, looking the way it looks now (or from the
 * side it is asked to): every node projected on the view plane, the target in the
 * middle of what is there, the distance at which nineteen in twenty fit -- a far
 * straggler does not shrink the rest.
 */
function framing(camera: THREE.PerspectiveCamera, aspect: number, points: Vec3[], from?: THREE.Vector3): { target: THREE.Vector3; distance: number } {
  if (points.length === 0) return { target: new THREE.Vector3(), distance: 40 };
  const { center, radius } = bounds(points);
  const c = new THREE.Vector3(...center);
  // The axes the camera will have once it looks at the centre.
  const back = from ? from.clone() : camera.position.clone().sub(c);
  if (back.lengthSq() < 1e-6) back.set(0, 0.35, 1);
  back.normalize();
  const right = new THREE.Vector3(0, 1, 0).cross(back);
  if (right.lengthSq() < 1e-6) right.set(1, 0, 0);
  right.normalize();
  const up = back.clone().cross(right);
  const rel = points.map((p) => new THREE.Vector3(p[0], p[1], p[2]).sub(c));
  const pick = (values: number[], q: number) => values.slice().sort((a, b) => a - b)[Math.min(values.length - 1, Math.floor(values.length * q))];
  const xs = rel.map((v) => v.dot(right)), ys = rel.map((v) => v.dot(up));
  const mx = (pick(xs, 0.03) + pick(xs, 0.97)) / 2, my = (pick(ys, 0.03) + pick(ys, 0.97)) / 2;
  const target = c.clone().addScaledVector(right, mx).addScaledVector(up, my);
  const tanV = Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2);
  const tanH = tanV * (Number.isFinite(aspect) && aspect > 0 ? aspect : 1);
  const needed = rel.map((v, i) => Math.max(Math.abs(xs[i] - mx) / tanH, Math.abs(ys[i] - my) / tanV) + v.dot(back));
  const distance = pick(needed, 0.95) * 1.08 + 2;
  // Never a camera at an impossible place: the whole sphere, from far enough, instead.
  if (!Number.isFinite(distance) || ![target.x, target.y, target.z].every(Number.isFinite)) {
    return { target: c, distance: radius / Math.sin(THREE.MathUtils.degToRad(camera.fov) / 2) };
  }
  return { target, distance: Math.max(8, distance) };
}

function pointGeometry(capacity: number): THREE.BufferGeometry {
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.BufferAttribute(new Float32Array(capacity * 3), 3).setUsage(THREE.DynamicDrawUsage));
  g.setAttribute("color", new THREE.BufferAttribute(new Float32Array(capacity * 3), 3).setUsage(THREE.DynamicDrawUsage));
  g.setAttribute("size", new THREE.BufferAttribute(new Float32Array(capacity), 1).setUsage(THREE.DynamicDrawUsage));
  g.setDrawRange(0, 0);
  return g;
}

/** The control point of an edge's curve: its middle, drawn a little towards the centre of the map. */
function control(a: THREE.Vector3, b: THREE.Vector3, out: THREE.Vector3): THREE.Vector3 {
  return out.copy(a).add(b).multiplyScalar(0.5 * 0.86);
}

function bezier(a: THREE.Vector3, c: THREE.Vector3, b: THREE.Vector3, u: number, out: THREE.Vector3): THREE.Vector3 {
  const v = 1 - u;
  return out.set(
    v * v * a.x + 2 * v * u * c.x + u * u * b.x,
    v * v * a.y + 2 * v * u * c.y + u * u * b.y,
    v * v * a.z + 2 * v * u * c.z + u * u * b.z,
  );
}

// --- labels --------------------------------------------------------------------------------

function labelText(node: EcoNode, visible: VisibleGraph): string {
  if (node.isGroup && node.detail.type === "group") return `${node.label} (${node.detail.count})`;
  const agent = agentOf(node.id);
  if (agent !== null) {
    const hidden = visible.hiddenOf.get(agent) ?? 0;
    return visible.context.has(agent) || hidden === 0 ? node.label : `${node.label}  +${hidden}`;
  }
  return node.label;
}

function labelKey(node: EcoNode, visible: VisibleGraph): string {
  return `${node.category}|${labelText(node, visible)}`;
}

/** Canvas-drawn text, one texture per distinct label, shared and counted; disposed when the last user goes. */
class LabelCache {
  private entries = new Map<string, { texture: THREE.CanvasTexture; aspect: number; users: number }>();

  acquire(key: string, text: () => string, color: string): { texture: THREE.CanvasTexture; aspect: number } | null {
    const found = this.entries.get(key);
    if (found) { found.users++; return found; }
    const canvas = document.createElement("canvas");
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    const label = text().length > 48 ? `${text().slice(0, 47)}…` : text();
    const font = "500 30px 'Geist Variable', Inter, 'Segoe UI', system-ui, sans-serif";
    ctx.font = font;
    const width = Math.ceil(ctx.measureText(label).width) + 66;
    const height = 52;
    canvas.width = width;
    canvas.height = height;
    ctx.font = font;
    // Frosted: a translucent pill, a hairline in the category's colour, light text.
    ctx.fillStyle = "rgba(20, 29, 48, 0.62)";
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;
    roundRect(ctx, 1, 1, width - 2, height - 2, (height - 2) / 2);
    ctx.fill();
    ctx.globalAlpha = 0.45;
    ctx.stroke();
    ctx.globalAlpha = 1;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(26, height / 2, 6.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#eef2f9";
    ctx.textBaseline = "middle";
    ctx.fillText(label, 42, height / 2 + 1);
    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.anisotropy = 4;
    const entry = { texture, aspect: width / height, users: 1 };
    this.entries.set(key, entry);
    return entry;
  }

  release(key: string) {
    const entry = this.entries.get(key);
    if (!entry) return;
    entry.users--;
    if (entry.users <= 0) { entry.texture.dispose(); this.entries.delete(key); }
  }

  dispose() {
    for (const entry of this.entries.values()) entry.texture.dispose();
    this.entries.clear();
  }
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

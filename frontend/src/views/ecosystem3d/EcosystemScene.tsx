// The 3D scene of the agent ecosystem (ADR-037), drawn with three.js through
// React Three Fiber. Built to stay fluid with many nodes:
//  - one instanced mesh per shape (one draw call per category), one for the
//    pointer's hit spheres, one line set for every edge, one point set for the
//    glows and one for the signals that run along the edges;
//  - everything that moves is written in a single frame loop into preallocated
//    buffers; React re-renders only when the data or the view change;
//  - every geometry, material and texture made here is disposed on unmount,
//    and the WebGL context goes with the canvas when the tab is left.
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { CATEGORY, SUBAGENT_COLOR, agentOf, type Category, type EcoNode, type Shape, type VisibleGraph } from "./ecosystemModel";
import { bounds, nodeRadius, spawnPoint, type Vec3 } from "./ecosystemLayout";

/** Frame the whole map, or fly to one node; `instant` jumps there (the first framing, and always with reduced motion). */
export type CameraRequest = { seq: number; kind: "fit"; instant?: boolean } | { seq: number; kind: "focus"; id: string; instant?: boolean };

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
}

const BACKGROUND = "#050a15";
const SEGMENTS = 8;
const MAX_PARTICLES = 1400;
const MAX_LABELS = 320;

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
    case "torus": return new THREE.TorusGeometry(0.85, 0.3, 10, 28);
    case "tetrahedron": return new THREE.TetrahedronGeometry(1.25, 0);
    case "book": return new THREE.BoxGeometry(1.25, 1.55, 0.42);
    case "knot": return new THREE.TorusKnotGeometry(0.62, 0.2, 56, 8);
    case "cone": return new THREE.ConeGeometry(0.8, 1.6, 16);
    case "dodecahedron": return new THREE.DodecahedronGeometry(1.05, 0);
    case "cube": return new THREE.BoxGeometry(1.3, 1.3, 1.3);
    case "capsule": return new THREE.CapsuleGeometry(0.5, 0.9, 6, 12);
    case "card": return new THREE.BoxGeometry(1.5, 1.0, 0.16);
    case "pyramid": return new THREE.ConeGeometry(0.95, 1.5, 4);
    case "coin": return new THREE.CylinderGeometry(0.95, 0.95, 0.3, 24).rotateX(Math.PI / 2);
    case "sphere": return new THREE.IcosahedronGeometry(0.95, 2);
  }
}

/** Instance colours also light the shape from within: the nodes glow in their own colour. */
function glowingMaterial(): THREE.MeshStandardMaterial {
  const material = new THREE.MeshStandardMaterial({ roughness: 0.35, metalness: 0.25, color: "#ffffff" });
  material.onBeforeCompile = (shader) => {
    shader.uniforms.uGlow = { value: 0.42 };
    shader.fragmentShader = `uniform float uGlow;\n${shader.fragmentShader.replace("#include <emissivemap_fragment>",
      "#include <emissivemap_fragment>\n#ifdef USE_COLOR\n\ttotalEmissiveRadiance += vColor.rgb * uGlow;\n#endif")}`;
  };
  material.customProgramCacheKey = () => "aicos-eco-glow";
  return material;
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
      }`,
    transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  });
}

const capacityFor = (n: number) => Math.max(16, 2 ** Math.ceil(Math.log2(Math.max(1, n))));

export default function EcosystemScene(props: SceneProps) {
  const tooltip = useRef<HTMLDivElement>(null);
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
    <div className="eco-canvas-wrap" onPointerMove={follow} style={{ cursor: hovered ? "pointer" : "grab" }}>
      <Canvas dpr={[1, 2]} frameloop={props.animate ? "always" : "demand"}
              camera={{ fov: 50, near: 0.1, far: 5000, position: [0, 26, 70] }}
              gl={{ antialias: true, powerPreference: "high-performance" }}
              onPointerMissed={() => props.onSelect(null)}>
        <color attach="background" args={[BACKGROUND]} />
        <fog attach="fog" args={[BACKGROUND, 90, 380]} />
        <ambientLight intensity={0.55} />
        <hemisphereLight args={["#7c9cff", "#1a0f2e", 0.6]} />
        <directionalLight position={[40, 60, 30]} intensity={1.1} />
        <pointLight position={[0, 0, 0]} intensity={60} distance={70} color="#8b5cf6" />
        <Starfield />
        <Graph {...props} onHover={onHover} />
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

/** A far, still field of stars: depth without cost. */
function Starfield() {
  const geometry = useMemo(() => {
    const g = new THREE.BufferGeometry();
    const n = 1400;
    const p = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      const u = hash(`s${i}`) * 2 - 1, theta = hash(`t${i}`) * Math.PI * 2, r = 420 + hash(`r${i}`) * 900;
      const s = Math.sqrt(1 - u * u);
      p.set([r * s * Math.cos(theta), r * u, r * s * Math.sin(theta)], i * 3);
    }
    g.setAttribute("position", new THREE.BufferAttribute(p, 3));
    return g;
  }, []);
  const material = useMemo(() => new THREE.PointsMaterial({ color: "#5b6b8f", size: 1.5, sizeAttenuation: false, transparent: true,
    opacity: 0.55, depthWrite: false, fog: false }), []);
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

function Graph(props: SceneProps) {
  const { visible, positions } = props;
  const { camera, gl, invalidate, scene } = useThree();

  // --- what does not change between renders ---
  const geometries = useMemo(() => {
    const map = new Map<Shape, THREE.BufferGeometry>();
    for (const c of Object.values(CATEGORY)) if (!map.has(c.shape)) map.set(c.shape, makeGeometry(c.shape));
    return map;
  }, []);
  const shellGeometry = useMemo(() => new THREE.IcosahedronGeometry(1, 1), []);
  const hitGeometry = useMemo(() => new THREE.IcosahedronGeometry(1, 0), []);
  const solid = useMemo(glowingMaterial, []);
  const shell = useMemo(() => new THREE.MeshBasicMaterial({ wireframe: true, transparent: true, opacity: 0.35, depthWrite: false,
    blending: THREE.AdditiveBlending, color: "#ffffff" }), []);
  const hitMaterial = useMemo(() => new THREE.MeshBasicMaterial({ visible: false }), []);
  const glowMaterial = useMemo(() => pointsMaterial(0.55), []);
  const signalMaterial = useMemo(() => pointsMaterial(1.0), []);
  const lineMaterial = useMemo(() => new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, depthWrite: false,
    blending: THREE.AdditiveBlending }), []);
  const labels = useMemo(() => new LabelCache(), []);
  const labelGroup = useMemo(() => new THREE.Group(), []);
  useEffect(() => {
    scene.add(labelGroup);
    return () => {
      scene.remove(labelGroup);
      for (const g of geometries.values()) g.dispose();
      shellGeometry.dispose(); hitGeometry.dispose(); solid.dispose(); shell.dispose(); hitMaterial.dispose();
      glowMaterial.dispose(); signalMaterial.dispose(); lineMaterial.dispose(); labels.dispose();
    };
  }, [scene, labelGroup, geometries, shellGeometry, hitGeometry, solid, shell, hitMaterial, glowMaterial, signalMaterial, lineMaterial, labels]);

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
    g.setAttribute("color", new THREE.BufferAttribute(new Float32Array(edgeCapacity * SEGMENTS * 6), 3).setUsage(THREE.DynamicDrawUsage));
    return g;
  }, [edgeCapacity]);
  const glowGeometry = useMemo(() => pointGeometry(nodeCapacity), [nodeCapacity]);
  const signalGeometry = useMemo(() => pointGeometry(MAX_PARTICLES), []);
  useEffect(() => () => lineGeometry.dispose(), [lineGeometry]);
  useEffect(() => () => glowGeometry.dispose(), [glowGeometry]);
  useEffect(() => () => signalGeometry.dispose(), [signalGeometry]);

  const meshes = useRef(new Map<Category, THREE.InstancedMesh>());
  const shellMesh = useRef<THREE.InstancedMesh | null>(null);
  const hitMesh = useRef<THREE.InstancedMesh>(null);

  // --- animated state, kept across renders ---
  const current = useRef(new Map<string, THREE.Vector3>());
  const grown = useRef(new Map<string, number>());
  const shown = useRef<THREE.Vector3[]>([]);
  const colors = useRef<{ node: THREE.Color[]; glow: number[]; edge: Float32Array; dirty: boolean }>({ node: [], glow: [], edge: new Float32Array(0), dirty: true });
  const settled = useRef(false);

  // New nodes grow out of what they hang from; gone ones are forgotten. Before the next frame draws.
  useLayoutEffect(() => {
    const now = current.current;
    for (const id of [...now.keys()]) if (!indexOf.has(id)) { now.delete(id); grown.current.delete(id); }
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
    const white = new THREE.Color("#ffffff");
    const { focusSet, focusId, matches, selected } = props;
    const node: THREE.Color[] = [];
    const glow: number[] = [];
    items.forEach((it) => {
      const id = it.node.id;
      const c = it.base.clone();
      let g = it.node.category === "agent" ? 1 : 0.65;
      if (it.node.status === "inactive") { c.lerp(new THREE.Color("#64748b"), 0.65); g *= 0.4; }
      if (it.node.status === "warning") g *= 1.2;
      const dimmed = (focusSet && !focusSet.has(id)) || (!focusSet && matches && !matches.has(id));
      if (dimmed) { c.lerp(background, 0.8); g *= 0.08; }
      if (id === selected || id === focusId) { c.lerp(white, 0.22); g *= 1.6; }
      if (matches?.has(id) && !dimmed) g *= 1.4;
      node.push(c);
      glow.push(g);
    });
    const edge = new Float32Array(edges.length * 2);
    edges.forEach(({ edge: e }, i) => {
      let k = e.kind === "subagent" ? 0.9 : e.kind === "member" || e.kind === "offers" ? 0.22 : 0.34;
      if (focusSet) {
        if (e.source === focusId || e.target === focusId) k = 1.1;
        else if (focusSet.has(e.source) && focusSet.has(e.target)) k = 0.3;
        else k = 0.04;
      } else if (matches) {
        k = matches.has(e.source) || matches.has(e.target) ? 0.55 : 0.06;
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
      const copies = focused ? 4 : props.focusSet ? 0 : e.kind === "subagent" ? 2 : 1;
      for (let k = 0; k < copies; k++) list.push({ edge: i, phase: hash(`${e.id}#${k}`), speed: 0.12 + hash(`${e.id}$${k}`) * 0.16 });
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
      const height = it.node.category === "agent" ? 1.05 : it.node.isGroup ? 0.8 : 0.62;
      sprite.scale.set(height * texture.aspect, height, 1);
      sprite.center.set(0.5, 0);
      sprite.renderOrder = 10;
      labelGroup.add(sprite);
      live.set(id, { sprite, key, lift: it.radius + 0.25, height, aspect: texture.aspect,
        minPx: it.node.category === "agent" ? 15 : it.node.isGroup ? 13 : 12,
        maxPx: it.node.category === "agent" ? 24 : it.node.isGroup ? 20 : 18 });
    }
    invalidate();
  }, [labelled, labelGroup, labels, props.visible, invalidate]);
  useEffect(() => () => {
    for (const entry of labelSprites.current.values()) { entry.sprite.material.dispose(); labels.release(entry.key); }
    labelSprites.current.clear();
  }, [labels]);

  // --- camera: orbit, pan, zoom; and a goal it glides to when asked to frame or focus ---
  const controls = useMemo(() => new OrbitControls(camera, gl.domElement), [camera, gl]);
  const goal = useRef<{ target: THREE.Vector3; distance: number } | null>(null);
  useEffect(() => {
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.rotateSpeed = 0.6;
    controls.zoomSpeed = 0.9;
    controls.screenSpacePanning = true;
    controls.minDistance = 2.5;
    controls.maxDistance = 900;
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
        euler.set(animate ? Math.sin(t * 0.3 + it.phase) * 0.35 : 0, (animate ? t * 0.35 : 0) + it.phase, 0);
        quaternion.setFromEuler(euler);
        matrix.compose(shown.current[i], quaternion, scaleVector.setScalar(s));
        mesh.setMatrixAt(slot, matrix);
        if (paint) mesh.setColorAt(slot, colors.current.node[i] ?? it.base);
      });
      mesh.count = indices.length;
      mesh.instanceMatrix.needsUpdate = true;
      if (paint && mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    }

    // The wire shells of agents and clusters, turning slowly.
    const shellM = shellMesh.current;
    if (shellM) {
      shells.forEach((i, slot) => {
        const it = items[i];
        if (!shown.current[i]) return;
        const s = it.radius * 1.55 * (grown.current.get(it.node.id) ?? 1);
        euler.set(animate ? t * 0.12 : 0, animate ? -t * 0.18 + it.phase : it.phase, 0);
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

    // Edges: a gentle curve towards the centre, from owner to owned.
    const linePos = lineGeometry.getAttribute("position") as THREE.BufferAttribute;
    const lineCol = lineGeometry.getAttribute("color") as THREE.BufferAttribute;
    const lp = linePos.array as Float32Array;
    const lc = lineCol.array as Float32Array;
    edges.forEach(({ a, b }, e) => {
      const pa = shown.current[a], pb = shown.current[b];
      if (!pa || !pb) return;
      control(pa, pb, temp.c);
      let o = e * SEGMENTS * 6;
      for (let s = 0; s < SEGMENTS; s++) {
        bezier(pa, temp.c, pb, s / SEGMENTS, temp.a);
        bezier(pa, temp.c, pb, (s + 1) / SEGMENTS, temp.b);
        lp[o] = temp.a.x; lp[o + 1] = temp.a.y; lp[o + 2] = temp.a.z;
        lp[o + 3] = temp.b.x; lp[o + 4] = temp.b.y; lp[o + 5] = temp.b.z;
        if (paint) {
          const ka = colors.current.edge[e * 2] ?? 0.3;
          const ca = colors.current.node[a] ?? items[a].base, cb = colors.current.node[b] ?? items[b].base;
          for (const [offset, u] of [[0, s / SEGMENTS], [3, (s + 1) / SEGMENTS]] as const) {
            temp.color.copy(ca).lerp(cb, u).multiplyScalar(ka);
            lc[o + offset] = temp.color.r; lc[o + offset + 1] = temp.color.g; lc[o + offset + 2] = temp.color.b;
          }
        }
        o += 6;
      }
    });
    lineGeometry.setDrawRange(0, edges.length * SEGMENTS * 2);
    linePos.needsUpdate = true;
    if (paint) lineCol.needsUpdate = true;

    // Glows.
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
      gs.setX(i, it.radius * (it.node.category === "agent" ? 3.4 : 3.2));
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
        const fade = Math.sin(u * Math.PI) * Math.min(1, (colors.current.edge[signal.edge * 2] ?? 0.3) * 2.2);
        sc.setXYZ(count, c.r * fade, c.g * fade, c.b * fade);
        ss.setX(count, 0.42);
        count++;
      }
    }
    signalGeometry.setDrawRange(0, count);
    sp.needsUpdate = true; sc.needsUpdate = true; ss.needsUpdate = true;

    // Labels above their nodes.
    for (const [id, entry] of labelSprites.current) {
      const i = indexOf.get(id);
      if (i === undefined || !shown.current[i]) continue;
      const g = grown.current.get(id) ?? 1;
      entry.sprite.position.copy(shown.current[i]);
      entry.sprite.position.y += entry.lift * g;
      entry.sprite.material.opacity = g;
      const pxToWorld = (state.viewport.dpr * camera.position.distanceTo(entry.sprite.position)) / perUnit;
      const h = Math.min(Math.max(entry.height, entry.minPx * pxToWorld), entry.maxPx * pxToWorld);
      entry.sprite.scale.set(h * entry.aspect, h, 1);
    }

    if (paint) colors.current.dirty = false;

    // The camera's goal.
    const asked = pending.current;
    if (asked && state.size.width > 0 && state.size.height > 0) {
      pending.current = null;
      if (asked.kind === "fit") {
        goal.current = framing(camera as THREE.PerspectiveCamera, state.size.width / state.size.height, [...positions.values()]);
      } else {
        const p = positions.get(asked.id);
        const node = items.find((it) => it.node.id === asked.id)?.node;
        if (p) goal.current = { target: new THREE.Vector3(...p), distance: node?.category === "agent" ? 18 : node?.isGroup ? 14 : 9 };
      }
      if (goal.current && (asked.instant || !animate)) {
        temp.d.copy(camera.position).sub(controls.target);
        if (temp.d.lengthSq() < 1e-6) temp.d.set(0, 0.35, 1);
        controls.target.copy(goal.current.target);
        camera.position.copy(temp.d.normalize().multiplyScalar(goal.current.distance).add(controls.target));
        goal.current = null;
      }
      moving = true;
    }
    if (goal.current) {
      const kc = 1 - Math.exp(-dt * 3.2);
      controls.target.lerp(goal.current.target, kc);
      temp.d.copy(camera.position).sub(controls.target);
      if (temp.d.lengthSq() < 1e-6) temp.d.set(0, 0.35, 1);
      temp.d.normalize().multiplyScalar(goal.current.distance).add(controls.target);
      camera.position.lerp(temp.d, kc);
      if (camera.position.distanceTo(temp.d) < 0.05 && controls.target.distanceTo(goal.current.target) < 0.05) goal.current = null;
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
      {byCategory.map(([category, indices]) => (
        <instancedMesh key={`${category}:${capacityFor(indices.length)}`} frustumCulled={false}
                       args={[geometries.get(CATEGORY[category].shape)!, solid, capacityFor(indices.length)]}
                       ref={(mesh: THREE.InstancedMesh | null) => {
                         if (mesh) { meshes.current.set(category, mesh); colors.current.dirty = true; } else meshes.current.delete(category);
                       }} />
      ))}
      {shells.length > 0 && (
        <instancedMesh key={`shell:${capacityFor(shells.length)}`} frustumCulled={false}
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
                     onDoubleClick={(e) => { e.stopPropagation(); const id = idAt(e); if (id) props.onActivate(id); }} />
      <lineSegments geometry={lineGeometry} material={lineMaterial} frustumCulled={false} />
      <points geometry={glowGeometry} material={glowMaterial} frustumCulled={false} />
      <points geometry={signalGeometry} material={signalMaterial} frustumCulled={false} />
    </>
  );
}

/**
 * Where the camera goes to show the map, looking the way it looks now: every node
 * projected on the view plane, the target in the middle of what is there, the
 * distance at which nineteen in twenty fit -- a far straggler does not shrink the rest.
 */
function framing(camera: THREE.PerspectiveCamera, aspect: number, points: Vec3[]): { target: THREE.Vector3; distance: number } {
  if (points.length === 0) return { target: new THREE.Vector3(), distance: 40 };
  const { center, radius } = bounds(points);
  const c = new THREE.Vector3(...center);
  // The axes the camera will have once it looks at the centre from where it is.
  const back = camera.position.clone().sub(c);
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
    const font = "600 30px Inter, 'Segoe UI', system-ui, sans-serif";
    ctx.font = font;
    const width = Math.ceil(ctx.measureText(label).width) + 64;
    const height = 52;
    canvas.width = width;
    canvas.height = height;
    ctx.font = font;
    ctx.fillStyle = "rgba(7, 12, 24, 0.82)";
    ctx.strokeStyle = color;
    ctx.globalAlpha = 1;
    ctx.lineWidth = 2;
    roundRect(ctx, 1, 1, width - 2, height - 2, 14);
    ctx.fill();
    ctx.globalAlpha = 0.7;
    ctx.stroke();
    ctx.globalAlpha = 1;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(24, height / 2, 7, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#e7ecf7";
    ctx.textBaseline = "middle";
    ctx.fillText(label, 40, height / 2 + 1);
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

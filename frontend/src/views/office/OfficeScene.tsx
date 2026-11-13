// The office: one room, desks in pods of four, a robot at each desk for each agent.
// Geometry only (no models to download): rounded boxes, capsules and spheres in the
// console's palette. A robot types and its screen scrolls when its agent has a task in
// progress; it waits, eyes dimmed, when it has none; it sleeps when the agent is off.
import { useEffect, useMemo, useRef, useState, type MutableRefObject } from "react";
import * as THREE from "three";
import { Canvas, useFrame, useThree, type ThreeEvent } from "@react-three/fiber";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";

export type DeskState = "working" | "waiting" | "idle" | "off";

export interface DeskAgent {
  id: number;
  name: string;
  role: string;
  state: DeskState;
  task: string | null;
}

export type OfficeView = "overview" | "top";

const ACCENT = new THREE.Color("#3a8dff");
const EYE: Record<DeskState, string> = { working: "#5fb0ff", waiting: "#f2b84b", idle: "#8d96a9", off: "#1a2030" };
const PER_POD = 4;
// Desk slots around a pod's centre: the robot sits outside, facing the screen and the centre.
const SLOTS: { x: number; z: number; rot: number }[] = [
  { x: -1.35, z: -1.05, rot: 0 }, { x: 1.35, z: -1.05, rot: 0 },
  { x: -1.35, z: 1.05, rot: Math.PI }, { x: 1.35, z: 1.05, rot: Math.PI },
];

function layout(count: number) {
  const pods = Math.max(1, Math.ceil(count / PER_POD));
  const cols = Math.ceil(Math.sqrt(pods * 1.4));
  const rows = Math.ceil(pods / cols);
  const gapX = 6.4, gapZ = 6.2;
  const desks: { x: number; z: number; rot: number }[] = [];
  for (let i = 0; i < count; i++) {
    const pod = Math.floor(i / PER_POD), slot = SLOTS[i % PER_POD];
    const px = (pod % cols - (cols - 1) / 2) * gapX;
    const pz = (Math.floor(pod / cols) - (rows - 1) / 2) * gapZ;
    desks.push({ x: px + slot.x, z: pz + slot.z, rot: slot.rot });
  }
  const pods2: { x: number; z: number }[] = [];
  for (let p = 0; p < pods; p++) {
    pods2.push({ x: (p % cols - (cols - 1) / 2) * gapX, z: (Math.floor(p / cols) - (rows - 1) / 2) * gapZ });
  }
  return { desks, pods: pods2, width: cols * gapX + 4, depth: rows * gapZ + 4 };
}

/** A screen's picture: code-like lines that scroll while the agent works. */
function useScreen(agent: DeskAgent) {
  const canvas = useMemo(() => {
    const c = document.createElement("canvas");
    c.width = 256; c.height = 144;
    return c;
  }, []);
  const texture = useMemo(() => {
    const t = new THREE.CanvasTexture(canvas);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }, [canvas]);
  const paint = useRef((offset: number) => {
    void offset;
  });
  paint.current = (offset: number) => {
    const g = canvas.getContext("2d");
    if (!g) return;
    g.fillStyle = agent.state === "off" ? "#030407" : "#070b14";
    g.fillRect(0, 0, 256, 144);
    if (agent.state === "off") { texture.needsUpdate = true; return; }
    g.fillStyle = "#101828";
    g.fillRect(0, 0, 256, 18);
    g.fillStyle = agent.state === "working" ? "#5fb0ff" : "#8d96a9";
    g.font = "600 11px Geist Variable, Segoe UI, sans-serif";
    g.fillText(agent.name.slice(0, 30), 8, 13);
    if (agent.state !== "working") {
      g.fillStyle = "#4a5470";
      g.font = "500 12px Geist Variable, Segoe UI, sans-serif";
      g.fillText(agent.state === "waiting" ? "In attesa di iniziare" : "In pausa", 8, 80);
      texture.needsUpdate = true;
      return;
    }
    const palette = ["#5fb0ff", "#8d96a9", "#3ccf8a", "#c3cad8", "#9a8cff"];
    for (let line = 0; line < 9; line++) {
      const seed = line + offset;
      const indent = (seed * 7) % 4;
      const width = 40 + ((seed * 53) % 150);
      g.fillStyle = palette[(seed * 3) % palette.length];
      g.globalAlpha = 0.85;
      g.fillRect(10 + indent * 12, 26 + line * 13, width, 5);
    }
    g.globalAlpha = 1;
    texture.needsUpdate = true;
  };
  useEffect(() => { paint.current(0); }, [agent.state, agent.name]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => texture.dispose(), [texture]);
  return { texture, paint };
}

function Robot({ agent, desk, selected, dark, onSelect, onHover, tagRef }: {
  agent: DeskAgent; dark: boolean; desk: { x: number; z: number; rot: number }; selected: boolean;
  onSelect: (id: number) => void; onHover: (id: number | null) => void; tagRef: (el: THREE.Object3D | null) => void;
}) {
  const root = useRef<THREE.Group>(null);
  const head = useRef<THREE.Group>(null);
  const armL = useRef<THREE.Mesh>(null);
  const armR = useRef<THREE.Mesh>(null);
  const eyes = useRef<THREE.MeshStandardMaterial>(null);
  const ring = useRef<THREE.Mesh>(null);
  const { texture, paint } = useScreen(agent);
  const phase = useMemo(() => (agent.id * 1.37) % (Math.PI * 2), [agent.id]);
  const scroll = useRef({ acc: 0, offset: 0 });

  const geo = useMemo(() => ({
    deskTop: new RoundedBoxGeometry(2.1, 0.08, 1.05, 2, 0.04),
    deskSide: new RoundedBoxGeometry(0.06, 0.72, 0.95, 1, 0.02),
    monitor: new RoundedBoxGeometry(1.15, 0.68, 0.06, 2, 0.03),
    stand: new THREE.CylinderGeometry(0.03, 0.03, 0.32, 10),
    keyboard: new RoundedBoxGeometry(0.6, 0.025, 0.2, 1, 0.01),
    seat: new THREE.CylinderGeometry(0.34, 0.34, 0.08, 24),
    back: new RoundedBoxGeometry(0.62, 0.55, 0.07, 2, 0.03),
    pole: new THREE.CylinderGeometry(0.04, 0.04, 0.42, 10),
    body: new THREE.CapsuleGeometry(0.24, 0.32, 6, 18),
    head: new THREE.SphereGeometry(0.26, 28, 20),
    visor: new THREE.SphereGeometry(0.235, 28, 16, -Math.PI * 0.42, Math.PI * 0.84, Math.PI * 0.3, Math.PI * 0.38),
    eye: new THREE.SphereGeometry(0.035, 12, 10),
    arm: new THREE.CapsuleGeometry(0.055, 0.32, 4, 10),
    ring: new THREE.RingGeometry(0.62, 0.7, 48),
  }), []);
  useEffect(() => () => Object.values(geo).forEach((g) => g.dispose()), [geo]);

  useFrame((state, delta) => {
    const t = state.clock.elapsedTime + phase;
    const working = agent.state === "working";
    if (head.current) {
      head.current.rotation.y = working ? Math.sin(t * 0.7) * 0.12 : Math.sin(t * 0.35) * 0.35;
      head.current.rotation.x = agent.state === "off" ? 0.45 : working ? 0.12 + Math.sin(t * 2.1) * 0.03 : Math.sin(t * 0.5) * 0.05;
      head.current.position.y = 1.42 + Math.sin(t * 1.6) * (agent.state === "off" ? 0.004 : 0.012);
    }
    const typing = working ? Math.sin(t * 14) * 0.09 : 0;
    if (armL.current) armL.current.rotation.x = 1.2 + typing;
    if (armR.current) armR.current.rotation.x = 1.2 - typing;
    if (eyes.current) {
      const blink = Math.sin(t * 0.9) > 0.985 ? 0.15 : 1;
      eyes.current.emissiveIntensity = (agent.state === "off" ? 0 : working ? 2.2 : 1.1) * blink;
    }
    if (ring.current) {
      const m = ring.current.material as THREE.MeshBasicMaterial;
      m.opacity = selected ? 0.9 : working ? 0.35 + Math.sin(t * 2) * 0.15 : 0;
    }
    if (working) {
      scroll.current.acc += delta;
      if (scroll.current.acc > 0.35) { scroll.current.acc = 0; scroll.current.offset += 1; paint.current(scroll.current.offset); }
    }
  });

  const pick = (e: ThreeEvent<MouseEvent>) => { e.stopPropagation(); onSelect(agent.id); };
  const over = (e: ThreeEvent<PointerEvent>) => { e.stopPropagation(); document.body.style.cursor = "pointer"; onHover(agent.id); };
  const out = () => { document.body.style.cursor = ""; onHover(null); };
  const shell = agent.state === "off" ? "#9aa3b5" : "#e9eef7";

  return (
    <group position={[desk.x, 0, desk.z]} rotation={[0, desk.rot, 0]}>
      {/* desk: the screen faces the robot, which sits on the outer side */}
      <mesh geometry={geo.deskTop} position={[0, 0.76, 0]} castShadow receiveShadow>
        <meshStandardMaterial color={dark ? "#2b3346" : "#dfe4ec"} roughness={0.35} metalness={0.15} />
      </mesh>
      <mesh geometry={geo.deskSide} position={[-1.0, 0.37, 0]} castShadow><meshStandardMaterial color={dark ? "#1f2635" : "#c7cdd9"} roughness={0.5} /></mesh>
      <mesh geometry={geo.deskSide} position={[1.0, 0.37, 0]} castShadow><meshStandardMaterial color={dark ? "#1f2635" : "#c7cdd9"} roughness={0.5} /></mesh>
      {/* the light strip under the desk's edge: lit while the agent works */}
      <mesh position={[0, 0.71, -0.53]}>
        <boxGeometry args={[1.9, 0.015, 0.015]} />
        <meshBasicMaterial color={agent.state === "working" ? ACCENT : "#2a3245"} toneMapped={false} />
      </mesh>
      <mesh geometry={geo.stand} position={[0, 0.95, 0.28]}><meshStandardMaterial color="#2a3245" metalness={0.6} roughness={0.3} /></mesh>
      <group position={[0, 1.24, 0.3]} rotation={[0, Math.PI, 0]}>
        <mesh geometry={geo.monitor} castShadow><meshStandardMaterial color={dark ? "#2a3245" : "#3a4356"} metalness={0.5} roughness={0.3} /></mesh>
        <mesh position={[0, 0, 0.035]}>
          <planeGeometry args={[1.06, 0.6]} />
          <meshBasicMaterial map={texture} toneMapped={false} />
        </mesh>
      </group>
      <mesh geometry={geo.keyboard} position={[0, 0.81, -0.18]}><meshStandardMaterial color="#2a3245" roughness={0.6} /></mesh>
      {/* chair */}
      <mesh geometry={geo.pole} position={[0, 0.22, -1.0]}><meshStandardMaterial color="#2a3245" metalness={0.5} /></mesh>
      <mesh geometry={geo.seat} position={[0, 0.47, -1.0]} castShadow><meshStandardMaterial color="#1d2433" roughness={0.7} /></mesh>
      <mesh geometry={geo.back} position={[0, 0.82, -1.33]} rotation={[-0.12, 0, 0]} castShadow>
        <meshStandardMaterial color="#1d2433" roughness={0.7} />
      </mesh>
      {/* selection and activity ring on the floor */}
      <mesh ref={ring} geometry={geo.ring} position={[0, 0.012, -0.95]} rotation={[-Math.PI / 2, 0, 0]}>
        <meshBasicMaterial color={ACCENT} transparent opacity={0} toneMapped={false} />
      </mesh>
      {/* the robot */}
      <group ref={root} position={[0, 0, -0.98]} onClick={pick} onPointerOver={over} onPointerOut={out}>
        <mesh geometry={geo.body} position={[0, 0.88, 0]} castShadow>
          <meshStandardMaterial color={shell} roughness={0.25} metalness={0.15} />
        </mesh>
        <mesh ref={armL} geometry={geo.arm} position={[-0.3, 0.98, 0.16]} castShadow>
          <meshStandardMaterial color={shell} roughness={0.3} />
        </mesh>
        <mesh ref={armR} geometry={geo.arm} position={[0.3, 0.98, 0.16]} castShadow>
          <meshStandardMaterial color={shell} roughness={0.3} />
        </mesh>
        <group ref={head} position={[0, 1.42, 0]}>
          <mesh geometry={geo.head} castShadow><meshStandardMaterial color={shell} roughness={0.2} metalness={0.15} /></mesh>
          <mesh geometry={geo.visor} scale={[1.08, 1.0, 1.08]}>
            <meshStandardMaterial color="#0a0f1a" roughness={0.1} metalness={0.6} />
          </mesh>
          <mesh geometry={geo.eye} position={[-0.08, 0.02, 0.245]}>
            <meshStandardMaterial ref={eyes} color={EYE[agent.state]} emissive={EYE[agent.state]} emissiveIntensity={1.5} toneMapped={false} />
          </mesh>
          <mesh geometry={geo.eye} position={[0.08, 0.02, 0.245]}>
            <meshStandardMaterial color={EYE[agent.state]} emissive={EYE[agent.state]} emissiveIntensity={1.5} toneMapped={false} />
          </mesh>
          <object3D ref={tagRef} position={[0, 0.55, 0]} />
        </group>
      </group>
    </group>
  );
}

function Plant({ x, z, scale = 1 }: { x: number; z: number; scale?: number }) {
  const leaves = useMemo(() => Array.from({ length: 9 }, (_, i) => ({
    rot: (i / 9) * Math.PI * 2, tilt: 0.35 + (i % 3) * 0.18, h: 0.55 + (i % 4) * 0.12,
  })), []);
  return (
    <group position={[x, 0, z]} scale={scale}>
      <mesh position={[0, 0.25, 0]} castShadow>
        <cylinderGeometry args={[0.26, 0.2, 0.5, 20]} />
        <meshStandardMaterial color="#d9dee8" roughness={0.4} />
      </mesh>
      {leaves.map((l, i) => (
        <mesh key={i} position={[Math.sin(l.rot) * 0.12, 0.5 + l.h * 0.5, Math.cos(l.rot) * 0.12]}
              rotation={[Math.cos(l.rot) * l.tilt, 0, -Math.sin(l.rot) * l.tilt]} scale={[0.9, l.h * 4.5, 0.35]} castShadow>
          <sphereGeometry args={[0.09, 10, 8]} />
          <meshStandardMaterial color="#2f8f5b" roughness={0.6} />
        </mesh>
      ))}
    </group>
  );
}

/** Keeps each name tag over its robot's head: positions projected to the canvas each frame. */
function Tags({ anchors, tags }: {
  anchors: MutableRefObject<Map<number, THREE.Object3D>>; tags: MutableRefObject<Map<number, HTMLElement>>;
}) {
  const { camera, size } = useThree();
  const v = useMemo(() => new THREE.Vector3(), []);
  useFrame(() => {
    for (const [id, el] of tags.current) {
      const anchor = anchors.current.get(id);
      if (!anchor) continue;
      anchor.getWorldPosition(v);
      v.project(camera);
      const visible = v.z < 1 && Math.abs(v.x) < 1.1 && Math.abs(v.y) < 1.1;
      el.style.opacity = visible ? "" : "0";
      el.style.transform = `translate(-50%, -100%) translate(${((v.x + 1) / 2) * size.width}px, ${((1 - v.y) / 2) * size.height}px)`;
    }
  });
  return null;
}

function CameraRig({ view, width, depth }: { view: OfficeView; width: number; depth: number }) {
  const { camera, gl, invalidate, size } = useThree();
  // Far enough for the whole floor to fit the frame, whatever the frame's proportions.
  const aspect = size.width / Math.max(size.height, 1);
  const extent = Math.max(depth * 1.35, (width * 1.45) / Math.max(aspect, 0.6));
  const controls = useMemo(() => new OrbitControls(camera, gl.domElement), [camera, gl]);
  const goal = useRef<THREE.Vector3 | null>(null);
  useEffect(() => {
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.maxPolarAngle = Math.PI * 0.46;
    controls.minDistance = 4;
    controls.maxDistance = extent * 2.2;
    controls.target.set(0, 0.6, 0);
    const stop = () => { goal.current = null; };
    controls.addEventListener("start", stop);
    return () => { controls.removeEventListener("start", stop); controls.dispose(); };
  }, [controls, extent]);
  useEffect(() => {
    goal.current = view === "top" ? new THREE.Vector3(0, extent * 1.25, 0.01) : new THREE.Vector3(0, extent * 0.62, extent * 0.72);
    invalidate();
  }, [view, extent, invalidate]);
  useFrame((_, delta) => {
    if (goal.current) {
      camera.position.lerp(goal.current, 1 - Math.exp(-delta * 3.5));
      if (camera.position.distanceTo(goal.current) < 0.05) goal.current = null;
    }
    controls.update();
  });
  return null;
}

export default function OfficeScene({ agents, selected, onSelect, view, dark }: {
  agents: DeskAgent[]; selected: number | null; onSelect: (id: number | null) => void; view: OfficeView; dark: boolean;
}) {
  const { desks, pods, width, depth } = useMemo(() => layout(agents.length), [agents.length]);
  const anchors = useRef(new Map<number, THREE.Object3D>());
  const tags = useRef(new Map<number, HTMLElement>());
  const extent = Math.max(width, depth);
  const [hovered, setHovered] = useState<number | null>(null);
  const ground = dark ? "#0b0f18" : "#dfe4ec";
  const wall = dark ? "#0e1320" : "#eef1f6";

  return (
    <div className="office-canvas">
      <Canvas shadows dpr={[1, 2]} camera={{ fov: 42, near: 0.1, far: 400, position: [0, extent * 0.4, extent * 0.52] }}
              gl={{ antialias: true }} onPointerMissed={() => onSelect(null)}>
        <color attach="background" args={[dark ? "#05070c" : "#e8ecf3"]} />
        <fog attach="fog" args={[dark ? "#05070c" : "#e8ecf3", extent * 1.4, extent * 3.2]} />
        <ambientLight intensity={dark ? 0.5 : 0.75} />
        <hemisphereLight args={[dark ? "#6f8fff" : "#ffffff", dark ? "#0b0f18" : "#c7cdd9", dark ? 0.55 : 0.6]} />
        <directionalLight position={[extent * 0.4, extent * 0.9, extent * 0.3]} intensity={dark ? 1.7 : 1.8} castShadow
                          shadow-mapSize-width={2048} shadow-mapSize-height={2048}
                          shadow-camera-left={-extent} shadow-camera-right={extent}
                          shadow-camera-top={extent} shadow-camera-bottom={-extent} shadow-bias={-0.0004} />
        <pointLight position={[0, 4, 0]} intensity={dark ? 18 : 6} distance={extent} color="#5fa0ff" />

        {/* floor, walls and the window band */}
        <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
          <planeGeometry args={[width + 6, depth + 6]} />
          <meshStandardMaterial color={ground} roughness={0.85} metalness={0.05} />
        </mesh>
        <mesh position={[0, 1.6, -(depth / 2 + 3)]} receiveShadow>
          <boxGeometry args={[width + 6, 3.2, 0.2]} />
          <meshStandardMaterial color={wall} roughness={0.9} />
        </mesh>
        <mesh position={[-(width / 2 + 3), 1.6, 0]} receiveShadow>
          <boxGeometry args={[0.2, 3.2, depth + 6]} />
          <meshStandardMaterial color={wall} roughness={0.9} />
        </mesh>
        <mesh position={[0, 1.9, -(depth / 2 + 2.88)]}>
          <planeGeometry args={[width + 2, 1.6]} />
          <meshBasicMaterial color={dark ? "#14306b" : "#bcd4ff"} transparent opacity={dark ? 0.55 : 0.6} toneMapped={false} />
        </mesh>
        {pods.map((p, i) => (
          <group key={i} position={[p.x, 0.005, p.z]}>
            <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
              <circleGeometry args={[2.9, 64]} />
              <meshStandardMaterial color={dark ? "#111827" : "#e9edf4"} roughness={0.8} />
            </mesh>
            <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0.004, 0]}>
              <ringGeometry args={[2.86, 2.9, 96]} />
              <meshBasicMaterial color={ACCENT} transparent opacity={dark ? 0.45 : 0.3} toneMapped={false} />
            </mesh>
          </group>
        ))}
        <Plant x={-(width / 2 + 1.8)} z={-(depth / 2 + 1.8)} scale={1.3} />
        <Plant x={width / 2 + 1.6} z={-(depth / 2 + 1.8)} scale={1.1} />
        <Plant x={-(width / 2 + 1.8)} z={depth / 2 + 1.4} />

        {agents.map((a, i) => (
          <Robot key={a.id} agent={a} desk={desks[i]} selected={selected === a.id} dark={dark} onSelect={onSelect} onHover={setHovered}
                 tagRef={(el) => { if (el) anchors.current.set(a.id, el); else anchors.current.delete(a.id); }} />
        ))}
        <Tags anchors={anchors} tags={tags} />
        <CameraRig view={view} width={width} depth={depth} />
      </Canvas>
      <div className="office-tags" aria-hidden="true">
        {agents.map((a) => (
          <button key={a.id} tabIndex={-1}
                  className={`office-tag is-${a.state}${selected === a.id ? " is-selected" : ""}${a.state === "working" || selected === a.id || hovered === a.id ? " is-shown" : ""}`}
                  ref={(el) => { if (el) tags.current.set(a.id, el); else tags.current.delete(a.id); }}
                  onClick={() => onSelect(a.id)}>
            <span className="dot" />{a.name}
          </button>
        ))}
      </div>
    </div>
  );
}

// The office: one room, desks in pods of four, a robot at each desk for each agent.
// Geometry only (no models to download): rounded boxes, capsules and spheres in the
// console's palette. A robot types and its screen scrolls when its agent has a task in
// progress; it waits, eyes dimmed, when it has none; it sleeps when the agent is off.
// The camera moves as in the other 3D views: orbit, move along the floor, zoom towards
// the pointer, two fingers sideways; and it glides where it is asked (officeCamera.ts).
import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent, type MutableRefObject } from "react";
import * as THREE from "three";
import { Canvas, useFrame, useThree, type ThreeEvent } from "@react-three/fiber";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";
import { horizontalSwipe } from "../../components/graphGestures";
import {
  MAX_POLAR, MIN_DISTANCE, TAG_HEIGHT, answer, clampToRoom, maxDistance, offsetDirection, placeTags, seat,
  type Goal, type OfficeCameraRequest, type Room, type ScreenPoint,
} from "./officeCamera";

export type DeskState = "working" | "waiting" | "idle" | "off";

export interface DeskAgent {
  id: number;
  name: string;
  role: string;
  state: DeskState;
  task: string | null;
  /** PHASE 37 (ADR-047): its latest moment, when recent: shown on its tag under the name. */
  step?: string | null;
}

export interface OfficeSceneProps {
  agents: DeskAgent[];
  selected: number | null;
  /** The last thing the camera was asked to do. */
  camera: OfficeCameraRequest | null;
  autoRotate: boolean;
  /** False with reduced motion: the camera jumps instead of gliding. */
  animate: boolean;
  dark: boolean;
  /** A click on a robot or on its name tag. */
  onSelect: (id: number) => void;
  /** A double click on a robot or on its name tag. */
  onActivate: (id: number) => void;
  /** A double click on the floor or on the walls. */
  onBackground: () => void;
}

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

function Robot({ agent, desk, selected, dark, onSelect, onActivate, onHover }: {
  agent: DeskAgent; dark: boolean; desk: { x: number; z: number; rot: number }; selected: boolean;
  onSelect: (id: number) => void; onActivate: (id: number) => void; onHover: (id: number | null) => void;
}) {
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

  // A drag that starts and ends on the same robot moved the view: it does not choose the robot.
  const pick = (e: ThreeEvent<MouseEvent>) => { e.stopPropagation(); if (e.delta <= 4) onSelect(agent.id); };
  const activate = (e: ThreeEvent<MouseEvent>) => { e.stopPropagation(); onActivate(agent.id); };
  const over = (e: ThreeEvent<PointerEvent>) => { e.stopPropagation(); onHover(agent.id); };
  const out = () => onHover(null);
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
      <group position={[0, 0, -0.98]} onClick={pick} onDoubleClick={activate} onPointerOver={over} onPointerOut={out}>
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

/**
 * The camera: orbit, move along the floor, zoom towards the pointer, and a goal it glides
 * to when asked. In the same frame, after the camera has moved, each name tag is put over
 * its robot: a tag placed before the move trails behind it while the view turns.
 */
function CameraRig({ room, request, autoRotate, animate, seats, anchors, tags, onGesture }: {
  room: Room; request: OfficeCameraRequest | null; autoRotate: boolean; animate: boolean;
  seats: Map<number, THREE.Vector3>; anchors: Map<number, THREE.Vector3>;
  tags: MutableRefObject<Map<number, HTMLElement>>; onGesture: (active: boolean) => void;
}) {
  const { camera, gl } = useThree();
  const controls = useMemo(() => new OrbitControls(camera, gl.domElement), [camera, gl]);
  const goal = useRef<Goal | null>(null);
  const gesture = useRef(onGesture);
  gesture.current = onGesture;
  useEffect(() => {
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.rotateSpeed = 0.7;
    controls.zoomSpeed = 0.9;
    // The right button moves the view along the floor, as over a plan, not up into the air.
    controls.screenSpacePanning = false;
    controls.zoomToCursor = true;
    controls.minDistance = MIN_DISTANCE;
    controls.maxPolarAngle = MAX_POLAR;
    controls.autoRotateSpeed = 0.35;
    const start = () => { goal.current = null; gesture.current(true); };
    const end = () => gesture.current(false);
    controls.addEventListener("start", start);
    controls.addEventListener("end", end);
    return () => {
      controls.removeEventListener("start", start);
      controls.removeEventListener("end", end);
      controls.dispose();
    };
  }, [controls]);
  useEffect(() => { controls.maxDistance = maxDistance(room); }, [controls, room]);
  useEffect(() => { controls.autoRotate = autoRotate; }, [controls, autoRotate]);

  // Two fingers to the right or left on a touchpad (or Shift + wheel): the view moves along x,
  // the office following the fingers at the depth of the target. Caught on the way down to
  // the canvas, before the orbit controls would read it as a zoom; up and down still zoom.
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
    };
    host.addEventListener("wheel", onWheel, { capture: true, passive: false });
    return () => host.removeEventListener("wheel", onWheel, { capture: true });
  }, [gl, camera, controls]);

  // A request is answered in the frame loop, once the canvas has its size: framing needs the real aspect.
  const pending = useRef<OfficeCameraRequest | null>(null);
  useEffect(() => { if (request) pending.current = request; }, [request?.seq]); // eslint-disable-line react-hooks/exhaustive-deps

  const temp = useMemo(() => ({ dir: new THREE.Vector3(), clamped: new THREE.Vector3() }), []);
  const placed = useMemo(() => new Map<number, ScreenPoint>(), []);
  const written = useMemo(() => new WeakMap<HTMLElement, string>(), []);

  useFrame((state, delta) => {
    const dt = Math.min(delta, 0.1);
    const cam = camera as THREE.PerspectiveCamera;
    const asked = pending.current;
    if (asked && state.size.width > 0 && state.size.height > 0) {
      pending.current = null;
      const next = answer(asked, goal.current, cam, controls.target, state.size.width / state.size.height, room, seats);
      if (next) goal.current = next;
      const instant = (asked.kind === "fit" || asked.kind === "focus") && asked.instant;
      if (goal.current && (instant || !animate)) {
        const dir = (goal.current.direction ?? offsetDirection(camera.position, controls.target, temp.dir)).clone();
        controls.target.copy(goal.current.target);
        camera.position.copy(dir.multiplyScalar(goal.current.distance).add(controls.target));
        goal.current = null;
      }
    }
    if (goal.current) {
      const k = 1 - Math.exp(-dt * 3.6);
      const want = goal.current;
      controls.target.lerp(want.target, k);
      const distance = camera.position.distanceTo(controls.target);
      const dir = offsetDirection(camera.position, controls.target, temp.dir);
      if (want.direction) dir.lerp(want.direction, k).normalize();
      const nextDistance = distance + (want.distance - distance) * k;
      camera.position.copy(dir).multiplyScalar(nextDistance).add(controls.target);
      if (Math.abs(nextDistance - want.distance) < 0.02 && controls.target.distanceTo(want.target) < 0.02
          && (!want.direction || dir.angleTo(want.direction) < 0.002)) goal.current = null;
    }
    controls.update();
    // The view stays over the office: past the edge, camera and target stop together.
    clampToRoom(temp.clamped.copy(controls.target), room).sub(controls.target);
    if (temp.clamped.lengthSq() > 0) {
      controls.target.add(temp.clamped);
      camera.position.add(temp.clamped);
    }

    // The tags, with the camera as it is in this frame. Only what changed is written.
    placeTags(camera, anchors, state.size.width, state.size.height, state.viewport.dpr, placed);
    for (const [id, el] of tags.current) {
      const at = placed.get(id);
      const css = at ? `translate(${at.x}px, ${at.y}px) translate(-50%, -100%)` : "hidden";
      if (written.get(el) === css) continue;
      written.set(el, css);
      if (at) {
        el.style.transform = css;
        delete el.dataset.behind;
      } else {
        el.dataset.behind = "";
      }
    }
  });
  return null;
}

export default function OfficeScene({ agents, selected, camera, autoRotate, animate, dark, onSelect, onActivate, onBackground }: OfficeSceneProps) {
  const { desks, pods, width, depth } = useMemo(() => layout(agents.length), [agents.length]);
  const room = useMemo<Room>(() => ({ width, depth }), [width, depth]);
  // Where each robot sits and where its tag hangs: fixed points, not the moving head.
  const seats = useMemo(() => new Map(agents.map((a, i) => [a.id, seat(desks[i])])), [agents, desks]);
  const anchors = useMemo(() => new Map(agents.map((a, i) => [a.id, seat(desks[i], TAG_HEIGHT)])), [agents, desks]);
  const tags = useRef(new Map<number, HTMLElement>());
  const extent = Math.max(width, depth);
  const ground = dark ? "#0b0f18" : "#dfe4ec";
  const wall = dark ? "#0e1320" : "#eef1f6";

  // While the view moves, the tags hold still: the robot passed under the pointer does not
  // light its tag up. What the pointer ends on counts when the move ends.
  const [hovered, setHovered] = useState<number | null>(null);
  const [moving, setMoving] = useState(false);
  const gesture = useRef(false);
  const underPointer = useRef<number | null>(null);
  const hover = useCallback((id: number | null) => {
    underPointer.current = id;
    if (!gesture.current) setHovered(id);
  }, []);
  const onGesture = useCallback((active: boolean) => {
    gesture.current = active;
    setMoving(active);
    if (!active) setHovered(underPointer.current);
  }, []);

  // The tags are labels: the pointer goes through them to the office, so a drag or a wheel
  // that starts on one still moves the view. A click that lands on one chooses its agent.
  const down = useRef<{ x: number; y: number } | null>(null);
  const tagAt = (x: number, y: number): number | null => {
    let found: number | null = null;
    for (const [id, el] of tags.current) {
      if (!el.classList.contains("is-shown") || el.dataset.behind !== undefined) continue;
      const r = el.getBoundingClientRect();
      if (r.width > 0 && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) found = id;
    }
    return found;
  };
  const clickTag = (event: ReactMouseEvent) => {
    const from = down.current;
    if (from && Math.hypot(event.clientX - from.x, event.clientY - from.y) > 4) return;
    const id = tagAt(event.clientX, event.clientY);
    if (id !== null) onSelect(id);
  };
  const doubleClickTag = (event: ReactMouseEvent) => {
    const id = tagAt(event.clientX, event.clientY);
    if (id !== null) onActivate(id);
  };

  return (
    <div className="office-canvas" style={{ cursor: moving ? "grabbing" : hovered !== null ? "pointer" : "grab" }}
         onPointerDown={(e) => { down.current = { x: e.clientX, y: e.clientY }; }} onClick={clickTag} onDoubleClick={doubleClickTag}>
      <Canvas shadows dpr={[1, 2]} camera={{ fov: 42, near: 0.1, far: 400, position: [0, extent * 0.62, extent * 0.72] }}
              gl={{ antialias: true }} onPointerMissed={(e) => { if (e.type === "dblclick") onBackground(); }}>
        <color attach="background" args={[dark ? "#05070c" : "#e8ecf3"]} />
        <fog attach="fog" args={[dark ? "#05070c" : "#e8ecf3", extent * 1.6, extent * 4]} />
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
          <Robot key={a.id} agent={a} desk={desks[i]} selected={selected === a.id} dark={dark}
                 onSelect={onSelect} onActivate={onActivate} onHover={hover} />
        ))}
        <CameraRig room={room} request={camera} autoRotate={autoRotate} animate={animate} seats={seats} anchors={anchors}
                   tags={tags} onGesture={onGesture} />
      </Canvas>
      <div className="office-tags" aria-hidden="true">
        {agents.map((a) => (
          <span key={a.id}
                className={`office-tag is-${a.state}${selected === a.id ? " is-selected" : ""}${a.state === "working" || selected === a.id || hovered === a.id ? " is-shown" : ""}`}
                ref={(el) => { if (el) tags.current.set(a.id, el); else tags.current.delete(a.id); }}>
            <span className="dot" />
            <span className="office-tag-text">{a.name}
              {/* A new step comes in with a short fade: the tag says the agent did something. */}
              {a.step && <small key={a.step} className="office-tag-step">{a.step}</small>}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}

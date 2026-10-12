import type { ProjectFileView, Software } from "../api/types";

/*
 * PHASE 33 (ADR-040): «Apri con». Which programs of the Software Hub can open a file,
 * and which one suits it best -- decided from the file alone (its name, its extension,
 * and once it is read, whether it is text), every time a new file is selected.
 *
 * The catalog says, per program, how it opens a file (`fileArgs`) and what it is the
 * right choice for (`fileTypes`: ".java" an extension, "pom.xml" a name, "*" any text).
 * The ranking, in order:
 *   1. the most precise match: the exact file name, then the extension (the longer one
 *      first: ".d.ts" before ".ts"), then "any text file";
 *   2. the more specialised program: the one that declares fewer types (WebStorm, made
 *      for the web, before Visual Studio Code, made for everything);
 *   3. the name, so that the order never changes by chance.
 * Only an installed program that can receive a file is suggested; the others are listed
 * with the reason they cannot.
 */

export type ViewKind = ProjectFileView["kind"];

export interface FileType {
  /** The file name in lower case. */
  name: string;
  /** ".java", or "" when the name has none. */
  extension: string;
  /** What the operator calls it: "Java", "Markdown", "Immagine PNG", "Testo". */
  label: string;
  /** Text, binary, or not known until it is read. */
  text: boolean | null;
}

export interface Candidate {
  software: Software;
  /** 3 the file name, 2 the extension, 1 any text file, 0 no declaration fits. */
  score: number;
  /** The declaration that matched. */
  matched: string | null;
  /** Why it fits, in a few words; empty when nothing fits. */
  reason: string;
  /** Why it cannot open the file now, or null when it can. */
  unavailable: string | null;
}

export interface OpenWithChoice {
  type: FileType;
  suggested: Candidate | null;
  /** Fits the file and can open it, after the suggested one. */
  suitable: Candidate[];
  /** Can open it, without declaring this kind of file. */
  others: Candidate[];
  /** Would fit, but cannot open it on this machine now. */
  unavailable: Candidate[];
}

// [label, text]. Extensions not listed are named after themselves, and "not known" until read.
const EXTENSIONS: Record<string, [string, boolean]> = {
  ".java": ["Java", true], ".kt": ["Kotlin", true], ".kts": ["Kotlin script", true], ".groovy": ["Groovy", true],
  ".gradle": ["Gradle", true], ".scala": ["Scala", true], ".properties": ["Properties", true],
  ".ts": ["TypeScript", true], ".tsx": ["TypeScript React", true], ".js": ["JavaScript", true],
  ".jsx": ["JavaScript React", true], ".mjs": ["JavaScript (modulo)", true], ".cjs": ["JavaScript (CommonJS)", true],
  ".vue": ["Vue", true], ".svelte": ["Svelte", true], ".astro": ["Astro", true],
  ".css": ["CSS", true], ".scss": ["SCSS", true], ".sass": ["Sass", true], ".less": ["Less", true],
  ".html": ["HTML", true], ".htm": ["HTML", true], ".svg": ["SVG", true],
  ".py": ["Python", true], ".pyi": ["Python (stub)", true], ".ipynb": ["Notebook Jupyter", true],
  ".cs": ["C#", true], ".vb": ["Visual Basic", true], ".fs": ["F#", true], ".c": ["C", true], ".h": ["Header C/C++", true],
  ".cpp": ["C++", true], ".cc": ["C++", true], ".cxx": ["C++", true], ".hpp": ["Header C++", true],
  ".sln": ["Soluzione Visual Studio", true], ".slnx": ["Soluzione Visual Studio", true],
  ".csproj": ["Progetto C#", true], ".vbproj": ["Progetto Visual Basic", true], ".fsproj": ["Progetto F#", true],
  ".vcxproj": ["Progetto C++", true], ".xaml": ["XAML", true], ".razor": ["Razor", true], ".cshtml": ["Razor", true],
  ".go": ["Go", true], ".rs": ["Rust", true], ".rb": ["Ruby", true], ".php": ["PHP", true], ".swift": ["Swift", true],
  ".jsp": ["JSP", true], ".jspf": ["JSP", true], ".tag": ["Tag JSP", true], ".tld": ["Tag library", true],
  ".form": ["Form Swing (NetBeans)", true],
  ".sql": ["SQL", true], ".graphql": ["GraphQL", true], ".prisma": ["Prisma", true],
  ".md": ["Markdown", true], ".mdx": ["MDX", true], ".txt": ["Testo", true], ".log": ["Log", true],
  ".json": ["JSON", true], ".jsonc": ["JSON", true], ".json5": ["JSON5", true], ".xml": ["XML", true],
  ".xsd": ["XML Schema", true], ".yml": ["YAML", true], ".yaml": ["YAML", true], ".toml": ["TOML", true],
  ".ini": ["Configurazione", true], ".cfg": ["Configurazione", true], ".conf": ["Configurazione", true],
  ".env": ["Variabili d'ambiente", true], ".csv": ["CSV", true], ".tsv": ["TSV", true],
  ".sh": ["Script shell", true], ".bash": ["Script Bash", true], ".ps1": ["Script PowerShell", true],
  ".psm1": ["Modulo PowerShell", true], ".bat": ["Script batch", true], ".cmd": ["Script batch", true],
  ".gitignore": ["Git ignore", true], ".gitattributes": ["Git attributes", true], ".editorconfig": ["EditorConfig", true],
  ".dockerignore": ["Docker ignore", true],
  ".png": ["Immagine PNG", false], ".jpg": ["Immagine JPEG", false], ".jpeg": ["Immagine JPEG", false],
  ".gif": ["Immagine GIF", false], ".webp": ["Immagine WebP", false], ".bmp": ["Immagine BMP", false],
  ".ico": ["Icona", false], ".psd": ["Photoshop", false],
  ".blend": ["Scena Blender", false], ".fbx": ["Modello FBX", false], ".obj": ["Modello OBJ", false],
  ".glb": ["Modello glTF", false], ".gltf": ["Modello glTF", true], ".stl": ["Modello STL", false],
  ".mwb": ["Modello MySQL Workbench", false], ".drawio": ["Diagramma draw.io", true],
  ".pdf": ["PDF", false], ".docx": ["Documento Word", false], ".xlsx": ["Foglio Excel", false],
  ".pptx": ["Presentazione PowerPoint", false], ".zip": ["Archivio ZIP", false], ".7z": ["Archivio 7z", false],
  ".rar": ["Archivio RAR", false], ".gz": ["Archivio gzip", false], ".tar": ["Archivio tar", false],
  ".jar": ["Archivio Java", false], ".war": ["Archivio web Java", false], ".class": ["Bytecode Java", false],
  ".exe": ["Eseguibile", false], ".dll": ["Libreria", false], ".msi": ["Installer", false],
  ".mp3": ["Audio", false], ".wav": ["Audio", false], ".mp4": ["Video", false], ".mov": ["Video", false],
  ".ttf": ["Font", false], ".otf": ["Font", false], ".woff": ["Font", false], ".woff2": ["Font", false],
  ".db": ["Database", false], ".sqlite": ["Database SQLite", false],
};

const NAMES: Record<string, string> = {
  "dockerfile": "Dockerfile", "docker-compose.yml": "Docker Compose", "makefile": "Makefile",
  "pom.xml": "Progetto Maven", "build.gradle": "Build Gradle", "build.gradle.kts": "Build Gradle",
  "settings.gradle": "Impostazioni Gradle", "settings.gradle.kts": "Impostazioni Gradle",
  "package.json": "Progetto npm", "tsconfig.json": "Configurazione TypeScript",
  "requirements.txt": "Dipendenze Python", "pyproject.toml": "Progetto Python", "pipfile": "Dipendenze Python",
  "build.xml": "Build Ant", "license": "Licenza", "readme": "Leggimi",
};

/** The extension of a name, or "" -- a dotfile such as ".gitignore" is its own extension. */
function extensionOf(name: string): string {
  const dot = name.lastIndexOf(".");
  return dot < 0 ? "" : name.slice(dot);
}

/** What a file is, from its path and -- once the viewer read it -- from its kind. */
export function fileTypeOf(path: string, kind?: ViewKind | null): FileType {
  const name = (path.split("/").pop() ?? path).toLowerCase();
  const extension = extensionOf(name);
  const known = EXTENSIONS[extension];
  const label = NAMES[name] ?? known?.[0] ?? (extension ? `File ${extension}` : "File");
  let text: boolean | null = known ? known[1] : null;
  if (kind === "TEXT") text = true;
  else if (kind === "IMAGE" || kind === "BINARY") text = false;
  return { name, extension, label, text };
}

function match(software: Software, type: FileType): { score: number; matched: string | null } {
  let best = { score: 0, matched: null as string | null };
  for (const raw of software.fileTypes ?? []) {
    const token = raw.trim().toLowerCase();
    let score = 0;
    if (token === "*") score = type.text === false ? 0 : 1;
    else if (token.startsWith(".")) score = type.name.endsWith(token) ? 2 : 0;
    else score = type.name === token ? 3 : 0;
    if (score > best.score || (score === best.score && score > 0 && token.length > (best.matched?.length ?? 0))) {
      best = { score, matched: token };
    }
  }
  return best;
}

function reasonOf(score: number, matched: string | null, type: FileType): string {
  if (score === 3) return `Fatto per ${type.label} (${matched})`;
  if (score === 2) return `Adatto ai file ${matched}`;
  if (score === 1) return "Apre qualsiasi file di testo";
  return "";
}

/** Why a program the catalog says opens files cannot open one now. */
export function unavailableReason(software: Software): string | null {
  if (software.opensFiles) return null;
  if (!software.enabled) return "Disattivato nel Software Hub";
  if (software.availability === "NOT_INSTALLED") return "Non installato";
  if (software.availability === "INCOMPATIBLE_HARDWARE") return "Non compatibile con questo computer";
  if (software.availability === "INSTALLED" && !software.resolvedExecutable) {
    return "Aperto dal menu Start: non riceve un file";
  }
  return "Non può ricevere un file su questo computer";
}

/** The programs that could open a file, ranked for it. */
export function openWith(software: Software[], path: string, kind?: ViewKind | null): OpenWithChoice {
  const type = fileTypeOf(path, kind);
  const candidates: Candidate[] = software
    .filter((s) => (s.fileArgs ?? []).length > 0)
    .map((s) => {
      const { score, matched } = match(s, type);
      return { software: s, score, matched, reason: reasonOf(score, matched, type), unavailable: unavailableReason(s) };
    })
    .sort((a, b) => b.score - a.score
      || (b.matched?.length ?? 0) - (a.matched?.length ?? 0)
      || (a.software.fileTypes?.length ?? 0) - (b.software.fileTypes?.length ?? 0)
      || a.software.name.localeCompare(b.software.name, "it"));
  const available = candidates.filter((c) => c.unavailable === null);
  const suggested = available.find((c) => c.score > 0) ?? null;
  return {
    type,
    suggested,
    suitable: available.filter((c) => c.score > 0 && c !== suggested),
    others: available.filter((c) => c.score === 0).sort((a, b) => a.software.name.localeCompare(b.software.name, "it")),
    unavailable: candidates.filter((c) => c.unavailable !== null && c.score > 0),
  };
}

/** "IntelliJ IDEA + Junie" is "IntelliJ IDEA" on a button. */
export function shortName(software: Software): string {
  return software.name.split(" + ")[0];
}

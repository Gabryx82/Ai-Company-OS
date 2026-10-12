import type { Software } from "../api/types";

/** A program of the Software Hub, as the control plane describes it; installed unless said otherwise. */
export function program(key: string, name: string, fileTypes: string[], extra: Partial<Software> = {}): Software {
  return {
    id: key.length, key, name, category: "IDE", role: `Ruolo di ${name}`, purpose: null, capabilities: [], projectTypes: [],
    launchKind: "DESKTOP", appId: null, executable: `C:\\Apps\\${key}.exe`, executableArgs: [], openFolder: true,
    cliCommand: null, url: null, healthUrl: null, embeddable: false, embedNote: null, executionTarget: false,
    requirements: null, configuration: null, iconUrl: null, incompatibleReason: null, enabled: true,
    availability: "INSTALLED", availabilityDetail: null, resolvedExecutable: `C:\\Apps\\${key}.exe`, launchable: true,
    version: 0, createdAt: "2026-10-01T10:00:00Z", updatedAt: "2026-10-01T10:00:00Z", catalog: true,
    fileArgs: ["{file}"], fileTypes, opensFiles: true, ...extra,
  } as Software;
}

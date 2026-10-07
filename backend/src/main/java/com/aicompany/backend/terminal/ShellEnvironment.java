package com.aicompany.backend.terminal;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The environment a shell of the console starts with (ADR-029 §5): the
 * operator's, as a terminal opened from the Start menu would get it -- not the
 * backend process's. The backend may have been started by anything (a script, an
 * agent's session) and carries that parent's variables: a Claude Code session
 * marks its children ({@code CLAUDECODE}, {@code CLAUDE_CODE_CHILD_SESSION}…) and
 * a Claude Code opened under those markers believes it is nested and stops saving
 * its transcript.
 *
 * <p>On Windows the environment is built afresh from the user's profile and the
 * registry ({@code CreateEnvironmentBlock}, as Explorer does). Elsewhere, or if
 * that fails, it is the backend's own; in both cases without a parent session's
 * markers.
 */
final class ShellEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(ShellEnvironment.class);

    /** What a running Claude Code session hands to the processes it starts: never configuration. */
    static final Set<String> SESSION_MARKERS = Set.of(
            "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID",
            "CLAUDE_CODE_HOST_SESSION_ID", "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_MESSAGING_SOCKET",
            "CLAUDE_CODE_MESSAGING_TOKEN", "CLAUDE_CODE_SSE_PORT", "CLAUDE_CODE_EXECPATH", "CLAUDE_PID");

    private ShellEnvironment() {
    }

    static Map<String, String> forShells(Map<String, String> inherited) {
        Map<String, String> base = Platform.isWindows() ? userEnvironment().orElse(inherited) : inherited;
        Map<String, String> environment = withoutSessionMarkers(base);
        environment.put("TERM", "xterm-256color");
        return environment;
    }

    static Map<String, String> withoutSessionMarkers(Map<String, String> environment) {
        Map<String, String> out = new HashMap<>(environment);
        out.keySet().removeIf(name -> SESSION_MARKERS.stream().anyMatch(marker -> marker.equalsIgnoreCase(name)));
        return out;
    }

    /** The environment Windows gives a new process of this user, read from the profile and the registry. */
    static Optional<Map<String, String>> userEnvironment() {
        if (!Platform.isWindows()) {
            return Optional.empty();
        }
        WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(),
                WinNT.TOKEN_QUERY | WinNT.TOKEN_DUPLICATE, token)) {
            LOG.warn("Cannot open the process token (error {}): shells get the backend's environment",
                    Kernel32.INSTANCE.GetLastError());
            return Optional.empty();
        }
        try {
            PointerByReference block = new PointerByReference();
            if (!UserEnv.INSTANCE.CreateEnvironmentBlock(block, token.getValue(), false)) {
                LOG.warn("CreateEnvironmentBlock failed (error {}): shells get the backend's environment",
                        Kernel32.INSTANCE.GetLastError());
                return Optional.empty();
            }
            try {
                return Optional.of(parse(block.getValue()));
            } finally {
                UserEnv.INSTANCE.DestroyEnvironmentBlock(block.getValue());
            }
        } catch (LinkageError | RuntimeException e) {
            LOG.warn("Cannot build the user's environment ({}): shells get the backend's environment", e.toString());
            return Optional.empty();
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.getValue());
        }
    }

    /** A block of {@code NAME=value} UTF-16 strings, ended by an empty one; the hidden {@code =C:} entries are skipped. */
    private static Map<String, String> parse(Pointer block) {
        Map<String, String> environment = new HashMap<>();
        long offset = 0;
        while (true) {
            String entry = block.getWideString(offset);
            if (entry.isEmpty()) {
                return environment;
            }
            offset += (entry.length() + 1L) * Native.WCHAR_SIZE;
            int equals = entry.indexOf('=', 1);
            if (equals > 0 && entry.charAt(0) != '=') {
                environment.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
    }

    private interface UserEnv extends StdCallLibrary {
        UserEnv INSTANCE = Native.load("userenv", UserEnv.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean CreateEnvironmentBlock(PointerByReference environment, WinNT.HANDLE token, boolean inherit);

        boolean DestroyEnvironmentBlock(Pointer environment);
    }
}

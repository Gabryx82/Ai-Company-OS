package com.aicompany.backend.terminal;

import com.pty4j.PtyProcess;
import org.springframework.web.socket.WebSocketSession;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A shell of the console and the socket it is shown on, if any (ADR-029 §5). The
 * process belongs to the shell, not to the socket: a socket that goes away (a
 * reloaded page, a closed browser tab) leaves the shell running, detached, until
 * a console of the same person attaches again, closes it, or it has been alone
 * longer than the configured time.
 *
 * <p>The fields that change are guarded by the instance's monitor, which is also
 * what keeps "output appended to the history" and "output sent to the socket" in
 * the same order as "replay sent to a socket that attaches".
 */
final class RunningShell {

    final String id;
    final String owner;
    final String shell;
    final Path directory;
    final Instant startedAt;
    final PtyProcess process;
    final String pty;
    final OutputHistory history = new OutputHistory();
    final TerminalModes modes = new TerminalModes();

    /** The socket it is shown on, and that socket's id; both null while detached. */
    WebSocketSession socket;
    String socketId;
    Instant detachedAt;
    /** The console closed it on purpose: the socket going away ends the process. */
    volatile boolean closeRequested;

    RunningShell(String id, String owner, String shell, Path directory, PtyProcess process, String pty) {
        this.id = id;
        this.owner = owner;
        this.shell = shell;
        this.directory = directory;
        this.startedAt = Instant.now();
        this.process = process;
        this.pty = pty;
    }

    synchronized TerminalSocketHandler.TerminalSession view() {
        return new TerminalSocketHandler.TerminalSession(id, shell, directory.toString(), startedAt, socket != null, detachedAt);
    }
}

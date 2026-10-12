package com.aicompany.backend.workspace.desktop;

import com.aicompany.backend.software.detect.PathTemplate;
import com.aicompany.backend.software.exception.LaunchFailedException;
import com.aicompany.backend.software.exception.LauncherUnavailableException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real desktop: the Windows shell through JNA (already on the classpath for the
 * terminal).
 *
 * <p><strong>The Recycle Bin, never a delete.</strong> {@code SHFileOperation} with
 * {@code FOF_ALLOWUNDO} moves to the Recycle Bin; it deletes for good only where there is
 * no bin -- a network share, a removable drive -- so only fixed drives are accepted. Two
 * cases remain where Windows would destroy instead of recycle (an item larger than the
 * bin, a bin set to "remove files immediately"): {@code FOF_WANTNUKEWARNING} makes Windows
 * ask the operator on the desktop first, and the explorer refuses items over 1 GB before
 * it gets there. {@code FOF_NORECURSEREPARSE} treats a link inside a folder as an object:
 * what it points at is never touched.
 *
 * <p><strong>Revealing</strong> asks the shell itself ({@code SHOpenFolderAndSelectItems})
 * to open the folder with the item selected. Not {@code explorer.exe /select,<path>}: a
 * path with a space makes Java quote the whole argument, and Explorer then opens Documents
 * instead (seen on the operator's machine, 2026-10-13); a comma in a name breaks it too.
 * Should the shell fail, Explorer opens the folder that holds the item -- a folder, never
 * a file, which Explorer would run. It starts a window, so it follows the launcher's
 * switch (ADR-019 I6).
 */
public class WindowsDesktopShell implements DesktopShell {

    private static final Logger log = LoggerFactory.getLogger(WindowsDesktopShell.class);

    private static final int FLAGS = ShellAPI.FOF_ALLOWUNDO | ShellAPI.FOF_NO_UI | ShellAPI.FOF_WANTNUKEWARNING
            | ShellAPI.FOF_NORECURSEREPARSE;

    /** The three shell functions JNA's {@code Shell32} does not map. */
    interface ShellItems extends StdCallLibrary {
        Pointer ILCreateFromPathW(WString path);

        void ILFree(Pointer pidl);

        WinNT.HRESULT SHOpenFolderAndSelectItems(Pointer pidlFolder, int cidl, Pointer apidl, int flags);
    }

    private final boolean windows;
    private final boolean launcherEnabled;
    private final Map<String, String> environment;

    public WindowsDesktopShell(boolean windows, boolean launcherEnabled, Map<String, String> environment) {
        this.windows = windows;
        this.launcherEnabled = launcherEnabled;
        this.environment = environment;
    }

    @Override
    public Optional<String> recycleBinRefusal(Path path) {
        if (!windows) {
            return Optional.of("Il Cestino è disponibile solo quando il control plane gira su Windows.");
        }
        Path root = path.toAbsolutePath().getRoot();
        if (root == null || Kernel32.INSTANCE.GetDriveType(root.toString()) != WinBase.DRIVE_FIXED) {
            return Optional.of("La cartella è su " + (root == null ? "un'unità sconosciuta" : root)
                    + ", che non è un disco fisso di questo computer: Windows non ha un Cestino lì e cancellerebbe "
                    + "per sempre. Eliminalo da Esplora risorse, se vuoi.");
        }
        return Optional.empty();
    }

    @Override
    public void moveToRecycleBin(Path path) throws IOException {
        Optional<String> refusal = recycleBinRefusal(path);
        if (refusal.isPresent()) {
            throw new IOException(refusal.get());
        }
        ShellAPI.SHFILEOPSTRUCT operation = new ShellAPI.SHFILEOPSTRUCT();
        operation.wFunc = ShellAPI.FO_DELETE;
        operation.pFrom = operation.encodePaths(new String[]{path.toAbsolutePath().toString()});
        operation.fFlags = (short) FLAGS;
        int result = Shell32.INSTANCE.SHFileOperation(operation);
        if (result != 0) {
            throw new IOException("Windows non l'ha spostato nel Cestino (codice 0x" + Integer.toHexString(result)
                    + "): forse è aperto in un altro programma.");
        }
        if (operation.fAnyOperationsAborted) {
            throw new IOException("Lo spostamento nel Cestino è stato annullato.");
        }
        log.info("Moved to the Recycle Bin: {}", path.getFileName());
    }

    @Override
    public void reveal(Path path) {
        if (!windows || !launcherEnabled) {
            throw new LauncherUnavailableException(
                    "Opening Explorer is supported only when the control plane runs on Windows with the launcher on");
        }
        Path target = path.toAbsolutePath().normalize();
        // On a thread of its own: COM is initialised for this call and gone after it, whatever the
        // request thread had.
        AtomicReference<String> failure = new AtomicReference<>();
        Thread shell = new Thread(() -> failure.set(selectInExplorer(target)), "aicos-reveal");
        shell.setDaemon(true);
        shell.start();
        try {
            shell.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (shell.isAlive()) {
            log.info("Explorer is still opening {}", target.getFileName());
            return;
        }
        if (failure.get() != null) {
            log.warn("The shell did not select {} ({}): opening its folder", target.getFileName(), failure.get());
            openFolder(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? target : target.getParent());
        }
    }

    /** Null when the shell opened the folder with the item selected; why not, otherwise. */
    private static String selectInExplorer(Path target) {
        WinNT.HRESULT initialised = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        try {
            ShellItems shell = Native.load("shell32", ShellItems.class);
            Pointer item = shell.ILCreateFromPathW(new WString(target.toString()));
            if (item == null) {
                return "the shell has no item for it";
            }
            try {
                WinNT.HRESULT result = shell.SHOpenFolderAndSelectItems(item, 0, null, 0);
                return COMUtils.SUCCEEDED(result) ? null : "HRESULT 0x" + Integer.toHexString(result.intValue());
            } finally {
                shell.ILFree(item);
            }
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return e.toString();
        } finally {
            if (COMUtils.SUCCEEDED(initialised)) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    private void openFolder(Path folder) {
        String explorer = PathTemplate.expand("%SystemRoot%\\explorer.exe", environment).orElse("explorer.exe");
        try {
            new ProcessBuilder(explorer, folder.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            log.warn("Explorer did not start: {}", e.toString());
            throw new LaunchFailedException("The operating system did not start " + explorer);
        }
    }
}

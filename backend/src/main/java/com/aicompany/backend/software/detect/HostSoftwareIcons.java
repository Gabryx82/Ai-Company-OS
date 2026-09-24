package com.aicompany.backend.software.detect;

import com.aicompany.backend.software.model.Software;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Icons extracted with Windows PowerShell: the associated icon of an executable,
 * or the logo a Store package declares in its manifest.
 *
 * <p>The target reaches the script through an environment variable, never by
 * being spliced into the script text, so a path cannot become PowerShell code.
 * Results -- including "no icon" -- are cached for the life of the process.
 */
public class HostSoftwareIcons implements SoftwareIcons {

    private static final Logger log = LoggerFactory.getLogger(HostSoftwareIcons.class);

    private static final String EXECUTABLE_SCRIPT = """
            Add-Type -AssemblyName System.Drawing
            $icon = [System.Drawing.Icon]::ExtractAssociatedIcon($env:AICOS_ICON_TARGET)
            $bitmap = $icon.ToBitmap()
            $stream = New-Object System.IO.MemoryStream
            $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
            [Convert]::ToBase64String($stream.ToArray())
            """;

    private static final String PACKAGE_SCRIPT = """
            $parts = $env:AICOS_ICON_TARGET.Split('!')
            $package = Get-AppxPackage | Where-Object { $_.PackageFamilyName -eq $parts[0] } | Select-Object -First 1
            if (-not $package) { exit 0 }
            [xml]$manifest = Get-Content -LiteralPath (Join-Path $package.InstallLocation 'AppxManifest.xml')
            $app = $manifest.Package.Applications.Application | Where-Object { $_.Id -eq $parts[1] } | Select-Object -First 1
            if (-not $app) { $app = $manifest.Package.Applications.Application | Select-Object -First 1 }
            $logo = $app.VisualElements.Square44x44Logo
            if (-not $logo) { $logo = $app.VisualElements.Square150x150Logo }
            if (-not $logo) { exit 0 }
            $folder = Join-Path $package.InstallLocation (Split-Path $logo)
            $base = [System.IO.Path]::GetFileNameWithoutExtension($logo)
            $file = Get-ChildItem -LiteralPath $folder -Filter "$base*.png" |
                Where-Object { $_.Name -notmatch 'contrast' } | Sort-Object Length -Descending | Select-Object -First 1
            if ($file) { [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($file.FullName)) }
            """;

    private final Map<String, String> environment;
    private final boolean windows;
    private final Map<String, Optional<byte[]>> cache = new ConcurrentHashMap<>();

    public HostSoftwareIcons(Map<String, String> environment, boolean windows) {
        this.environment = Map.copyOf(environment);
        this.windows = windows;
    }

    @Override
    public Optional<byte[]> icon(Software software, Detection detection) {
        if (!windows) {
            return Optional.empty();
        }
        if (detection.executable() != null && detection.executable().toString().toLowerCase().endsWith(".exe")) {
            String target = detection.executable().toString();
            return cache.computeIfAbsent("exe:" + target, ignored -> run(EXECUTABLE_SCRIPT, target));
        }
        String appId = software.getAppId();
        if (appId != null && appId.contains("!")) {
            return cache.computeIfAbsent("pkg:" + appId, ignored -> run(PACKAGE_SCRIPT, appId));
        }
        return Optional.empty();
    }

    private Optional<byte[]> run(String script, String target) {
        Optional<String> powershell = PathTemplate.expand(
                "%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", environment);
        if (powershell.isEmpty()) {
            return Optional.empty();
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(powershell.get(), "-NoProfile", "-NonInteractive",
                    "-Command", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("AICOS_ICON_TARGET", target);
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String base64 = new String(output, StandardCharsets.US_ASCII).strip();
            if (base64.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Base64.getDecoder().decode(base64));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("No icon for {}: {}", target, failure.toString());
            return Optional.empty();
        }
    }
}

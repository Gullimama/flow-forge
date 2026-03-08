package com.flowforge.ingest.blob;

import com.flowforge.common.entity.LogType;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Extracts zip/gzip/tar.gz archives with safety checks and classifies log files (APP / ISTIO / UNKNOWN).
 */
@Component
public class ZipExtractor {

    private static final long MAX_UNCOMPRESSED_SIZE = 10L * 1024 * 1024 * 1024; // 10 GB
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_COMPRESSION_RATIO = 100;

    public record ClassifiedLogFile(Path path, LogType logType, String serviceName, long sizeBytes) {}

    /**
     * Extract a zip file with safety checks against zip bombs and path traversal.
     */
    public List<Path> extract(Path zipFile, Path targetDir) throws IOException {
        List<Path> extracted = new ArrayList<>();
        long compressedSize = Files.size(zipFile);
        long totalExtracted = 0;
        int entryCount = 0;

        try (ZipFile zf = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (entryCount++ >= MAX_ENTRIES) {
                    throw new SecurityException("Too many entries (max " + MAX_ENTRIES + ")");
                }
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                Path resolved = safeResolve(targetDir, entry.getName());
                Files.createDirectories(resolved.getParent());
                long entrySize = entry.getSize();
                if (entrySize >= 0) {
                    totalExtracted += entrySize;
                    checkSizeLimit(totalExtracted, compressedSize);
                }
                try (InputStream in = zf.getInputStream(entry)) {
                    long written = Files.copy(in, resolved);
                    if (entrySize < 0) {
                        totalExtracted += written;
                        checkSizeLimit(totalExtracted, compressedSize);
                    }
                }
                extracted.add(resolved);
            }
        }
        return extracted;
    }

    /**
     * Extract and classify: returns list of classified log files.
     * Supports .zip, .gz, and .tar.gz formats; plain files are copied and classified.
     */
    public List<ClassifiedLogFile> extractAndClassify(Path archiveFile, Path targetDir) throws IOException {
        String filename = archiveFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".tar.gz") || filename.endsWith(".tgz")) {
            return extractTarGz(archiveFile, targetDir);
        } else if (filename.endsWith(".gz") && !filename.endsWith(".tar.gz")) {
            return extractGzip(archiveFile, targetDir);
        } else if (filename.endsWith(".zip")) {
            return extractZipAndClassify(archiveFile, targetDir);
        } else {
            return classifyPlainFile(archiveFile, targetDir);
        }
    }

    public Path safeResolve(Path targetDir, String entryName) {
        Path resolved = targetDir.resolve(entryName).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new SecurityException(
                "Zip entry '" + entryName + "' escapes target directory");
        }
        return resolved;
    }

    public void checkSizeLimit(long totalExtracted, long compressedSize) {
        if (totalExtracted > MAX_UNCOMPRESSED_SIZE) {
            throw new SecurityException(
                "Extracted size " + totalExtracted + " exceeds limit " + MAX_UNCOMPRESSED_SIZE + " (potential zip bomb)");
        }
        if (compressedSize > 0 && totalExtracted / compressedSize > MAX_COMPRESSION_RATIO) {
            throw new SecurityException(
                "Compression ratio exceeds limit " + MAX_COMPRESSION_RATIO + " (potential zip bomb)");
        }
    }

    public static LogType classifyByFilename(String filename) {
        if (filename == null) {
            return LogType.UNKNOWN;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.contains("istio") || lower.contains("envoy") || lower.contains("proxy")) {
            return LogType.ISTIO;
        }
        if (lower.contains("app") || lower.endsWith(".log")) {
            return LogType.APP;
        }
        return LogType.UNKNOWN;
    }

    private List<ClassifiedLogFile> extractZipAndClassify(Path zipFile, Path targetDir) throws IOException {
        List<Path> paths = extract(zipFile, targetDir);
        List<ClassifiedLogFile> result = new ArrayList<>();
        for (Path p : paths) {
            long size = Files.size(p);
            LogType logType = classifyByFilename(p.getFileName().toString());
            String serviceName = deriveServiceName(p);
            result.add(new ClassifiedLogFile(p, logType, serviceName, size));
        }
        return result;
    }

    private List<ClassifiedLogFile> extractGzip(Path gzFile, Path targetDir) throws IOException {
        String baseName = gzFile.getFileName().toString();
        if (baseName.endsWith(".gz")) {
            baseName = baseName.substring(0, baseName.length() - 3);
        }
        Path outFile = targetDir.resolve(baseName);
        Files.createDirectories(targetDir);
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gzFile)))) {
            Files.copy(in, outFile);
        }
        long size = Files.size(outFile);
        LogType logType = classifyByFilename(baseName);
        return List.of(new ClassifiedLogFile(outFile, logType, deriveServiceName(outFile), size));
    }

    private List<ClassifiedLogFile> extractTarGz(Path tarGzFile, Path targetDir) throws IOException {
        // Simple tar.gz: read gzip stream and parse tar (minimal implementation: treat as single stream for first file)
        // For full tar support we'd use Apache Commons Compress or similar. Here we extract the first member.
        Files.createDirectories(targetDir);
        List<ClassifiedLogFile> result = new ArrayList<>();
        try (GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tarGzFile)));
             TarInputStream tar = new TarInputStream(gzip)) {
            TarEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path resolved = safeResolve(targetDir, entry.getName());
                Files.createDirectories(resolved.getParent());
                long written = tar.copyTo(resolved);
                LogType logType = classifyByFilename(entry.getName());
                result.add(new ClassifiedLogFile(resolved, logType, deriveServiceName(resolved), written));
            }
        }
        return result;
    }

    private List<ClassifiedLogFile> classifyPlainFile(Path file, Path targetDir) throws IOException {
        Path dest = targetDir.resolve(file.getFileName());
        Files.createDirectories(targetDir);
        if (!file.equals(dest)) {
            Files.copy(file, dest);
        }
        long size = Files.size(dest);
        LogType logType = classifyByFilename(file.getFileName().toString());
        return List.of(new ClassifiedLogFile(dest, logType, deriveServiceName(dest), size));
    }

    private static String deriveServiceName(Path path) {
        if (path == null || path.getNameCount() < 2) {
            return "";
        }
        return path.getName(path.getNameCount() - 2).toString();
    }

    /** Minimal tar input stream to read tar.gz one entry at a time. */
    private static class TarInputStream implements AutoCloseable {
        private final InputStream in;
        private final byte[] block = new byte[512];
        private String currentName;
        private long currentSize;
        private long currentRead;

        TarInputStream(InputStream in) {
            this.in = in;
        }

        TarEntry getNextEntry() throws IOException {
            if (currentName != null) {
                while (currentRead < currentSize) {
                    long toSkip = Math.min(block.length, currentSize - currentRead);
                    in.skipNBytes(toSkip);
                    currentRead += toSkip;
                }
                long padding = (512 - (currentSize % 512)) % 512;
                if (padding > 0) {
                    in.skipNBytes(padding);
                }
            }
            int n = in.read(block);
            if (n < 512 || block[0] == 0) {
                return null;
            }
            currentName = new String(block, 0, 100).trim();
            if (currentName.isEmpty()) {
                return null;
            }
            String sizeStr = new String(block, 124, 12).trim();
            currentSize = 0;
            for (int i = 0; i < sizeStr.length(); i++) {
                char c = sizeStr.charAt(i);
                if (c >= '0' && c <= '7') {
                    currentSize = currentSize * 8 + (c - '0');
                }
            }
            currentRead = 0;
            return new TarEntry(currentName, currentSize);
        }

        long copyTo(Path path) throws IOException {
            long written = 0;
            byte[] buf = new byte[8192];
            try (java.io.OutputStream out = Files.newOutputStream(path)) {
                while (written < currentSize) {
                    int toRead = (int) Math.min(buf.length, currentSize - written);
                    int r = in.read(buf, 0, toRead);
                    if (r <= 0) break;
                    out.write(buf, 0, r);
                    written += r;
                    currentRead += r;
                }
            }
            return written;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static class TarEntry {
        private final String name;
        private final long size;

        TarEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }

        String getName() { return name; }
        long getSize() { return size; }
        boolean isDirectory() { return name.endsWith("/"); }
    }
}

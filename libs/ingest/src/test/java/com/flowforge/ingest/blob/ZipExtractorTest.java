package com.flowforge.ingest.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowforge.common.entity.LogType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class ZipExtractorTest {

    private final ZipExtractor extractor = new ZipExtractor();

    private static Path createTestZip(Path dir, Map<String, String> entries) throws Exception {
        Path zipFile = dir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes());
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    private static Path createZipWithEntry(Path dir, String entryName, String content) throws Exception {
        return createTestZip(dir, Map.of(entryName, content));
    }

    @Test
    void extract_standardZip_extractsAllEntries(@TempDir Path tempDir) throws Exception {
        Path zipFile = createTestZip(tempDir, Map.of(
            "app-booking.log", "2024-01-15 INFO booking started",
            "app-payment.log", "2024-01-15 ERROR payment failed"));

        Path outDir = tempDir.resolve("out");
        List<Path> extracted = extractor.extract(zipFile, outDir);

        assertThat(extracted).hasSize(2);
        assertThat(extracted).allSatisfy(p -> assertThat(p).exists());
    }

    @Test
    void extractAndClassify_zipFile_classifiesLogTypes(@TempDir Path tempDir) throws Exception {
        Path zipFile = createTestZip(tempDir, Map.of(
            "app-booking.log", "INFO log line",
            "istio-proxy.log", "envoy access log"));

        List<ZipExtractor.ClassifiedLogFile> classified =
            extractor.extractAndClassify(zipFile, tempDir.resolve("out"));

        assertThat(classified).extracting(ZipExtractor.ClassifiedLogFile::logType)
            .containsExactlyInAnyOrder(LogType.APP, LogType.ISTIO);
    }

    @Test
    void extract_pathTraversal_throwsSecurityException(@TempDir Path tempDir) throws Exception {
        Path maliciousZip = createZipWithEntry(tempDir, "../../../etc/passwd", "malicious");

        assertThatThrownBy(() -> extractor.extract(maliciousZip, tempDir.resolve("out")))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("escapes target directory");
    }

    @Test
    void extractAndClassify_plainTextFile_copiedDirectly(@TempDir Path tempDir) throws Exception {
        Path plainLog = tempDir.resolve("booking-service.log");
        Files.writeString(plainLog, "2024-01-15 INFO plain log line");

        List<ZipExtractor.ClassifiedLogFile> result =
            extractor.extractAndClassify(plainLog, tempDir.resolve("out"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sizeBytes()).isGreaterThan(0);
        assertThat(result.get(0).logType()).isEqualTo(LogType.APP);
    }

    @Test
    void classifyByFilename_appLog_returnsApp() {
        assertThat(ZipExtractor.classifyByFilename("app-booking.log")).isEqualTo(LogType.APP);
        assertThat(ZipExtractor.classifyByFilename("svc/app.log")).isEqualTo(LogType.APP);
    }

    @Test
    void classifyByFilename_istioLog_returnsIstio() {
        assertThat(ZipExtractor.classifyByFilename("istio-proxy.log")).isEqualTo(LogType.ISTIO);
        assertThat(ZipExtractor.classifyByFilename("envoy.log")).isEqualTo(LogType.ISTIO);
    }
}

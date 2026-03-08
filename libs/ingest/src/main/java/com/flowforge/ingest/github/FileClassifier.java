package com.flowforge.ingest.github;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FileClassifier {

    public record ClassifiedFile(
        String relativePath,
        FileType fileType,
        String serviceName,
        String module
    ) {}

    public enum FileType {
        JAVA_SOURCE,
        YAML_CONFIG,
        PROPERTIES_CONFIG,
        K8S_MANIFEST,
        ISTIO_MANIFEST,
        HELM_CHART,
        BUILD_FILE,
        DOCKERFILE,
        OTHER
    }

    private static final List<String> JAVA_EXT = List.of("java");
    private static final List<String> YAML_EXT = List.of("yml", "yaml");
    private static final List<String> PROPERTIES_EXT = List.of("properties");
    private static final Pattern SERVICES_DIR = Pattern.compile("services/([^/]+)");
    private static final Pattern LIBS_DIR = Pattern.compile("libs/([^/]+)");

    public ClassifiedFile classify(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return new ClassifiedFile("", FileType.OTHER, "", "");
        }
        String normalized = relativePath.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        FileType type = resolveFileType(normalized, lower);
        String serviceName = detectServiceName(normalized);
        String module = detectModule(normalized);
        return new ClassifiedFile(relativePath, type, serviceName, module);
    }

    public String detectServiceName(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        String normalized = relativePath.replace('\\', '/');
        var servicesMatcher = SERVICES_DIR.matcher(normalized);
        if (servicesMatcher.find()) {
            return servicesMatcher.group(1);
        }
        var libsMatcher = LIBS_DIR.matcher(normalized);
        if (libsMatcher.find()) {
            return libsMatcher.group(1);
        }
        return "";
    }

    private String detectModule(String path) {
        if (path.contains("/src/main/")) {
            return "main";
        }
        if (path.contains("/src/test/")) {
            return "test";
        }
        return "";
    }

    private FileType resolveFileType(String path, String lowerPath) {
        if (path.endsWith("Dockerfile") || lowerPath.endsWith("dockerfile")) {
            return FileType.DOCKERFILE;
        }
        if ((lowerPath.contains("/helm/") || lowerPath.contains("charts/")) && lowerPath.contains("/templates/")) {
            return FileType.HELM_CHART;
        }
        if (lowerPath.contains("/k8s/") || lowerPath.contains("k8s/")
            || lowerPath.matches(".*deployment\\.yaml") || lowerPath.matches(".*service\\.yaml")
            || lowerPath.matches(".*configmap\\.yaml") || lowerPath.matches(".*-deployment\\.yaml")) {
            return FileType.K8S_MANIFEST;
        }
        if (lowerPath.contains("/istio/") || lowerPath.contains("virtual-service")
            || lowerPath.contains("destination-rule")) {
            return FileType.ISTIO_MANIFEST;
        }
        if ((lowerPath.contains("/helm/") || lowerPath.contains("charts/")) && lowerPath.endsWith("chart.yaml")) {
            return FileType.HELM_CHART;
        }
        if (lowerPath.endsWith("pom.xml") || lowerPath.endsWith("build.gradle")
            || lowerPath.endsWith("build.gradle.kts") || lowerPath.endsWith("settings.gradle")
            || lowerPath.endsWith("settings.gradle.kts")) {
            return FileType.BUILD_FILE;
        }
        String ext = extension(lowerPath);
        if (JAVA_EXT.contains(ext)) {
            return FileType.JAVA_SOURCE;
        }
        if (YAML_EXT.contains(ext)) {
            if (path.contains("src/main/resources/application.")
                || path.contains("src/main/resources/application.yml")
                || path.contains("application.yaml")) {
                return FileType.YAML_CONFIG;
            }
            if (lowerPath.contains("k8s/") || lowerPath.contains("/deployments/")) {
                return FileType.K8S_MANIFEST;
            }
            if (lowerPath.contains("istio/")) {
                return FileType.ISTIO_MANIFEST;
            }
            if (lowerPath.contains("helm/") || lowerPath.contains("charts/")) {
                return FileType.HELM_CHART;
            }
            return FileType.YAML_CONFIG;
        }
        if (PROPERTIES_EXT.contains(ext)) {
            return FileType.PROPERTIES_CONFIG;
        }
        return FileType.OTHER;
    }

    private static String extension(String path) {
        int i = path.lastIndexOf('.');
        if (i <= 0 || i == path.length() - 1) {
            return "";
        }
        return path.substring(i + 1);
    }
}

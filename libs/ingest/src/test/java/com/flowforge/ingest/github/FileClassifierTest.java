package com.flowforge.ingest.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileClassifierTest {

    private final FileClassifier classifier = new FileClassifier();

    @Test
    void classifiesJavaSourceFile() {
        FileClassifier.ClassifiedFile result = classifier.classify("services/booking/src/main/java/com/example/BookingService.java");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.JAVA_SOURCE);
        assertThat(result.serviceName()).isEqualTo("booking");
    }

    @Test
    void classifiesYamlConfigFile() {
        FileClassifier.ClassifiedFile result = classifier.classify("services/payment/src/main/resources/application.yml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.YAML_CONFIG);
        assertThat(result.serviceName()).isEqualTo("payment");
    }

    @Test
    void classifiesKubernetesManifest() {
        FileClassifier.ClassifiedFile result = classifier.classify("k8s/deployments/booking-deployment.yaml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.K8S_MANIFEST);
    }

    @Test
    void classifiesIstioManifest() {
        FileClassifier.ClassifiedFile result = classifier.classify("istio/virtual-service-booking.yaml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.ISTIO_MANIFEST);
    }

    @Test
    void classifiesHelmChart() {
        FileClassifier.ClassifiedFile result = classifier.classify("helm/charts/booking/templates/deployment.yaml");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.HELM_CHART);
    }

    @Test
    void classifiesDockerfile() {
        FileClassifier.ClassifiedFile result = classifier.classify("services/booking/Dockerfile");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.DOCKERFILE);
    }

    @Test
    void classifiesPropertiesConfig() {
        FileClassifier.ClassifiedFile result = classifier.classify("services/api/src/main/resources/application.properties");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.PROPERTIES_CONFIG);
    }

    @Test
    void fallsBackToOtherForUnknownExtension() {
        FileClassifier.ClassifiedFile result = classifier.classify("README.md");
        assertThat(result.fileType()).isEqualTo(FileClassifier.FileType.OTHER);
    }

    @ParameterizedTest
    @CsvSource({
        "services/booking/src/Main.java, booking",
        "services/payment-service/src/Pay.java, payment-service",
        "libs/common/src/Util.java, common"
    })
    void detectsServiceNameFromPath(String path, String expectedService) {
        assertThat(classifier.detectServiceName(path)).isEqualTo(expectedService);
    }
}

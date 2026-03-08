# Stage 20 — vLLM Generation Model (Spring AI ChatModel)

## Goal

Configure **Spring AI ChatModel** to connect to **vLLM** serving **Qwen2.5-Coder-32B-Instruct** via its OpenAI-compatible API. Build a prompt management layer, structured output parsing via `BeanOutputConverter`, and a resilient generation service with retry and circuit-breaker semantics.

## Prerequisites

- Stage 01 (config framework)

## What to build

### 20.1 vLLM deployment

For local dev, use `docker/docker-compose.yml` with 2× GPU passthrough, or use a smaller model (e.g. `Qwen/Qwen2.5-Coder-7B-Instruct`) with a single GPU.

**ArgoCD Application** — registered in the App-of-Apps root:

```yaml
# k8s/argocd/apps/vllm.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: flowforge-vllm
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "5"
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: flowforge
  source:
    repoURL: https://github.com/tesco/flow-forge.git
    targetRevision: HEAD
    path: k8s/ml-serving/vllm
  destination:
    server: https://kubernetes.default.svc
    namespace: flowforge-ml
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

**Deployment** — 2× A100 GPU-scheduled vLLM serving Qwen2.5-Coder-32B-Instruct:

```yaml
# k8s/ml-serving/vllm/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vllm
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: vllm
    app.kubernetes.io/component: llm-serving
    app.kubernetes.io/part-of: flowforge
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: vllm
  template:
    metadata:
      labels:
        app.kubernetes.io/name: vllm
        app.kubernetes.io/component: llm-serving
    spec:
      nodeSelector:
        agentpool: gpupool
      tolerations:
        - key: nvidia.com/gpu
          operator: Equal
          value: present
          effect: NoSchedule
      containers:
        - name: vllm
          image: vllm/vllm-openai:v0.6.6
          args:
            - --model
            - Qwen/Qwen2.5-Coder-32B-Instruct
            - --tensor-parallel-size
            - "2"
            - --max-model-len
            - "32768"
            - --gpu-memory-utilization
            - "0.90"
            - --port
            - "8000"
          ports:
            - containerPort: 8000
              name: http
              protocol: TCP
          resources:
            requests:
              cpu: "4"
              memory: 32Gi
              nvidia.com/gpu: "2"
            limits:
              cpu: "4"
              memory: 32Gi
              nvidia.com/gpu: "2"
          readinessProbe:
            httpGet:
              path: /v1/models
              port: 8000
            initialDelaySeconds: 120
            periodSeconds: 15
          livenessProbe:
            httpGet:
              path: /health
              port: 8000
            initialDelaySeconds: 180
            periodSeconds: 30
          volumeMounts:
            - name: model-cache
              mountPath: /root/.cache/huggingface
      volumes:
        - name: model-cache
          persistentVolumeClaim:
            claimName: vllm-model-cache
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: vllm-model-cache
  namespace: flowforge-ml
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100Gi
```

**Service** — cluster-internal access on port 8000:

```yaml
# k8s/ml-serving/vllm/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: vllm
  namespace: flowforge-ml
  labels:
    app.kubernetes.io/name: vllm
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: vllm
  ports:
    - port: 8000
      targetPort: 8000
      protocol: TCP
      name: http
```

### 20.2 Spring AI ChatModel configuration

```java
@Configuration
public class LlmConfig {

    /**
     * ChatModel backed by vLLM (OpenAI-compatible API).
     */
    @Bean
    public ChatModel chatModel(FlowForgeProperties props) {
        var api = OpenAiApi.builder()
            .baseUrl(props.vllm().baseUrl())       // http://vllm:8000
            .apiKey("not-needed")                   // vLLM doesn't require API key
            .build();

        return new OpenAiChatModel(api,
            OpenAiChatOptions.builder()
                .model("Qwen/Qwen2.5-Coder-32B-Instruct")
                .temperature(0.1)                   // Low temperature for factual output
                .maxTokens(8192)
                .topP(0.95)
                .frequencyPenalty(0.1)
                .build()
        );
    }
}
```

### 20.3 Prompt template management

```java
@Component
public class PromptTemplateManager {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    @PostConstruct
    void loadTemplates() {
        // Load from classpath resources
        templates.put("flow-analysis", loadTemplate("prompts/flow-analysis.st"));
        templates.put("code-explanation", loadTemplate("prompts/code-explanation.st"));
        templates.put("migration-risk", loadTemplate("prompts/migration-risk.st"));
        templates.put("dependency-analysis", loadTemplate("prompts/dependency-analysis.st"));
        templates.put("reactive-complexity", loadTemplate("prompts/reactive-complexity.st"));
        templates.put("synthesis-stage1", loadTemplate("prompts/synthesis-stage1.st"));
        templates.put("synthesis-stage2", loadTemplate("prompts/synthesis-stage2.st"));
        templates.put("synthesis-stage3", loadTemplate("prompts/synthesis-stage3.st"));
        templates.put("synthesis-stage4", loadTemplate("prompts/synthesis-stage4.st"));
        templates.put("synthesis-stage5", loadTemplate("prompts/synthesis-stage5.st"));
        templates.put("synthesis-stage6", loadTemplate("prompts/synthesis-stage6.st"));
    }

    /**
     * Render a named template with the given variables.
     */
    public Prompt render(String templateName, Map<String, Object> variables) {
        var template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName);
        }
        return template.create(variables);
    }

    private PromptTemplate loadTemplate(String resourcePath) {
        var resource = new ClassPathResource(resourcePath);
        return new PromptTemplate(resource);
    }
}
```

### 20.4 Structured output with BeanOutputConverter

```java
@Component
public class StructuredOutputService {

    private final ChatModel chatModel;
    private final PromptTemplateManager promptManager;

    /**
     * Generate structured output parsed into a Java record.
     *
     * Spring AI's BeanOutputConverter automatically:
     * 1. Appends JSON schema instructions to the prompt
     * 2. Parses the LLM response into the target Java type
     */
    public <T> T generate(String templateName, Map<String, Object> variables, Class<T> outputType) {
        var converter = new BeanOutputConverter<>(outputType);

        // Add format instructions to the prompt variables
        var augmentedVars = new HashMap<>(variables);
        augmentedVars.put("format", converter.getFormat());

        var prompt = promptManager.render(templateName, augmentedVars);
        var response = chatModel.call(prompt);

        return converter.convert(response.getResult().getOutput().getContent());
    }

    /**
     * Generate with a list output type.
     */
    public <T> List<T> generateList(String templateName, Map<String, Object> variables,
                                     ParameterizedTypeReference<List<T>> outputType) {
        var converter = new BeanOutputConverter<>(outputType);
        var augmentedVars = new HashMap<>(variables);
        augmentedVars.put("format", converter.getFormat());

        var prompt = promptManager.render(templateName, augmentedVars);
        var response = chatModel.call(prompt);

        return converter.convert(response.getResult().getOutput().getContent());
    }
}
```

### 20.5 Resilient generation service

```java
@Service
public class LlmGenerationService {

    private final ChatModel chatModel;
    private final StructuredOutputService structuredOutput;
    private final PromptTemplateManager promptManager;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Generate free-form text response.
     */
    @CircuitBreaker(name = "vllm", fallbackMethod = "fallbackGenerate")
    @Retry(name = "vllm")
    public String generate(String templateName, Map<String, Object> variables) {
        return meterRegistry.timer("flowforge.llm.generation.latency", "template", templateName)
            .record(() -> {
                var prompt = promptManager.render(templateName, variables);
                var response = chatModel.call(prompt);

                meterRegistry.counter("flowforge.llm.tokens.prompt").increment(
                    response.getMetadata().getUsage().getPromptTokens());
                meterRegistry.counter("flowforge.llm.tokens.completion").increment(
                    response.getMetadata().getUsage().getGenerationTokens());

                return response.getResult().getOutput().getContent();
            });
    }

    /**
     * Generate structured response parsed into output type.
     */
    @CircuitBreaker(name = "vllm", fallbackMethod = "fallbackStructured")
    @Retry(name = "vllm")
    public <T> T generateStructured(String templateName, Map<String, Object> variables,
                                     Class<T> outputType) {
        return meterRegistry.timer("flowforge.llm.generation.structured.latency", "template", templateName)
            .record(() -> structuredOutput.generate(templateName, variables, outputType));
    }

    /**
     * Fallback: return a minimal valid response indicating generation failure.
     */
    private String fallbackGenerate(String templateName, Map<String, Object> variables,
                                     Throwable t) {
        log.error("LLM generation failed for template {}: {}", templateName, t.getMessage());
        meterRegistry.counter("flowforge.llm.generation.fallback").increment();
        return "[LLM generation unavailable — template: %s, error: %s]"
            .formatted(templateName, t.getMessage());
    }

    private <T> T fallbackStructured(String templateName, Map<String, Object> variables,
                                      Class<T> outputType, Throwable t) {
        log.error("Structured LLM generation failed for {}: {}", templateName, t.getMessage());
        meterRegistry.counter("flowforge.llm.generation.structured.fallback").increment();
        throw new LlmGenerationException(
            "Structured generation failed for template '%s': %s"
                .formatted(templateName, t.getMessage()), t);
    }

    private String serializeToString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}

/**
 * Thrown when LLM generation fails and no fallback is possible.
 * Callers should catch this to implement stage-specific degradation.
 */
public class LlmGenerationException extends RuntimeException {
    public LlmGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### 20.6 Resilience4j configuration for vLLM

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      vllm:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        slow-call-rate-threshold: 80
        slow-call-duration-threshold: 120s
  retry:
    instances:
      vllm:
        max-attempts: 2
        wait-duration: 2s
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - java.net.SocketTimeoutException
```

### 20.7 Dependencies

```kotlin
// libs/llm/build.gradle.kts
dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.ai.openai)
    implementation(libs.resilience4j.spring.boot)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
}
```

## Testing & Verification Strategy

### Unit Tests

**`PromptTemplateManagerTest`** — verify template loading and rendering.

```java
class PromptTemplateManagerTest {

    private PromptTemplateManager manager;

    @BeforeEach
    void setUp() {
        manager = new PromptTemplateManager();
        manager.loadTemplates(); // triggers @PostConstruct
    }

    @Test
    void render_knownTemplate_populatesVariables() {
        var prompt = manager.render("flow-analysis", Map.of(
            "flowName", "booking-creation-flow",
            "flowType", "SYNC_REQUEST",
            "services", "api-gateway, booking-service",
            "codeEvidence", "class BookingController { ... }",
            "logPatterns", "ERROR: Connection refused",
            "graphContext", "api-gateway -> booking-service",
            "format", "{}"
        ));
        var text = prompt.getContents();
        assertThat(text).contains("booking-creation-flow");
        assertThat(text).contains("SYNC_REQUEST");
        assertThat(text).contains("api-gateway, booking-service");
    }

    @Test
    void render_unknownTemplate_throwsIllegalArgument() {
        assertThatThrownBy(() -> manager.render("nonexistent", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown template: nonexistent");
    }

    @Test
    void allElevenTemplates_loadSuccessfully() {
        var templateNames = List.of(
            "flow-analysis", "code-explanation", "migration-risk",
            "dependency-analysis", "reactive-complexity",
            "synthesis-stage1", "synthesis-stage2", "synthesis-stage3",
            "synthesis-stage4", "synthesis-stage5", "synthesis-stage6"
        );
        for (var name : templateNames) {
            assertThatCode(() -> manager.render(name, TestFixtures.minimalTemplateVars(name)))
                .doesNotThrowAnyException();
        }
    }
}
```

**`StructuredOutputServiceTest`** — mock `ChatModel`, verify BeanOutputConverter integration.

```java
@ExtendWith(MockitoExtension.class)
class StructuredOutputServiceTest {

    @Mock ChatModel chatModel;
    @Mock PromptTemplateManager promptManager;
    @InjectMocks StructuredOutputService structuredOutputService;

    public record TestAnalysis(String summary, List<String> risks) {}

    @Test
    void generate_parsesValidJsonIntoRecord() {
        when(promptManager.render(anyString(), anyMap())).thenReturn(
            new Prompt("test prompt"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponse("""
                {"summary": "Flow is complex", "risks": ["coupling", "state"]}
                """));

        var result = structuredOutputService.generate(
            "flow-analysis", Map.of(), TestAnalysis.class);

        assertThat(result.summary()).isEqualTo("Flow is complex");
        assertThat(result.risks()).containsExactly("coupling", "state");
    }

    @Test
    void generate_addsFormatInstructionsToVariables() {
        when(promptManager.render(anyString(), argThat(vars ->
            vars.containsKey("format") && vars.get("format").toString().contains("properties")
        ))).thenReturn(new Prompt("with schema"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponse("""
                {"summary": "ok", "risks": []}
                """));

        structuredOutputService.generate("flow-analysis", Map.of(), TestAnalysis.class);

        verify(promptManager).render(eq("flow-analysis"),
            argThat(vars -> vars.containsKey("format")));
    }

    @Test
    void generate_invalidJson_throwsConversionException() {
        when(promptManager.render(anyString(), anyMap())).thenReturn(new Prompt("test"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponse("This is not JSON at all"));

        assertThatThrownBy(() ->
            structuredOutputService.generate("flow-analysis", Map.of(), TestAnalysis.class))
            .isInstanceOf(Exception.class);
    }
}
```

**`LlmGenerationServiceTest`** — test resilience behavior and token tracking.

```java
@ExtendWith(MockitoExtension.class)
class LlmGenerationServiceTest {

    @Mock ChatModel chatModel;
    @Mock StructuredOutputService structuredOutput;
    @Mock PromptTemplateManager promptManager;
    @Mock MeterRegistry meterRegistry;
    @Mock Timer timer;
    @Mock Counter counter;

    @InjectMocks LlmGenerationService generationService;

    @BeforeEach
    void stubMetrics() {
        when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(timer);
        when(timer.record(any(Supplier.class))).thenAnswer(inv ->
            inv.getArgument(0, Supplier.class).get());
        when(meterRegistry.counter(anyString())).thenReturn(counter);
    }

    @Test
    void generate_returnsLlmContent() {
        when(promptManager.render(anyString(), anyMap())).thenReturn(new Prompt("test"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponseWithTokens("Generated analysis", 500, 200));

        var result = generationService.generate("flow-analysis", Map.of());
        assertThat(result).isEqualTo("Generated analysis");
    }

    @Test
    void generate_tracksTokenUsage() {
        when(promptManager.render(anyString(), anyMap())).thenReturn(new Prompt("test"));
        when(chatModel.call(any(Prompt.class))).thenReturn(
            TestFixtures.chatResponseWithTokens("result", 1000, 500));

        generationService.generate("flow-analysis", Map.of());

        verify(meterRegistry).counter("flowforge.llm.tokens.prompt");
        verify(meterRegistry).counter("flowforge.llm.tokens.completion");
    }

    @Test
    void fallbackGenerate_returnsErrorMessage() throws Exception {
        var method = LlmGenerationService.class.getDeclaredMethod(
            "fallbackGenerate", String.class, Map.class, Throwable.class);
        method.setAccessible(true);

        var result = (String) method.invoke(generationService,
            "flow-analysis", Map.of(), new RuntimeException("vLLM timeout"));

        assertThat(result).contains("[LLM generation unavailable");
        assertThat(result).contains("flow-analysis");
        assertThat(result).contains("vLLM timeout");
    }

    @Test
    void fallbackStructured_throwsLlmGenerationException() throws Exception {
        var method = LlmGenerationService.class.getDeclaredMethod(
            "fallbackStructured", String.class, Map.class, Class.class, Throwable.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(generationService,
            "flow-analysis", Map.of(), String.class, new RuntimeException("down")))
            .hasCauseInstanceOf(LlmGenerationException.class);
    }
}
```

### Integration Tests

**`LlmGenerationServiceIntegrationTest`** — WireMock simulates the vLLM OpenAI-compatible API.

```java
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class LlmGenerationServiceIntegrationTest {

    @RegisterExtension
    static WireMockExtension vllmMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("flowforge.vllm.base-url", vllmMock::baseUrl);
    }

    @Autowired LlmGenerationService generationService;

    @BeforeEach
    void stubVllm() {
        vllmMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(okJson(TestFixtures.openAiChatCompletionResponse(
                """
                {"summary": "Test flow", "interactions": []}
                """,
                800, 250
            ))));
    }

    @Test
    void generate_endToEnd_withVllmMock() {
        var result = generationService.generate("flow-analysis", Map.of(
            "flowName", "test-flow",
            "flowType", "SYNC_REQUEST",
            "services", "svc-a, svc-b",
            "codeEvidence", "class Foo {}",
            "logPatterns", "ERROR: timeout",
            "graphContext", "svc-a -> svc-b",
            "format", "{}"
        ));

        assertThat(result).contains("summary");
    }

    @Test
    void circuitBreaker_opensOnRepeatedFailures() {
        vllmMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(serverError()));

        for (int i = 0; i < 15; i++) {
            try { generationService.generate("flow-analysis", Map.of()); }
            catch (Exception ignored) {}
        }
        // Circuit should be open; next call returns fallback immediately
        var result = generationService.generate("flow-analysis", Map.of());
        assertThat(result).contains("[LLM generation unavailable");
    }

    @Test
    void retry_succeedsOnSecondAttempt() {
        vllmMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("recovered"));

        vllmMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .inScenario("retry")
            .whenScenarioStateIs("recovered")
            .willReturn(okJson(TestFixtures.openAiChatCompletionResponse(
                "Success on retry", 100, 50))));

        var result = generationService.generate("flow-analysis",
            TestFixtures.minimalTemplateVars("flow-analysis"));
        assertThat(result).isEqualTo("Success on retry");
    }

    @Test
    void structuredGeneration_parsesJsonFromVllmResponse() {
        record SimpleOutput(String name, int count) {}
        vllmMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(okJson(TestFixtures.openAiChatCompletionResponse(
                """
                {"name": "test", "count": 42}
                """,
                200, 100
            ))));

        var result = generationService.generateStructured(
            "flow-analysis", TestFixtures.minimalTemplateVars("flow-analysis"),
            SimpleOutput.class);

        assertThat(result.name()).isEqualTo("test");
        assertThat(result.count()).isEqualTo(42);
    }
}
```

### Test Fixtures & Sample Data

| Fixture file | Description |
|---|---|
| `src/test/resources/prompts/*.st` | All 11 prompt templates (same as production) — loaded by `PromptTemplateManager` in test context |
| `src/test/resources/fixtures/vllm-chat-completion.json` | OpenAI-compatible `/v1/chat/completions` response with usage stats |
| `src/test/resources/fixtures/vllm-structured-response.json` | vLLM response containing valid JSON matching `FlowAnalysis` schema |
| `src/test/resources/fixtures/vllm-malformed-response.json` | vLLM response with invalid JSON for error-handling tests |
| `TestFixtures.java` | Factory methods: `chatResponse(content)`, `chatResponseWithTokens(content, prompt, completion)`, `openAiChatCompletionResponse(content, promptTokens, completionTokens)`, `minimalTemplateVars(templateName)` |

### Mocking Strategy

| Dependency | Mock or Real | Rationale |
|---|---|---|
| `ChatModel` | **Mock** in unit tests | Avoid vLLM dependency; control response content precisely |
| vLLM HTTP API | **WireMock** in integration tests | Simulate OpenAI-compatible `/v1/chat/completions` deterministically |
| `PromptTemplateManager` | **Mock** in `StructuredOutputServiceTest`, **real** in `PromptTemplateManagerTest` | Isolate output parsing from template rendering |
| `BeanOutputConverter` | **Real** always | Core Spring AI component; test actual JSON→record parsing |
| `MeterRegistry` | **SimpleMeterRegistry** or mock | Verify token counters and latency timers |
| Resilience4j | **Real** in integration tests | Validate circuit breaker state transitions and retry behavior with WireMock scenarios |

### CI/CD Considerations

- **Test tags**: `@Tag("unit")` for template/output/generation unit tests, `@Tag("integration")` for WireMock-based tests.
- **No Testcontainers required**: WireMock runs in-process — no Docker dependency for LLM tests.
- **Gradle filtering**: `./gradlew :libs:llm:test -PincludeTags=unit` for fast CI (< 5s). Integration tests in a separate CI stage.
- **Template validation**: Add a `@ParameterizedTest` that loads each `.st` template and renders it with minimal variables to catch missing resources at build time.
- **WireMock scenarios**: Use WireMock scenarios (`Scenario.STARTED` → state transitions) to test retry logic without timing-dependent flaky tests.
- **Circuit breaker isolation**: Use `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` or reset the circuit breaker registry to prevent state leakage between tests.
- **Response size limits**: Test with large vLLM responses (32K tokens) to validate that `maxTokens` configuration is respected and no OOM occurs in the parser.

## Verification

**Stage 20 sign-off requires all stages 1 through 20 to pass.** Run: `make verify`.

The verification report for stage 20 is `logs/stage-20.log`. It contains **cumulative output for stages 1–20** (Stage 1, then Stage 2, … then Stage 20 output).

| Check | How to verify | Pass criteria |
|---|---|---|
| vLLM pod running | `kubectl get pods -n flowforge-ml -l app.kubernetes.io/name=vllm` | Pod STATUS Running, 1/1 Ready |
| ArgoCD synced | `argocd app get flowforge-vllm` | Sync status Healthy / Synced |
| ChatModel | Simple prompt | Text response returned |
| Template render | "flow-analysis" with variables | Prompt populated correctly |
| Structured output | Generate `FlowAnalysis` record | Valid Java record returned |
| BeanOutputConverter | JSON schema in prompt | Schema appended automatically |
| Temperature | Check output consistency | Low variance across calls |
| Token tracking | Generate + check metrics | prompt/completion tokens counted |
| Retry | First call times out | Second call succeeds |
| Circuit breaker | vLLM down | Fallback response returned |
| Slow call | vLLM slow (>120s) | Circuit opens |
| Health check | Actuator /health | LLM service status shown |
| Latency metric | Check Micrometer timer | llm.generation.latency populated |

## Prompt templates

All templates live under `libs/llm/src/main/resources/prompts/` and use Spring AI's `PromptTemplate` with `{variable}` placeholders. The `{format}` placeholder is auto-injected by `BeanOutputConverter` and contains the JSON schema the model must produce.

### prompts/flow-analysis.st

```
You are an expert Java architect analyzing a legacy Java 11 Micronaut reactive microservice system.

## Flow Under Analysis: {flowName}
Flow Type: {flowType}
Services Involved: {services}

## Source Code Evidence
{codeEvidence}

## Runtime Log Patterns
{logPatterns}

## Knowledge Graph Context
{graphContext}

---
Task: Analyze this system flow end-to-end. For each inter-service interaction, identify the protocol
(HTTP, Kafka, gRPC), the data exchanged, and any side-effects. Describe the overall data flow
(input → transformations → output) and list external dependencies.

Produce your analysis as JSON matching this schema:
{format}
```

### prompts/code-explanation.st

```
You are a senior Java developer explaining legacy Micronaut/RxJava code to a migration team.

## Flow: {flowName}
Complexity: {complexity}

## Code Artifacts
{codeEvidence}

## Prior Flow Analysis
{flowAnalysis}

---
Task: For each code artifact, explain its purpose, the reactive patterns used, relevant annotations,
and what makes it complex or simple to migrate. Highlight any design patterns (Repository, Gateway,
Saga, Circuit Breaker) and Micronaut-specific framework usage (@Singleton, @Client, @KafkaListener).

Produce your explanation as JSON matching this schema:
{format}
```

### prompts/migration-risk.st

```
You are a migration risk analyst evaluating Java 11 Micronaut services for modernization.

## Flow: {flowName}
Services: {services}

## Code Evidence
{codeEvidence}

## Prior Analysis
Flow Analysis: {flowAnalysis}
Code Explanation: {codeExplanation}

---
Task: Identify all migration risks for this flow. For each risk, specify the category (REACTIVE,
COUPLING, STATE, CONFIGURATION, TESTING, DATA_MIGRATION), severity (LOW/MEDIUM/HIGH/CRITICAL),
the affected service, and a concrete mitigation strategy. Also identify all coupling points between
services and suggest decoupling strategies.

Produce your assessment as JSON matching this schema:
{format}
```

### prompts/dependency-analysis.st

```
You are a dependency analyst mapping runtime and build dependencies for a microservice flow.

## Flow: {flowName}
Services: {services}

## Code Evidence
{codeEvidence}

## Graph Context
{graphContext}

---
Task: Map all runtime dependencies (databases, caches, message brokers, external APIs, config servers,
service mesh) and build dependencies (Maven/Gradle artifacts). Identify version conflicts between
services sharing the same libraries. List shared internal libraries and their migration impact.

Produce your mapping as JSON matching this schema:
{format}
```

### prompts/reactive-complexity.st

```
You are a reactive programming specialist analyzing RxJava/Reactor code complexity.

## Class: {classFqn}
Service: {serviceName}

## Source Code
{sourceCode}

## Method Details
{methodDetails}

---
Task: Rate the reactive complexity of this class. Identify all reactive operator chains, error handling
patterns (onErrorResume, retry, timeout), backpressure strategies, and threading model (subscribeOn,
observeOn, Schedulers). Explain what makes migration easy or hard and suggest the equivalent
imperative/virtual-thread approach.

Produce your analysis as JSON matching this schema:
{format}
```

### prompts/synthesis-stage1.st (Flow Analysis)

```
You are analyzing service interaction flow "{flowName}" in a Java 11 Micronaut microservice estate.

## Context
Flow Type: {flowType}
Services: {services}
Complexity: {complexity}

## Evidence
### Code Snippets
{codeEvidence}

### Runtime Log Patterns
{logPatterns}

### Knowledge Graph Paths
{graphContext}

---
Produce a complete flow analysis: describe the flow's purpose, list every inter-service interaction
(protocol, data exchanged, side-effects), describe the data flow (input → transformations → output),
and list external dependencies and assumptions.

Output JSON matching:
{format}
```

### prompts/synthesis-stage2.st (Code Explanation)

```
You are explaining the code artifacts that implement flow "{flowName}".

## Prior Stage Output
{priorStageOutput}

## Code Evidence
{codeEvidence}

---
For each code artifact (class/method), explain its purpose, reactive patterns, annotations,
complexity, and role within the flow. Identify design patterns and framework-specific usage.

Output JSON matching:
{format}
```

### prompts/synthesis-stage3.st (Risk Assessment)

```
You are assessing migration risks for flow "{flowName}".

## Prior Stages
Flow Analysis: {flowAnalysis}
Code Explanation: {codeExplanation}

## Evidence
{codeEvidence}

---
Identify all migration risks (category, severity, affected service, mitigation). Identify coupling
points between services with decoupling strategies. List potential breaking changes and recommendations.

Output JSON matching:
{format}
```

### prompts/synthesis-stage4.st (Dependency Mapping)

```
You are mapping dependencies for flow "{flowName}".

## Prior Analysis
{priorStageOutput}

## Build Manifests
{buildEvidence}

## Graph Context
{graphContext}

---
Map runtime dependencies (type: DATABASE, CACHE, MESSAGE_BROKER, EXTERNAL_API, CONFIG_SERVER,
SERVICE_MESH) and build dependencies (groupId, artifactId, version). Identify version conflicts
across services, shared libraries, and their migration impact.

Output JSON matching:
{format}
```

### prompts/synthesis-stage5.st (Migration Plan)

```
You are creating a migration plan for flow "{flowName}".

## Complete Analysis
Risk Assessment: {riskAssessment}
Dependency Mapping: {dependencyMapping}

## Flow Context
Services: {services}
Complexity: {complexity}

---
Choose a migration strategy (BIG_BANG, STRANGLER_FIG, PARALLEL_RUN, BRANCH_BY_ABSTRACTION) and
justify your choice. Define ordered migration phases with tasks, deliverables, estimated duration,
and risks. Include a rollback plan with feature flag usage and data backup approach.

Output JSON matching:
{format}
```

### prompts/synthesis-stage6.st (Final Narrative)

```
You are writing the final research narrative for flow "{flowName}" in system-flows-research.md.

## Complete Synthesis
Flow Analysis: {flowAnalysis}
Code Explanation: {codeExplanation}
Risk Assessment: {riskAssessment}
Dependency Mapping: {dependencyMapping}
Migration Plan: {migrationPlan}

---
Write an executive summary (2-3 sentences) and a detailed narrative (500-1000 words) covering the
flow's architecture, reactive patterns, migration risks, and recommended approach. Include Mermaid
diagram specifications (sequence and/or flowchart). Highlight key findings with severity levels
and list open questions for the migration team.

Output JSON matching:
{format}
```

## Files to create

- `k8s/argocd/apps/vllm.yaml`
- `k8s/ml-serving/vllm/kustomization.yaml`
- `k8s/ml-serving/vllm/deployment.yaml`
- `k8s/ml-serving/vllm/service.yaml`
- `k8s/ml-serving/vllm/pvc.yaml`
- `libs/llm/build.gradle.kts`
- `libs/llm/src/main/java/com/flowforge/llm/config/LlmConfig.java`
- `libs/llm/src/main/java/com/flowforge/llm/prompt/PromptTemplateManager.java`
- `libs/llm/src/main/java/com/flowforge/llm/output/StructuredOutputService.java`
- `libs/llm/src/main/java/com/flowforge/llm/service/LlmGenerationService.java`
- `libs/llm/src/main/resources/prompts/flow-analysis.st`
- `libs/llm/src/main/resources/prompts/synthesis-stage1.st` ... `synthesis-stage6.st`
- `libs/llm/src/test/java/.../StructuredOutputServiceTest.java` (WireMock for vLLM)
- `libs/llm/src/test/java/.../LlmGenerationServiceTest.java`

## Depends on

- Stage 01 (config framework)

## Produces

- Spring AI `ChatModel` connected to vLLM (OpenAI-compatible)
- `BeanOutputConverter`-based structured output parsing
- Prompt template management with classpath resources
- Resilient generation with retry + circuit breaker
- Token usage tracking via Micrometer

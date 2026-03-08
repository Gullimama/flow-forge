package com.flowforge.parser.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.parser.analysis.ReactiveChainAnalyzer;
import com.flowforge.parser.model.ParsedClass;
import com.flowforge.parser.model.ParsedField;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ParserConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicronautCodeVisitorTest {

    private JavaParser javaParser;
    private MicronautCodeVisitor visitor;

    @BeforeEach
    void setUp() {
        javaParser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11));
        visitor = new MicronautCodeVisitor(new ReactiveChainAnalyzer());
    }

    @Test
    void visit_micronautController_extractsClassAndMethods() {
        String source = """
            package com.example;

            import io.micronaut.http.annotation.*;

            @Controller("/bookings")
            public class BookingController {
                @Get("/{id}")
                public Booking getBooking(String id) { return null; }

                @Post
                public Booking createBooking(@Body BookingRequest req) { return null; }
            }
            """;

        var classes = parseSource(source);

        assertThat(classes).hasSize(1);
        var clazz = classes.get(0);
        assertThat(clazz.fqn()).isEqualTo("com.example.BookingController");
        assertThat(clazz.annotations()).contains("Controller");
        assertThat(clazz.methods()).hasSize(2);
    }

    @Test
    void visit_extractsHttpMethodAndPath() {
        String source = """
            package com.example;
            import io.micronaut.http.annotation.*;

            @Controller("/api")
            public class ApiController {
                @Get("/bookings/{id}")
                public String get(String id) { return ""; }
            }
            """;

        var classes = parseSource(source);
        var method = classes.get(0).methods().get(0);

        assertThat(method.httpMethods()).containsExactly("GET");
        assertThat(method.httpPath()).get().isEqualTo("/bookings/{id}");
    }

    @Test
    void visit_innerClass_extractedSeparately() {
        String source = """
            package com.example;
            public class Outer {
                public static class Inner {
                    public void doWork() {}
                }
            }
            """;

        var classes = parseSource(source);

        assertThat(classes).hasSize(2);
        assertThat(classes).extracting(ParsedClass::simpleName)
            .containsExactlyInAnyOrder("Outer", "Inner");
    }

    @Test
    void visit_enumWithMethods_extractedCorrectly() {
        String source = """
            package com.example;
            public enum Status {
                ACTIVE, INACTIVE;
                public boolean isActive() { return this == ACTIVE; }
            }
            """;

        var classes = parseSource(source);

        assertThat(classes.get(0).classType()).isEqualTo(ParsedClass.ClassType.ENUM);
        assertThat(classes.get(0).methods()).hasSize(1);
    }

    @Test
    void visit_injectedFields_markedAsInjected() {
        String source = """
            package com.example;
            import jakarta.inject.Inject;
            public class MyService {
                @Inject BookingRepository repository;
                private String name;
            }
            """;

        var classes = parseSource(source);
        var fields = classes.get(0).fields();

        assertThat(fields).hasSize(2);
        assertThat(fields).filteredOn(ParsedField::isInjected).hasSize(1);
        assertThat(fields).filteredOn(ParsedField::isInjected)
            .extracting(ParsedField::name).containsExactly("repository");
    }

    private List<ParsedClass> parseSource(String source) {
        ParseResult<CompilationUnit> result = javaParser.parse(
            ParseStart.COMPILATION_UNIT, Providers.provider(source));
        assertThat(result.isSuccessful()).isTrue();
        var classes = new ArrayList<ParsedClass>();
        visitor.setCurrentFilePath("");
        result.getResult().orElseThrow().accept(visitor, classes);
        return classes;
    }
}

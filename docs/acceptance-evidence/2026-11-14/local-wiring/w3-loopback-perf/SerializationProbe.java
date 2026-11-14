import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.api.PageView;
import com.shitulelv.aicollab.work.application.view.TaskView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evidence-only probe: deserialize the captured HTTP response with the real record types,
 * then measure Jackson serialization alone (excluding DB, MVC, Tomcat, and client I/O).
 */
public final class SerializationProbe {
    private static final int WARMUP = 30;
    private static final int SAMPLES = 200;

    private SerializationProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: SerializationProbe <response.json> <result.json>");
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TypeReference<ApiResponse<PageView<TaskView>>> type = new TypeReference<>() {
        };
        ApiResponse<PageView<TaskView>> value = mapper.readValue(Path.of(args[0]).toFile(), type);

        for (int index = 0; index < WARMUP; index++) {
            mapper.writeValueAsBytes(value);
        }
        List<Long> nanos = new ArrayList<>();
        int bytes = 0;
        for (int index = 0; index < SAMPLES; index++) {
            long started = System.nanoTime();
            bytes = mapper.writeValueAsBytes(value).length;
            nanos.add(System.nanoTime() - started);
        }
        Collections.sort(nanos);
        String result = """
                {"samples":%d,"serializedBytes":%d,"p50Ms":%.3f,"p95Ms":%.3f,"p99Ms":%.3f}
                """.formatted(SAMPLES, bytes, ms(nanos, 0.50), ms(nanos, 0.95), ms(nanos, 0.99));
        Files.writeString(Path.of(args[1]), result);
        System.out.println(result);
    }

    private static double ms(List<Long> sortedNanos, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sortedNanos.size() * percentile) - 1);
        return sortedNanos.get(index) / 1_000_000.0;
    }
}

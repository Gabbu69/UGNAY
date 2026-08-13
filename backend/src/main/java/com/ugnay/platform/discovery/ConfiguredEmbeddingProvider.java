package com.ugnay.platform.discovery;

import com.ugnay.platform.shared.HeavyOperationCoordinator;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Local, checksum-gated multilingual-e5 inference with an explicit unavailable state. */
@Component
public final class ConfiguredEmbeddingProvider implements EmbeddingProvider {
    private final OrtEnvironment environment;
    private final Path model;
    private final Path tokenizerFile;
    private final String expectedModelSha256;
    private final String expectedTokenizerSha256;
    private final long minimumFreeMemoryBytes;
    private final long idleUnloadNanos;
    private final ScheduledExecutorService unloadScheduler;
    private final HeavyOperationCoordinator heavyOperations;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private volatile String reason;
    private volatile boolean ready;
    private volatile boolean configured;
    private volatile boolean permanentlyUnavailable;
    private volatile long lastUseNanos;

    public ConfiguredEmbeddingProvider(
            @Value("${ugnay.discovery.model-path:}") String configuredPath,
            @Value("${ugnay.discovery.model-sha256:}") String expectedModelSha256,
            @Value("${ugnay.discovery.tokenizer-path:}") String configuredTokenizerPath,
            @Value("${ugnay.discovery.tokenizer-sha256:}") String expectedTokenizerSha256,
            @Value("${ugnay.discovery.minimum-free-memory-bytes:0}") long minimumFreeMemoryBytes,
            @Value("${ugnay.discovery.idle-unload-seconds:120}") int idleUnloadSeconds,
            HeavyOperationCoordinator heavyOperations) {
        this.environment = OrtEnvironment.getEnvironment("ugnay-local-semantic");
        String modelValue = trim(configuredPath);
        this.model = modelValue.isBlank() ? null : Path.of(modelValue).toAbsolutePath();
        this.tokenizerFile = this.model == null ? null : (trim(configuredTokenizerPath).isBlank()
                ? this.model.getParent().resolve("tokenizer.json") : Path.of(trim(configuredTokenizerPath)).toAbsolutePath());
        this.expectedModelSha256 = trim(expectedModelSha256);
        this.expectedTokenizerSha256 = trim(expectedTokenizerSha256);
        this.minimumFreeMemoryBytes = Math.max(0, minimumFreeMemoryBytes);
        this.idleUnloadNanos = TimeUnit.SECONDS.toNanos(Math.max(30, idleUnloadSeconds));
        this.heavyOperations = heavyOperations;
        this.unloadScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ugnay-semantic-idle-unloader");
            thread.setDaemon(true);
            return thread;
        });
        inspectConfiguration();
    }

    @Override
    public synchronized Optional<double[]> embed(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        var lease = heavyOperations.tryAcquire("ONNX_INFERENCE");
        if (lease.isEmpty()) {
            reason = "Another local evidence operation is using the constrained-memory lane; semantic contribution is temporarily unavailable.";
            return Optional.empty();
        }
        try (var ignored = lease.orElseThrow()) {
            if (!ensureInitialized()) return Optional.empty();
            lastUseNanos = System.nanoTime();
            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            for (String inputName : session.getInputNames()) {
                if (inputName.equals("input_ids")) inputs.put(inputName, OnnxTensor.createTensor(environment, new long[][] { ids }));
                else if (inputName.equals("attention_mask")) inputs.put(inputName, OnnxTensor.createTensor(environment, new long[][] { mask }));
                else if (inputName.equals("token_type_ids")) inputs.put(inputName, OnnxTensor.createTensor(environment, new long[][] { typeIds }));
                else {
                    reason = "Unsupported ONNX input '" + inputName + "'; semantic contribution is disabled.";
                    ready = false;
                    closeInputs(inputs);
                    return Optional.empty();
                }
            }
            try (OrtSession.Result result = session.run(inputs)) {
                double[] vector = extractVector(result, mask);
                if (vector.length != 384) {
                    reason = "ONNX output has " + vector.length + " dimensions; expected 384 for multilingual-e5-small.";
                    ready = false;
                    return Optional.empty();
                }
                Optional<double[]> embedding = Optional.of(normalize(vector));
                scheduleIdleUnload();
                return embedding;
            } finally {
                closeInputs(inputs);
            }
        } catch (RuntimeException | OrtException exception) {
            reason = "Local ONNX inference failed safely: " + exception.getClass().getSimpleName() + ".";
            permanentlyUnavailable = true;
            ready = false;
            closeSession();
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return "intfloat/multilingual-e5-small (ONNX Runtime Java + local Hugging Face tokenizer)";
    }

    @Override
    public String availabilityReason() {
        return reason;
    }

    @Override
    public boolean available() {
        return configured && !permanentlyUnavailable;
    }

    @PreDestroy
    synchronized void close() {
        unloadScheduler.shutdownNow();
        closeSession();
    }

    private void inspectConfiguration() {
        if (model == null) {
            reason = "No local ONNX model path is configured; semantic contribution is unavailable.";
            return;
        }
        if (!Files.isRegularFile(model)) {
            reason = "Configured ONNX model file does not exist; semantic contribution is unavailable.";
            return;
        }
        if (!Files.isRegularFile(tokenizerFile)) {
            reason = "A local tokenizer.json was not found beside the ONNX model or at the configured tokenizer path.";
            return;
        }
        if (expectedModelSha256.isBlank() || expectedTokenizerSha256.isBlank()) {
            reason = "Pinned SHA-256 values are required for both model.onnx and tokenizer.json.";
            return;
        }
        configured = true;
        reason = "Semantic assets are configured and will be checksum-verified on demand.";
    }

    private boolean ensureInitialized() {
        if (ready) return true;
        if (!configured || permanentlyUnavailable) return false;
        if (!hasFreeMemory()) {
            reason = "Available physical memory is below the configured semantic safety threshold; lexical evidence remains available.";
            return false;
        }
        try {
            if (!constantTimeEquals(digest(model), expectedModelSha256)) {
                reason = "Configured ONNX model checksum does not match the pinned checksum.";
                permanentlyUnavailable = true;
                return false;
            }
            if (!constantTimeEquals(digest(tokenizerFile), expectedTokenizerSha256)) {
                reason = "Configured tokenizer checksum does not match the pinned checksum.";
                permanentlyUnavailable = true;
                return false;
            }
            Path tokenizerDirectory = tokenizerFile.toAbsolutePath().getParent();
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerDirectory);
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                options.addConfigEntry("session.intra_op.allow_spinning", "0");
                options.addConfigEntry("session.inter_op.allow_spinning", "0");
                session = environment.createSession(model.toAbsolutePath().toString(), options);
            }
            if (!session.getInputNames().contains("input_ids") || !session.getInputNames().contains("attention_mask")) {
                closeSession();
                reason = "The ONNX model does not expose the expected text-embedding inputs.";
                permanentlyUnavailable = true;
                return false;
            }
            ready = true;
            lastUseNanos = System.nanoTime();
            reason = "Local model and tokenizer checksums verified; semantic inference is available.";
            scheduleIdleUnload();
        } catch (Exception exception) {
            closeSession();
            reason = "Local semantic assets failed initialization: " + exception.getClass().getSimpleName() + ".";
            permanentlyUnavailable = true;
        }
        return ready;
    }

    private boolean hasFreeMemory() {
        if (minimumFreeMemoryBytes == 0) return true;
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getFreeMemorySize() >= minimumFreeMemoryBytes;
        }
        return true;
    }

    private void scheduleIdleUnload() {
        unloadScheduler.schedule(() -> {
            synchronized (ConfiguredEmbeddingProvider.this) {
                if (ready && System.nanoTime() - lastUseNanos >= idleUnloadNanos) {
                    closeSession();
                    reason = "Semantic assets are configured and unloaded after inactivity; they will reload on demand.";
                }
            }
        }, idleUnloadNanos, TimeUnit.NANOSECONDS);
    }

    private void closeSession() {
        ready = false;
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) { /* safe shutdown */ }
            session = null;
        }
    }

    private static double[] extractVector(OrtSession.Result result, long[] mask) throws OrtException {
        OnnxValue value = result.get("sentence_embedding").orElse(null);
        if (value == null) value = result.get("last_hidden_state").orElse(null);
        if (value == null) value = result.get(0);
        Object raw = value.getValue();
        if (raw instanceof float[][] pooled && pooled.length > 0) return toDouble(pooled[0]);
        if (raw instanceof double[][] pooled && pooled.length > 0) return pooled[0].clone();
        if (raw instanceof float[][][] hidden && hidden.length > 0) return meanPool(hidden[0], mask);
        if (raw instanceof double[][][] hidden && hidden.length > 0) return meanPool(hidden[0], mask);
        throw new OrtException("Unsupported embedding output shape: " + raw.getClass().getTypeName());
    }

    private static double[] meanPool(float[][] tokens, long[] mask) {
        if (tokens.length == 0) return new double[0];
        double[] result = new double[tokens[0].length];
        double count = 0;
        for (int token = 0; token < tokens.length && token < mask.length; token++) {
            if (mask[token] == 0) continue;
            count++;
            for (int dimension = 0; dimension < result.length; dimension++) result[dimension] += tokens[token][dimension];
        }
        if (count > 0) for (int i = 0; i < result.length; i++) result[i] /= count;
        return result;
    }

    private static double[] meanPool(double[][] tokens, long[] mask) {
        if (tokens.length == 0) return new double[0];
        double[] result = new double[tokens[0].length];
        double count = 0;
        for (int token = 0; token < tokens.length && token < mask.length; token++) {
            if (mask[token] == 0) continue;
            count++;
            for (int dimension = 0; dimension < result.length; dimension++) result[dimension] += tokens[token][dimension];
        }
        if (count > 0) for (int i = 0; i < result.length; i++) result[i] /= count;
        return result;
    }

    private static double[] normalize(double[] vector) {
        double norm = 0;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private static double[] toDouble(float[] source) {
        double[] result = new double[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i];
        return result;
    }

    private static String digest(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream source = new BufferedInputStream(Files.newInputStream(path));
             DigestInputStream stream = new DigestInputStream(source, digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    private static void closeInputs(Map<String, OnnxTensor> inputs) {
        inputs.values().forEach(tensor -> {
            try { tensor.close(); } catch (Exception ignored) { /* best effort */ }
        });
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}

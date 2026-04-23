package com.core.platform.applications.config;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.collections.CoreList;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.platform.shell.Shell;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Validates shell configuration at startup and publishes a config hash for drift detection.
 *
 * <p>Variables can be registered as required using the {@code require} and {@code requireAll} commands.
 * The {@code validate} command checks that all required variables are set and returns their status.
 * The {@code hash} command computes a deterministic hash of all configuration variables for drift detection
 * between nodes.
 */
public class ConfigValidator implements Encodable {

    private final Log log;
    private final Shell shell;
    private final List<String> requiredVariables;

    /**
     * Creates a {@code ConfigValidator} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param shell the shell containing configuration variables
     */
    public ConfigValidator(LogFactory logFactory, Shell shell) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        this.shell = Objects.requireNonNull(shell, "shell is null");
        this.log = logFactory.create(getClass());
        this.requiredVariables = new CoreList<>();
    }

    /**
     * Registers a variable as required.
     * If the variable is not currently set, a warning is logged.
     *
     * @param variableName the variable name to require
     */
    @Command(path = "require")
    public void require(String variableName) {
        if (!requiredVariables.contains(variableName)) {
            requiredVariables.add(variableName);
        }
        var value = shell.getPropertyValue(BufferUtils.fromAsciiString(variableName));
        if (value == null) {
            log.warn().append("required variable not set: ").append(variableName).commit();
        }
    }

    /**
     * Registers multiple variables as required.
     *
     * @param variableNames the variable names to require
     */
    @Command(path = "requireAll")
    public void requireAll(String... variableNames) {
        for (var variableName : variableNames) {
            require(variableName);
        }
    }

    /**
     * Validates all registered required variables.
     *
     * @param encoder the encoder to write results to
     */
    @Command(path = "validate", readOnly = true)
    public void validate(ObjectEncoder encoder) {
        encoder.openMap();
        for (var variableName : requiredVariables) {
            var value = shell.getPropertyValue(BufferUtils.fromAsciiString(variableName));
            encoder.string(variableName).string(value == null ? "MISSING" : "OK");
        }
        encoder.closeMap();
    }

    /**
     * Computes a deterministic hash of all set configuration variables.
     * Variables are sorted by key and concatenated as key=value pairs before hashing.
     * This is used for drift detection between nodes.
     *
     * @param encoder the encoder to write results to
     */
    @Command(path = "hash", readOnly = true)
    public void hash(ObjectEncoder encoder) {
        var sorted = new TreeMap<String, String>();
        var variables = shell.getVariables();
        for (var entry : variables.entrySet()) {
            sorted.put(BufferUtils.toAsciiString(entry.getKey()), BufferUtils.toAsciiString(entry.getValue()));
        }
        // FNV-1a hash
        var hash = 0xcbf29ce484222325L;
        for (var entry : sorted.entrySet()) {
            var pair = entry.getKey() + "=" + entry.getValue() + "\n";
            var bytes = pair.getBytes(StandardCharsets.UTF_8);
            for (var b : bytes) {
                hash ^= b & 0xFF;
                hash *= 0x100000001b3L;
            }
        }
        encoder.openMap()
                .string("hash").number(hash)
                .closeMap();
    }

    /**
     * Dumps all current shell variables as a sorted map.
     *
     * @param encoder the encoder to write results to
     */
    @Command(path = "dump", readOnly = true)
    public void dump(ObjectEncoder encoder) {
        encoder.openMap();
        var variables = shell.getVariables();
        var sortedKeys = new TreeMap<String, String>();
        for (var entry : variables.entrySet()) {
            sortedKeys.put(BufferUtils.toAsciiString(entry.getKey()), BufferUtils.toAsciiString(entry.getValue()));
        }
        for (var entry : sortedKeys.entrySet()) {
            encoder.string(entry.getKey()).string(entry.getValue());
        }
        encoder.closeMap();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        var allOk = true;
        for (var variableName : requiredVariables) {
            var value = shell.getPropertyValue(BufferUtils.fromAsciiString(variableName));
            if (value == null) {
                allOk = false;
                break;
            }
        }

        encoder.openMap()
                .string("valid").bool(allOk)
                .string("requiredCount").number(requiredVariables.size())
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}

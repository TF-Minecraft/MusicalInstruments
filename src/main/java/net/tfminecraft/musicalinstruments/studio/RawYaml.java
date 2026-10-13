package net.tfminecraft.musicalinstruments.studio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Bukkit's configuration setter removes nulls; raw project extension maps must keep them. */
final class RawYaml {
    private RawYaml() {}

    static Map<String, Object> read(byte[] bytes) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(StudioStore.MAX_FILE_BYTES);
        options.setAllowDuplicateKeys(false);
        try {
            Object value = new Yaml(new SafeConstructor(options)).load(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            if (!(value instanceof Map<?, ?> map)) throw new IOException("Project document is not a mapping");
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IOException("Project property name is not text");
                result.put(key, entry.getValue());
            }
            return result;
        } catch (YAMLException ex) { throw new IOException("Unreadable project YAML", ex); }
    }

    static byte[] bytes(Map<String, Object> values) { return new Yaml().dump(values).getBytes(StandardCharsets.UTF_8); }
}

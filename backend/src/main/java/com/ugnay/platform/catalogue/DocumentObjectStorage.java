package com.ugnay.platform.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

public interface DocumentObjectStorage {
    StoredObject store(String objectKey, Path source, String contentType, Map<String, String> metadata) throws IOException;

    InputStream open(String objectKey) throws IOException;

    record StoredObject(String objectKey, String etag) {}
}

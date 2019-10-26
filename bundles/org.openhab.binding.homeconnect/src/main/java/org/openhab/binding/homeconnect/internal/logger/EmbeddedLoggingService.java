/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homeconnect.internal.logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.storage.Storage;
import org.eclipse.smarthome.core.storage.StorageService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 *
 * Central logging service. Use default logging system and also persist in separate storage.
 *
 * @author Jonas Brüstel - Initial Contribution
 */
@NonNullByDefault
@Component(service = EmbeddedLoggingService.class, scope = ServiceScope.SINGLETON, configurationPid = "binding.homeconnect")
public class EmbeddedLoggingService {

    private static final String STORAGE_NAME = "homeconnect";
    private static final String KEY_EMBEDDED_LOGGING = "embeddedLogging";

    private final Storage<String> storage;
    private final Gson gson;
    private final org.slf4j.Logger logger = LoggerFactory.getLogger(EmbeddedLoggingService.class);

    private boolean loggingEnabled;

    @Activate
    public EmbeddedLoggingService(@Reference StorageService storageService) {
        storage = storageService.getStorage(STORAGE_NAME);
        gson = new GsonBuilder().create();
    }

    @Activate
    protected void activate(ComponentContext componentContext) {
        Object value = componentContext.getProperties().get(KEY_EMBEDDED_LOGGING);
        loggingEnabled = (value != null && (boolean) value);

        logger.info("Activated embedded logging service. file logging={}", loggingEnabled);
    }

    public Logger getLogger(Class<?> clazz) {
        return new Logger(clazz, loggingEnabled, storage);
    }

    public List<Log> getLogEntries() {
        try {
            return storage.stream().sorted((o1, o2) -> o1.getKey().compareTo(o2.getKey())).map(e -> {
                String serializedObject = e.getValue();
                if (serializedObject == null) {
                    throw new RuntimeException("Empty object in log storage");
                }
                return deserialize(serializedObject);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            clear();
            return new ArrayList<Log>();
        }
    }

    public void clear() {
        storage.getKeys().forEach(key -> storage.remove(key));
    }

    private Log deserialize(String serialized) {
        try {
            return gson.fromJson(serialized, Log.class);
        } catch (Exception e) {
            logger.error("Could not deserialize log entry. error={}\n{}", e.getMessage(), serialized);
            throw e;
        }
    }
}

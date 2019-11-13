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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.storage.Storage;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;
import org.openhab.binding.homeconnect.internal.client.HomeConnectSseClient;
import org.openhab.binding.homeconnect.internal.factory.HomeConnectHandlerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 *
 * Home Connect logger.
 *
 * @author Jonas Brüstel - Initial Contribution
 */
public class LogWriter {

    private @NonNullByDefault org.slf4j.Logger slf4jLogger;
    private final Gson gson;
    private final Storage<String> storage;
    private final String className;
    private final AtomicLong atomicLong;
    private boolean loggingEnabled;

    protected LogWriter(Class<?> clazz, boolean loggingEnabled, Storage<String> storage, AtomicLong atomicLong) {
        this.slf4jLogger = LoggerFactory.getLogger(clazz);
        this.storage = storage;
        this.className = clazz.getSimpleName();
        this.loggingEnabled = loggingEnabled;
        this.atomicLong = atomicLong;

        gson = new GsonBuilder().create();
    }

    public void log(Type type, Level level, String haId, String label, List<String> details,
            org.openhab.binding.homeconnect.internal.logger.Request request,
            org.openhab.binding.homeconnect.internal.logger.Response response, String message, Object... arguments) {
        FormattingTuple messageTuple = formatLog(message, arguments);

        writeLog(new Log(System.currentTimeMillis(), className, type, level, messageTuple.getMessage(), haId, label,
                details, request, response), messageTuple.getThrowable());
    }

    public void trace(String message) {
        log(Type.DEFAULT, Level.TRACE, null, null, null, null, null, message);
    }

    public void debug(String message) {
        log(Type.DEFAULT, Level.DEBUG, null, null, null, null, null, message);
    }

    public void info(String message) {
        log(Type.DEFAULT, Level.INFO, null, null, null, null, null, message);
    }

    public void warn(String message) {
        log(Type.DEFAULT, Level.WARN, null, null, null, null, null, message);
    }

    public void error(String message) {
        log(Type.DEFAULT, Level.ERROR, null, null, null, null, null, message);
    }

    public void traceWithLabel(String label, String message) {
        log(Type.DEFAULT, Level.TRACE, null, label, null, null, null, message);
    }

    public void debugWithLabel(String label, String message) {
        log(Type.DEFAULT, Level.DEBUG, null, label, null, null, null, message);
    }

    public void infoWithLabel(String label, String message) {
        log(Type.DEFAULT, Level.INFO, null, label, null, null, null, message);
    }

    public void warnWithLabel(String label, String message) {
        log(Type.DEFAULT, Level.WARN, null, label, null, null, null, message);
    }

    public void errorWithLabel(String label, String message) {
        log(Type.DEFAULT, Level.ERROR, null, label, null, null, null, message);
    }

    public void traceWithHaId(String haId, String message) {
        log(Type.DEFAULT, Level.TRACE, haId, null, null, null, null, message);
    }

    public void debugWithHaId(String haId, String message) {
        log(Type.DEFAULT, Level.DEBUG, haId, null, null, null, null, message);
    }

    public void infoWithHaId(String haId, String message) {
        log(Type.DEFAULT, Level.INFO, haId, null, null, null, null, message);
    }

    public void warnWithHaId(String haId, String message) {
        log(Type.DEFAULT, Level.WARN, haId, null, null, null, null, message);
    }

    public void errorWithHaId(String haId, String message) {
        log(Type.DEFAULT, Level.ERROR, haId, null, null, null, null, message);
    }

    public void debug(String message, Object... arguments) {
        log(Type.DEFAULT, Level.DEBUG, null, null, null, null, null, message, arguments);
    }

    public void info(String message, Object... arguments) {
        log(Type.DEFAULT, Level.INFO, null, null, null, null, null, message, arguments);
    }

    public void trace(String message, Object... arguments) {
        log(Type.DEFAULT, Level.TRACE, null, null, null, null, null, message, arguments);
    }

    public void warn(String message, Object... arguments) {
        log(Type.DEFAULT, Level.WARN, null, null, null, null, null, message, arguments);
    }

    public void error(String message, Object... arguments) {
        log(Type.DEFAULT, Level.ERROR, null, null, null, null, null, message, arguments);
    }

    public void debugWithLabel(String label, String message, Object... arguments) {
        log(Type.DEFAULT, Level.DEBUG, null, label, null, null, null, message, arguments);
    }

    public void infoWithLabel(String label, String message, Object... arguments) {
        log(Type.DEFAULT, Level.INFO, null, label, null, null, null, message, arguments);
    }

    public void traceWithLabel(String label, String message, Object... arguments) {
        log(Type.DEFAULT, Level.TRACE, null, label, null, null, null, message, arguments);
    }

    public void warnWithLabel(String label, String message, Object... arguments) {
        log(Type.DEFAULT, Level.WARN, null, label, null, null, null, message, arguments);
    }

    public void errorWithLabel(String label, String message, Object... arguments) {
        log(Type.DEFAULT, Level.ERROR, null, label, null, null, null, message, arguments);
    }

    public void debugWithHaId(String haId, String message, Object... arguments) {
        log(Type.DEFAULT, Level.DEBUG, haId, null, null, null, null, message, arguments);
    }

    public void infoWithHaId(String haId, String message, Object... arguments) {
        log(Type.DEFAULT, Level.INFO, haId, null, null, null, null, message, arguments);
    }

    public void traceWithHaId(String haId, String message, Object... arguments) {
        log(Type.DEFAULT, Level.TRACE, haId, null, null, null, null, message, arguments);
    }

    public void warnWithHaId(String haId, String message, Object... arguments) {
        log(Type.DEFAULT, Level.WARN, haId, null, null, null, null, message, arguments);
    }

    public void errorWithHaId(String haId, String message, Object... arguments) {
        log(Type.DEFAULT, Level.ERROR, haId, null, null, null, null, message, arguments);
    }

    private void writeLog(Log entry, Throwable throwable) {
        // log to storage
        try {
            if (loggingEnabled) {
                String key = entry.getCreated() + "-" + atomicLong.getAndIncrement();
                storage.put(key, serialize(entry));
            }
        } catch (Exception e) {
            slf4jLogger.error("Could not persist to extended log system. error={}  entry={}", e.getMessage(), entry);
        }

        // log to normal logger
        if ((Level.ERROR == entry.getLevel() && slf4jLogger.isDebugEnabled())
                || (Level.WARN == entry.getLevel() && slf4jLogger.isWarnEnabled())
                || (Level.DEBUG == entry.getLevel() && slf4jLogger.isDebugEnabled())
                || (Level.INFO == entry.getLevel() && slf4jLogger.isInfoEnabled())
                || (Level.TRACE == entry.getLevel() && slf4jLogger.isTraceEnabled())) {
            String identifier;
            if (entry.getType() == Type.API_CALL) {
                identifier = "API_CALL";
            } else if (entry.getType() == Type.API_ERROR) {
                identifier = "API_ERROR";
            } else {
                if (HomeConnectSseClient.class.getSimpleName().equals(entry.getClassName())) {
                    identifier = "SSE";
                } else if (HomeConnectHandlerFactory.class.getSimpleName().equals(entry.getClassName())) {
                    identifier = "FACTORY";
                } else if (HomeConnectApiClient.class.getSimpleName().equals(entry.getClassName())) {
                    identifier = "API";
                } else if (entry.getClassName() != null && entry.getClassName().endsWith("BridgeHandler")) {
                    identifier = "BRIDGE";
                } else if (entry.getClassName() != null && entry.getClassName().endsWith("Handler")) {
                    identifier = "HANDLER";
                } else {
                    identifier = "MISC";
                }
            }

            if (entry.getLabel() != null) {
                identifier = identifier + " " + entry.getLabel();
            } else if (entry.getHaId() != null) {
                identifier = identifier + " " + entry.getHaId();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[{}] ");

            if (entry.getType() == Type.API_CALL || entry.getType() == Type.API_ERROR) {
                sb.append(entry.getRequest().getMethod()).append(" ");
                if (entry.getResponse() != null) {
                    sb.append(entry.getResponse().getCode()).append(" ");
                }
                sb.append(entry.getRequest().getUrl()).append("\n");
                entry.getRequest().getHeader()
                        .forEach((key, value) -> sb.append("> ").append(key).append(": ").append(value).append("\n"));

                if (entry.getRequest() != null && entry.getRequest().getBody() != null) {
                    sb.append(entry.getRequest().getBody()).append("\n");
                }

                if (entry.getResponse() != null && entry.getResponse().getHeader() != null) {
                    sb.append("\n");
                    entry.getResponse().getHeader().forEach(
                            (key, value) -> sb.append("< ").append(key).append(": ").append(value).append("\n"));
                }
                if (entry.getResponse() != null && entry.getResponse().getBody() != null) {
                    sb.append(entry.getResponse().getBody()).append("\n");
                }
            } else {
                sb.append(entry.getMessage());
            }

            if (entry.getDetails() != null) {
                entry.getDetails().forEach(detail -> sb.append("\n").append(detail));
            }

            String format = sb.toString();
            switch (entry.getLevel()) {
                case TRACE:
                    if (throwable == null) {
                        slf4jLogger.trace(format, identifier);
                    } else {
                        slf4jLogger.trace(format, identifier, throwable);
                    }
                    break;
                case DEBUG:
                    if (throwable == null) {
                        slf4jLogger.debug(format, identifier);
                    } else {
                        slf4jLogger.debug(format, identifier, throwable);
                    }
                    break;
                case INFO:
                    if (throwable == null) {
                        slf4jLogger.info(format, identifier);
                    } else {
                        slf4jLogger.info(format, identifier, throwable);
                    }
                    break;
                case WARN:
                    if (throwable == null) {
                        slf4jLogger.warn(format, identifier);
                    } else {
                        slf4jLogger.warn(format, identifier, throwable);
                    }
                    break;
                case ERROR:
                    if (throwable == null) {
                        slf4jLogger.error(format, identifier);
                    } else {
                        slf4jLogger.error(format, identifier, throwable);
                    }
                    break;
            }
        }
    }

    private String serialize(Log entry) {
        return gson.toJson(entry);
    }

    private FormattingTuple formatLog(String format, Object... arguments) {
        return MessageFormatter.arrayFormat(format, arguments);
    }
}

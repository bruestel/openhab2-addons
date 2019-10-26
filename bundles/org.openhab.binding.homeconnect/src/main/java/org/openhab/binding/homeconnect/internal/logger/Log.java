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

import org.slf4j.event.Level;

/**
 *
 * Log entry.
 *
 * @author Jonas Brüstel - Initial Contribution
 */
public class Log {

    private long created;
    private String className;
    private Type type;
    private Level level;
    private String message;
    private String haId;
    private String label;
    private List<String> details;
    private Request request;
    private Response response;

    public Log(long created, String className, Type type, Level level, String message, String haId, String label,
            List<String> details, Request request, Response response) {
        this.created = created;
        this.className = className;
        this.type = type;
        this.level = level;
        this.message = message;
        this.haId = haId;
        this.label = label;
        this.details = details;
        this.request = request;
        this.response = response;
    }

    public long getCreated() {
        return created;
    }

    public String getClassName() {
        return className;
    }

    public Type getType() {
        return type;
    }

    public Level getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public String getHaId() {
        return haId;
    }

    public String getLabel() {
        return label;
    }

    public List<String> getDetails() {
        return details;
    }

    public Request getRequest() {
        return request;
    }

    public Response getResponse() {
        return response;
    }

    @Override
    public String toString() {
        return "Log [created=" + created + ", className=" + className + ", type=" + type + ", level=" + level
                + ", message=" + message + ", haId=" + haId + ", label=" + label + ", details=" + details + ", request="
                + request + ", response=" + response + "]";
    }
}

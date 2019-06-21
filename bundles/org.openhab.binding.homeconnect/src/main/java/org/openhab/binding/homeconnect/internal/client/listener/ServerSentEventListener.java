/**
 * Copyright (c) 2018-2019 Contributors to the openHAB project
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
package org.openhab.binding.homeconnect.internal.client.listener;

import org.eclipse.jdt.annotation.NonNull;
import org.openhab.binding.homeconnect.internal.client.model.Event;

/**
 * {@link ServerSentEventListener} inform about new events from Home Connect SSE interface.
 *
 * @author Jonas Brüstel - Initial contribution
 */
public interface ServerSentEventListener {

    /**
     * Home appliance id of interest
     *
     * @return
     */
    String haId();

    /**
     * Inform listener about new event
     *
     * @param event
     */
    void onEvent(@NonNull Event event);

    /**
     * If SSE client did a reconnect
     */
    void onReconnect();
}

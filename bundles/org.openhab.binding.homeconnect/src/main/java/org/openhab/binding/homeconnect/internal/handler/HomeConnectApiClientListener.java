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
package org.openhab.binding.homeconnect.internal.handler;

import org.eclipse.jdt.annotation.NonNull;
import org.openhab.binding.homeconnect.internal.client.HomeConnectApiClient;

/**
 * {@link org.eclipse.smarthome.core.thing.binding.ThingHandler} which implement {@link HomeConnectApiClientListener}
 * will be informed about new
 * {@link HomeConnectApiClient}
 * instances by there parent ({@link HomeConnectBridgeHandler}).
 *
 * @author Jonas Brüstel - Initial contribution
 */
public interface HomeConnectApiClientListener {

    void refreshApiClient(@NonNull HomeConnectApiClient apiClient);

}

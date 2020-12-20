/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.homeconnect.internal.type;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.binding.BaseDynamicStateDescriptionProvider;
import org.eclipse.smarthome.core.thing.type.DynamicStateDescriptionProvider;
import org.osgi.service.component.annotations.Component;

/**
 * The {@link HomeConnectDynamicStateDescriptionProvider} is responsible for handling dynamic values.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@Component(service = { DynamicStateDescriptionProvider.class, HomeConnectDynamicStateDescriptionProvider.class })
@NonNullByDefault
public class HomeConnectDynamicStateDescriptionProvider extends BaseDynamicStateDescriptionProvider {

}

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
package org.openhab.binding.homeconnect.internal.client.model;

/**
 * Option model
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class Option {

    private String key;
    private String value;
    private String unit;

    public Option(String key, String value, String unit) {
        this.key = key;
        this.value = value;
        this.unit = unit;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public boolean getValueAsBoolean() {
        return value != null ? Boolean.valueOf(getValue()).booleanValue() : false;
    }

    public int getValueAsInt() {
        return value != null ? Integer.valueOf(getValue()).intValue() : 0;
    }

    public String getUnit() {
        return unit;
    }

    @Override
    public String toString() {
        return "Option [key=" + key + ", value=" + value + ", unit=" + unit + "]";
    }

}

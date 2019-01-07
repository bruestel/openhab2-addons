/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.homeconnect.internal;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link HomeConnectBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Jonas Brüstel - Initial contribution
 */
@NonNullByDefault
public class HomeConnectBindingConstants {

    public static final String BINDING_ID = "homeconnect";

    public static final String HA_ID = "haId";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_API_BRIDGE = new ThingTypeUID(BINDING_ID, "api_bridge");
    public static final ThingTypeUID THING_TYPE_DISHWASHER = new ThingTypeUID(BINDING_ID, "dishwasher");
    public static final ThingTypeUID THING_TYPE_OVEN = new ThingTypeUID(BINDING_ID, "oven");
    public static final ThingTypeUID THING_TYPE_WASHER = new ThingTypeUID(BINDING_ID, "washer");
    public static final ThingTypeUID THING_TYPE_FRIDGE_FREEZER = new ThingTypeUID(BINDING_ID, "fridgefreezer");
    public static final ThingTypeUID THING_TYPE_DRYER = new ThingTypeUID(BINDING_ID, "dryer");
    public static final ThingTypeUID THING_TYPE_COFFEE_MAKER = new ThingTypeUID(BINDING_ID, "coffeemaker");
    public static final ThingTypeUID THING_TYPE_HOB = new ThingTypeUID(BINDING_ID, "hob");

    // SSE Event types
    public static final String EVENT_ELAPSED_PROGRAM_TIME = "BSH.Common.Option.ElapsedProgramTime";
    public static final String EVENT_OVEN_CAVITY_TEMPERATURE = "Cooking.Oven.Status.CurrentCavityTemperature";
    public static final String EVENT_POWER_STATE = "BSH.Common.Setting.PowerState";
    public static final String EVENT_CONNECTED = "CONNECTED";
    public static final String EVENT_DISCONNECTED = "DISCONNECTED";
    public static final String EVENT_DOOR_STATE = "BSH.Common.Status.DoorState";
    public static final String EVENT_OPERATION_STATE = "BSH.Common.Status.OperationState";
    public static final String EVENT_ACTIVE_PROGRAM = "BSH.Common.Root.ActiveProgram";
    public static final String EVENT_SELECTED_PROGRAM = "BSH.Common.Root.SelectedProgram";
    public static final String EVENT_REMOTE_CONTROL_START_ALLOWED = "BSH.Common.Status.RemoteControlStartAllowed";
    public static final String EVENT_REMOTE_CONTROL_ACTIVE = "BSH.Common.Status.RemoteControlActive";
    public static final String EVENT_LOCAL_CONTROL_ACTIVE = "BSH.Common.Status.LocalControlActive";
    public static final String EVENT_REMAINING_PROGRAM_TIME = "BSH.Common.Option.RemainingProgramTime";
    public static final String EVENT_PROGRAM_PROGRESS = "BSH.Common.Option.ProgramProgress";
    public static final String EVENT_SETPOINT_TEMPERATURE = "Cooking.Oven.Option.SetpointTemperature";
    public static final String EVENT_DURATION = "BSH.Common.Option.Duration";
    public static final String EVENT_WASHER_TEMPERATURE = "LaundryCare.Washer.Option.Temperature";
    public static final String EVENT_WASHER_SPIN_SPEED = "LaundryCare.Washer.Option.SpinSpeed";
    public static final String EVENT_FREEZER_SETPOINT_TEMPERATURE = "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer";
    public static final String EVENT_FRIDGE_SETPOINT_TEMPERATURE = "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator";
    public static final String EVENT_FREEZER_SUPER_MODE = "Refrigeration.FridgeFreezer.Setting.SuperModeFreezer";
    public static final String EVENT_FRIDGE_SUPER_MODE = "Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator";
    public static final String EVENT_DRYER_DRYING_TARGET = "LaundryCare.Dryer.Option.DryingTarget";
    public static final String EVENT_PROGRAM_FINISHED = "BSH.Common.Event.ProgramFinished";
    public static final String EVENT_ALARM_ELAPSED = "BSH.Common.Event.AlarmClockElapsed";
    public static final String EVENT_PREHEAT_FINISHED = "Cooking.Oven.Event.PreheatFinished";
    


    // Channel IDs
    public static final String CHANNEL_DOOR_STATE = "door_state";
    public static final String CHANNEL_ELAPSED_PROGRAM_TIME = "elapsed_program_time";
    public static final String CHANNEL_POWER_STATE = "power_state";
    public static final String CHANNEL_OPERATION_STATE = "operation_state";
    public static final String CHANNEL_ACTIVE_PROGRAM_STATE = "active_program_state";
    public static final String CHANNEL_SELECTED_PROGRAM_STATE = "selected_program_state";
    public static final String CHANNEL_REMOTE_START_ALLOWANCE_STATE = "remote_start_allowance_state";
    public static final String CHANNEL_REMOTE_CONTROL_ACTIVE_STATE = "remote_control_active_state";
    public static final String CHANNEL_LOCAL_CONTROL_ACTIVE_STATE = "local_control_active_state";
    public static final String CHANNEL_REMAINING_PROGRAM_TIME_STATE = "remaining_program_time_state";
    public static final String CHANNEL_PROGRAM_PROGRESS_STATE = "program_progress_state";
    public static final String CHANNEL_OVEN_CURRENT_CAVITY_TEMPERATURE = "oven_current_cavity_temperature";
    public static final String CHANNEL_SETPOINT_TEMPERATURE = "setpoint_temperature";
    public static final String CHANNEL_DURATION = "duration";
    public static final String CHANNEL_WASHER_TEMPERATURE = "laundry_care_washer_temperature";
    public static final String CHANNEL_WASHER_SPIN_SPEED = "laundry_care_washer_spin_speed";
    public static final String CHANNEL_REFRIDGERATOR_SETPOINT_TEMPERATURE = "setpoint_temperature_refridgerator";
    public static final String CHANNEL_REFRIDGERATOR_SUPER_MODE = "super_mode_refrigerator";
    public static final String CHANNEL_FREEZER_SETPOINT_TEMPERATURE = "setpoint_temperature_freezer";
    public static final String CHANNEL_FREEZER_SUPER_MODE = "super_mode_freezer";
    public static final String CHANNEL_DRYER_DRYING_TARGET = "dryer_drying_target";

    // List of all supported devices
    public static final Set<ThingTypeUID> SUPPORTED_DEVICE_THING_TYPES_UIDS = Stream
            .of(THING_TYPE_API_BRIDGE, THING_TYPE_DISHWASHER, THING_TYPE_OVEN, THING_TYPE_WASHER, THING_TYPE_DRYER,
                    THING_TYPE_FRIDGE_FREEZER, THING_TYPE_COFFEE_MAKER, THING_TYPE_HOB)
            .collect(Collectors.toSet());

    // Discoverable devices
    public static final Set<ThingTypeUID> DISCOVERABLE_DEVICE_THING_TYPES_UIDS = Stream.of(THING_TYPE_DISHWASHER,
            THING_TYPE_OVEN, THING_TYPE_WASHER, THING_TYPE_DRYER, THING_TYPE_FRIDGE_FREEZER, THING_TYPE_COFFEE_MAKER,
            THING_TYPE_HOB)
            .collect(Collectors.toSet());

    // List of state values
    public static final String STATE_POWER_OFF = "BSH.Common.EnumType.PowerState.Off";
    public static final String STATE_POWER_ON = "BSH.Common.EnumType.PowerState.On";
    public static final String STATE_POWER_STANDBY = "BSH.Common.EnumType.PowerState.Standby";
    public static final String STATE_DOOR_OPEN = "BSH.Common.EnumType.DoorState.Open";
    public static final String STATE_DOOR_LOCKED = "BSH.Common.EnumType.DoorState.Locked";
    public static final String STATE_DOOR_CLOSED = "BSH.Common.EnumType.DoorState.Closed";

    // List of program options
    public static final String OPTION_REMAINING_PROGRAM_TIME = "BSH.Common.Option.RemainingProgramTime";
    public static final String OPTION_PROGRAM_PROGRESS = "BSH.Common.Option.ProgramProgress";
    public static final String OPTION_ELAPSED_PROGRAM_TIME = "BSH.Common.Option.ElapsedProgramTime";
    public static final String OPTION_SETPOINT_TEMPERATURE = "Cooking.Oven.Option.SetpointTemperature";
    public static final String OPTION_DURATION = "BSH.Common.Option.Duration";
    public static final String OPTION_WASHER_TEMPERATURE = "LaundryCare.Washer.Option.Temperature";
    public static final String OPTION_WASHER_SPIN_SPEED = "LaundryCare.Washer.Option.SpinSpeed";
    public static final String OPTION_DRYER_DRYING_TARGET = "LaundryCare.Dryer.Option.DryingTarget";

}

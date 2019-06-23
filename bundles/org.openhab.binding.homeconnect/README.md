# HomeConnect Binding

The binding integrates the [Home Connect](https://www.home-connect.com/) system into openHAB.
It uses the Home Connect API to connect to household devices (Bosch and Siemens). 

As all status updates and commands have to go through the API, a permanent internet connection is required.

Supported devices: dishwasher, washer, dryer, oven, refrigerator/freezer, coffee machine

## Supported Things

### Bridge

The __Home Connect API__ is responsible for the communication with the Home Connect API. All devices use this bridge to execute commands and listen for updates. Without a working bridge the devices cannot communicate.

### Devices

Currently dishwashers, washing machines, dryers, refrigerators/freezers, coffee machines and ovens are supported.

## Discovery

After the bridge has been added, devices are discovered automatically.


## Channels

| Channel Type ID | Item Type    | Read only | Description  | Available on thing |
| --------------- | ------------ | --------- | ------------ | ------------------ |
| power_state | Switch | false | This setting describes the current power state of the home appliance. | dishwasher | 
| power_state_read_only | true | Switch | This setting describes the current power state of the home appliance (read only). | oven | 
| door_state | Contact | true | This status describes the state of the door of the home appliance. A change of that status is either triggered by the user operating the home appliance locally (i.e. opening/closing door) or automatically by the home appliance (i.e. locking the door). | dishwasher, washer, dryer, oven, refrigerator/freezer, coffeemaker | 
| operation_state | String | true | This status describes the operation state of the home appliance. | dishwasher, washer, dryer, oven, coffeemaker | 
| remote_start_allowance_state | Switch | true  | This status indicates whether the remote program start is enabled. This can happen due to a programmatic change (only disabling), or manually by the user changing the flag locally on the home appliance, or automatically after a certain duration - usually 24 hours. | dishwasher, washer, dryer, oven, coffeemaker | 
| remote_control_active_state | Switch | true  | This status indicates whether the allowance for remote controlling is enabled. | dishwasher, washer, dryer, oven | 
| active_program_state | String | true  | This status describes the active program of the home appliance. | dishwasher, washer, dryer, oven, coffeemaker | 
| selected_program_state | String | true | This status describes the selected program of the home appliance. | dishwasher, washer, dryer, oven, coffeemaker | 
| remaining_program_time_state | Number:Time | true | This status indicates the remaining program time of the home appliance. | dishwasher, washer, dryer, oven | 
| elapsed_program_time | Number:Time | true | This status indicates the elapsed program time of the home appliance. | oven | 
| program_progress_state | Number:Dimensionless | true | This status describes the program progress of the home appliance. | dishwasher, washer, dryer, oven, coffeemaker | 
| duration | Number:Time | true | This status describes the duration of the program of the home appliance. | oven | 
| current_cavity_temperature | Number:Temperature | true | This status describes the current cavity temperature of the home appliance. | oven | 
| setpoint_temperature | Number:Temperature | true | This status describes the setpoint/target temperature of the home appliance. | oven | 
| laundry_care_washer_temperature | String | true | This status describes describes the temperature of the washing program of the home appliance. | washer | 
| laundry_care_washer_spin_speed | String | true | This status defines the spin speed of a washer program of the home appliance. | washer | 
| dryer_drying_target | String | true | This status defines the desired dryness of a program of the home appliance. | dryer | 
| setpoint_temperature_refridgerator | Number:Temperature | false | Target temperature of the refrigerator compartment (Range depends on appliance - common range 2 to 8°C). | refrigerator/freezer | 
| setpoint_temperature_freezer | Number:Temperature | false | Target temperature of the freezer compartment (Range depends on appliance - common range -16 to -24°C). | refrigerator/freezer | 
| super_mode_refrigerator | Switch | true | The setting has no impact on setpoint temperatures but will make the fridge compartment cool to the lowest possible temperature until it is disabled by the manually by the customer or by the HA because of a timeout. | refrigerator/freezer | 
| super_mode_freezer | Switch | true | This setting has no impact on setpoint temperatures but will make the freezer compartment cool to the lowest possible temperature until it is disabled by the manually by the customer or by the home appliance because of a timeout. | refrigerator/freezer | 
            
## Thing Configuration

### Configuring the __Home Connect API__ Bridge


#### Physical appliance

##### 1. Preconditions

1. Please create an account at [Home Connect](https://www.home-connect.com/) and add your physical appliance to your account.
2. Test the connection to your physical appliance via mobile app ([Apple App Store (iOS) ](https://itunes.apple.com/de/app/home-connect-app/id901397789?mt=8) or [Google Play Store (Android)](https://play.google.com/store/apps/details?id=com.bshg.homeconnect.android.release)).

##### 2. Create oAuth2 tokens (Device Flow)

1. Create an account at [https://developer.home-connect.com](https://developer.home-connect.com) and login.
2. Create an application at [https://developer.home-connect.com/applications](https://developer.home-connect.com/applications)
    * _Application ID_: e.g. `openhab-binding`
    * _OAuth Flow_: Device Flow
    * _Home Connect User Account for Testing_: the associated user account email from [Home Connect](https://www.home-connect.com/) **_Please don't use your developer account username_**
3. Now you should see the client id and secret of the application. Please save them for later.
4. Use `curl` or equivalent method to start the authorization. Please replace `[client id]` with your application client id.

Curl call:

```
curl -X POST \
  https://api.home-connect.com/security/oauth/device_authorization \
  -H 'Cache-Control: no-cache' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'client_id=[client id]&scope=IdentifyAppliance%20Monitor%20Settings'
```

Response:

```
{
    "expires_in": 300,
    "device_code": "xxxxx-yyy-44fa-ff5-5b82c6348",
    "user_code": "0123-4567",
    "verification_uri": "https://verify.home-connect.com",
    "interval": 5,
    "verification_uri_complete": "https://verify.home-connect.com?user_code=0123-4567"
}
```

Please save the `device_code` and the `verification_uri_complete` for later use.

5. Open the `verification_uri_complete` link in a web browser. You can now login with your [Home Connect](https://www.home-connect.com/) account and grant access. **_Please don't use your developer account credentials**
6. Use `curl` or equivalent method to get the oAuth token. Please replace `[client id]` and `[client secret]` with your application credentials. For `[device code]` use response data from step 4. 

Curl call:

```
curl -X POST \
  https://api.home-connect.com/security/oauth/token \
  -H 'Cache-Control: no-cache' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'client_id=[client id]&client_secret=[client secret]&grant_type=device_code&device_code=[device code]'
```

Response:

```
{
    "id_token": "xyz",
    "access_token": "abc",
    "expires_in": 86400,
    "scope": "IdentifyAppliance Monitor Settings",
    "refresh_token": "123456",
    "token_type": "Bearer"
}
```

Please save `refresh_token` for later use in Paper UI. According to the API documentation the token expires if it wasn't used within 2 months. These means once setup, the configuration should work without recreating tokens manually.

##### 3. Setup Paper UI

The Home Connect bridge can be configured in the Paper UI as follows:

1. Go to Inbox > Choose Binding and add a new "HomeConnect Binding".
2. Enter
    * __client id:__ your application client id
    * __client secret:__ your application client secret
    * __simulator:__ false
    * __refresh token:__ token from previous step
3. That's it! Now you can use autodiscovery to add devices.

#### Simulator

The Home Connect developer site allows you to use simulated appliances. You can control them at https://developer.home-connect.com/simulator/dishwasher.

1. Create an account at [https://developer.home-connect.com](https://developer.home-connect.com) and login.
2. Open [https://developer.home-connect.com/applications](https://developer.home-connect.com/applications) and save the client id from the default application: "API Web Client" for later use.
3. Setup bridge at Paper UI
    1. Go to Inbox > Choose Binding and add a new "HomeConnect Binding".
    2. Enter
        * __client id:__ id from 2. step
        * __client secret:__ leave blank
        * __simulator:__ true
        * __refresh token:__ leave blank
    3. That's it! Now you can use autodiscovery to add devices.

## Examples: File based configuration

If you prefer to configure everything via file instead of PaperUI, here are some examples.

### things/homeconnect.things

```
// simulator bridge
Bridge homeconnect:api_bridge:simulator_api_bridge "Home Connect API (Simulator)" [ clientId="1234", simulator=true] {
    // Thing configurations
    Thing dishwasher dishwasher1 "Dishwasher (Simulator)"  [ haId="SIEMENS-HCS02DWH1-6F2FC400C1EA4A" ]
    Thing washer washer1 "Washer (Simulator)"  [ haId="SIEMENS-HCS03WCH1-1F35EC2BE34A0F" ]
    Thing fridgefreezer fridge1 "Fridge Freezer (Simulator)"  [ haId="SIEMENS-HCS05FRF1-7B3FA5EB3D885B" ]
    Thing oven oven1 "Oven (Simulator)"  [ haId="BOSCH-HCS01OVN1-2132B6FA25BA21" ]
    Thing dryer dryer1 "Dryer (Simulator)"  [ haId="BOSCH-HCS04DYR1-3921C766AD5BAF" ]
    Thing coffeemaker coffee1 "Coffee machine (Simulator)"  [ haId="BOSCH-HCS06COM1-2140A8821AE7AB" ]
}

// real device bridge
Bridge homeconnect:api_bridge:api_bridge "Home Connect API" [ clientId="1234", clientSecret="1234", refreshToken="1234", simulator=false] {
    // Thing configurations
    Thing dishwasher siemensdishwasher "Siemens Dishwasher"  [ haId="SIEMENS-SNXXXXXXTE-XXXXXXXXXXBF" ]
}
```

### items/homeconnect.items

```
// dishwasher
Switch                 DishwasherSimulator_PowerState                  "Power State"                       {channel="homeconnect:dishwasher:dishwasher1:power_state"}
Contact                DishwasherSimulator_DoorState                   "Door State"                        {channel="homeconnect:dishwasher:dishwasher1:door_state"}
String                 DishwasherSimulator_OperationState              "Operation State"                   {channel="homeconnect:dishwasher:dishwasher1:operation_state"}
Switch                 DishwasherSimulator_RemoteStartAllowanceState   "Remote Start Allowance State"      {channel="homeconnect:dishwasher:dishwasher1:remote_start_allowance_state"}
Switch                 DishwasherSimulator_RemoteControlActiveState    "Remote Control Activation State"   {channel="homeconnect:dishwasher:dishwasher1:remote_control_active_state"}
String                 DishwasherSimulator_SelectedProgramState        "Selected Program"                  {channel="homeconnect:dishwasher:dishwasher1:selected_program_state"}
String                 DishwasherSimulator_ActiveProgramState          "Active Program"                    {channel="homeconnect:dishwasher:dishwasher1:active_program_state"}
Number:Time            DishwasherSimulator_RemainingProgramTimeState   "Remaining program time"            {channel="homeconnect:dishwasher:dishwasher1:remaining_program_time_state"}
Number:Dimensionless   DishwasherSimulator_ProgramProgressState        "Progress State"                    {channel="homeconnect:dishwasher:dishwasher1:program_progress_state"}

// dryer
Contact                DryerSimulator_DoorState                   "Door State"                        {channel="homeconnect:dryer:dryer1:door_state"}
String                 DryerSimulator_OperationState              "Operation State"                   {channel="homeconnect:dryer:dryer1:operation_state"}
Switch                 DryerSimulator_RemoteStartAllowanceState   "Remote Start Allowance State"      {channel="homeconnect:dryer:dryer1:remote_start_allowance_state"}
Switch                 DryerSimulator_RemoteControlActiveState    "Remote Control Activation State"   {channel="homeconnect:dryer:dryer1:remote_control_active_state"}
String                 DryerSimulator_ActiveProgramState          "Active Program"                    {channel="homeconnect:dryer:dryer1:active_program_state"}
String                 DryerSimulator_SelectedProgramState        "Selected Program"                  {channel="homeconnect:dryer:dryer1:selected_program_state"}
String                 DryerSimulator_DryerDryingTarget           "Drying target"                     {channel="homeconnect:dryer:dryer1:dryer_drying_target"}
Number:Time            DryerSimulator_RemainingProgramTimeState   "Remaining program time"            {channel="homeconnect:dryer:dryer1:remaining_program_time_state"}
Number:Dimensionless   DryerSimulator_ProgramProgressState        "Progress State"                    {channel="homeconnect:dryer:dryer1:program_progress_state"}

// fridge/freezer
Contact              FridgeFreezerSimulator_DoorState                          "Door State"                 {channel="homeconnect:fridgefreezer:fridge1:door_state"}
Number:Temperature   FridgeFreezerSimulator_SetpointTemperatureRefridgerator   "Refrigerator temperature"   {channel="homeconnect:fridgefreezer:fridge1:setpoint_temperature_refridgerator"}
Switch               FridgeFreezerSimulator_SuperModeRefrigerator              "Refrigerator super mode"    {channel="homeconnect:fridgefreezer:fridge1:super_mode_refrigerator"}
Number:Temperature   FridgeFreezerSimulator_SetpointTemperatureFreezer         "Freezer temperature"        {channel="homeconnect:fridgefreezer:fridge1:setpoint_temperature_freezer"}
Switch               FridgeFreezerSimulator_SuperModeFreezer                   "Freezer super mode"         {channel="homeconnect:fridgefreezer:fridge1:super_mode_freezer"}

// oven
Switch                 OvenSimulator_PowerState                     "Power State"                       {channel="homeconnect:oven:oven1:power_state"}
Contact                OvenSimulator_DoorState                      "Door State"                        {channel="homeconnect:oven:oven1:door_state"}
String                 OvenSimulator_OperationState                 "Operation State"                   {channel="homeconnect:oven:oven1:operation_state"}
Switch                 OvenSimulator_RemoteStartAllowanceState      "Remote Start Allowance State"      {channel="homeconnect:oven:oven1:remote_start_allowance_state"}
Switch                 OvenSimulator_RemoteControlActiveState       "Remote Control Activation State"   {channel="homeconnect:oven:oven1:remote_control_active_state"}
String                 OvenSimulator_ActiveProgramState             "Active Program"                    {channel="homeconnect:oven:oven1:active_program_state"}
String                 OvenSimulator_SelectedProgramState           "Selected Program"                  {channel="homeconnect:oven:oven1:selected_program_state"}
Number:Time            OvenSimulator_RemainingProgramTimeState      "Remaining program time"            {channel="homeconnect:oven:oven1:remaining_program_time_state"}
Number:Dimensionless   OvenSimulator_ProgramProgressState           "Progress State"                    {channel="homeconnect:oven:oven1:program_progress_state"}
Number:Temperature     OvenSimulator_OvenCurrentCavityTemperature   "Cavity Temperature"                {channel="homeconnect:oven:oven1:oven_current_cavity_temperature"}
Number:Time            OvenSimulator_ElapsedProgramTime             "Elapsed Program Time"              {channel="homeconnect:oven:oven1:elapsed_program_time"}
Number:Temperature     OvenSimulator_SetpointTemperature            "Setpoint Temperature"              {channel="homeconnect:oven:oven1:setpoint_temperature"}
Number:Time            OvenSimulator_Duration                       "Selected duration"                 {channel="homeconnect:oven:oven1:duration"}

// washer
Contact                WasherSimulator_DoorState                      "Door State"                        {channel="homeconnect:washer:washer1:door_state"}
String                 WasherSimulator_OperationState                 "Operation State"                   {channel="homeconnect:washer:washer1:operation_state"}
Switch                 WasherSimulator_RemoteStartAllowanceState      "Remote Start Allowance State"      {channel="homeconnect:washer:washer1:remote_start_allowance_state"}
Switch                 WasherSimulator_RemoteControlActiveState       "Remote Control Activation State"   {channel="homeconnect:washer:washer1:remote_control_active_state"}
String                 WasherSimulator_ActiveProgramState             "Active Program"                    {channel="homeconnect:washer:washer1:active_program_state"}
String                 WasherSimulator_SelectedProgramState           "Selected Program"                  {channel="homeconnect:washer:washer1:selected_program_state"}
Number:Time            WasherSimulator_RemainingProgramTimeState      "Remaining program time"            {channel="homeconnect:washer:washer1:remaining_program_time_state"}
Number:Dimensionless   WasherSimulator_ProgramProgressState           "Progress State"                    {channel="homeconnect:washer:washer1:program_progress_state"}
String                 WasherSimulator_LaundryCareWasherTemperature   "Washing Program Temperature"       {channel="homeconnect:washer:washer1:laundry_care_washer_temperature"}
String                 WasherSimulator_LaundryCareWasherSpinSpeed     "Spin Speed"                        {channel="homeconnect:washer:washer1:laundry_care_washer_spin_speed"}

// coffee machine
Contact                BOSCHCoffeeMakerSimulatorBOSCHHCS06COM12140A8821AE7AB_DoorState                   "Door State"                     {channel="homeconnect:CoffeeMaker:BOSCH-HCS06COM1-2140A8821AE7AB:door_state"}
String                 BOSCHCoffeeMakerSimulatorBOSCHHCS06COM12140A8821AE7AB_OperationState              "Operation State"                {channel="homeconnect:CoffeeMaker:BOSCH-HCS06COM1-2140A8821AE7AB:operation_state"}
Switch                 BOSCHCoffeeMakerSimulatorBOSCHHCS06COM12140A8821AE7AB_RemoteStartAllowanceState   "Remote Start Allowance State"   {channel="homeconnect:CoffeeMaker:BOSCH-HCS06COM1-2140A8821AE7AB:remote_start_allowance_state"}
String                 BOSCHCoffeeMakerSimulatorBOSCHHCS06COM12140A8821AE7AB_SelectedProgramState        "Selected Program"               {channel="homeconnect:CoffeeMaker:BOSCH-HCS06COM1-2140A8821AE7AB:selected_program_state"}
String                 BOSCHCoffeeMakerSimulatorBOSCHHCS06COM12140A8821AE7AB_ActiveProgramState          "Active Program"                 {channel="homeconnect:CoffeeMaker:BOSCH-HCS06COM1-2140A8821AE7AB:active_program_state"}
Number:Dimensionless   BOSCHCoffeeMakerSimulatorBOSCHHCS06COM12140A8821AE7AB_ProgramProgressState        "Progress State"                 {channel="homeconnect:CoffeeMaker:BOSCH-HCS06COM1-2140A8821AE7AB:program_progress_state"}
```

## Misc
Refresh token does not work in simulated environment. Please redo authentication via :8080/homeconnect

## Logging

```
<!-- Home Connect appender that logs into a file -->
<appender name="HOMECONNECTFILE" class="ch.qos.logback.core.FileAppender">
    <file>${openhab.logdir:-userdata/logs}/homeconnect.log</file>
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%-5level] [%-30.30logger{36}:%-5line] - %msg%ex{10}%n</pattern>
    </encoder>
</appender>
```

```
<logger name="org.openhab.binding.homeconnect" level="ALL" additivity="false">
    <appender-ref ref="HOMECONNECTFILE" />
    <appender-ref ref="STDOUT" />
</logger>
```



# modern-forms-for-hubitat-and-homekit

A Hubitat driver for Modern Forms fans that works with HomeKit Integration by utilizing child devices.

# How-To

1) Add to Hubitat as a Virtual Device with User Type (bottom of list) of Modern Forms for Hubitat and HomeKit
2) Set IP Address in main device and other preferences
3) Add child devices to HomeKit Integration

# Notes

* To support various scenarios, you can decide to enable or disable the light and fan, which will delete the respective device.  You can also choose whether to turn on the device when setting a new level or speed.

* The current HomeKit integration sends a lighting level of 100 when turning the light on.

* Modern Forms fans retain the last used fan speed, but this makes HomeKit think the fan is still on even if the switch is turned off.  To circumvent this, the driver ignores the fan speed from the fan and tells Hubitat the fan speed is off if the switch is off.

/*
 *  Modern Forms Fan and Light Driver for HomeKit
 *
 *  Copyright 2026 Chris Staines
 *  Based on code from Robert Morris, Ben Hamilton, 1info, and Hubitat
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 * 
 *  Changelog:
 *      2026-09-03v03 (Gemini Flash 3.8) - Removed pauseExecution thread delays from componentSetSpeed and componentSetLevel.
 *                      Added explicit enabledLight and enabledFan checks to all component commands.
 *                      Guarded changeDirection against disabled fan state.
 *                      Updated componentCycleSpeed to start at fanSpeedLow when cycled from off/null.
 *                      Moved direction control to fan child device (with parent alias).
 *                      Added componentSetDirection for discrete HomeKit/rule commands.
 *                      Fixed supportedFanSpeeds being sent to parent instead of fan child.
 *                      Fixed componentSetLevel accidentally adjusting light when called on fan child.
 *                      note:  used Claude Sonnet 5 Medium to review code; found several areas for improvement, but agreed with overall approach to unresponsiveness.
 *      2026-09-02v01 (Gemini Flash 3.8) - Migrated to non-blocking asynchttpPost to prevent hub thread lockups.
 *                      Replaced recursive runIn chaining with Quartz cron schedules.
 *                      Added configurable polling blackout period for physical power cycles.
 *                      Increased default polling interval to 300s (5m) to prevent web-server exhaustion.
 *                      Added command throttling (pauseExecution) for HomeKit rapid-fire calls.
 *						note:  used Gemini to identify cause of unresponsiveness; found chief issue was polling interval and not using async requests, which caused excessive memory usage.
 *		2026-02-25v01 (Claude Opus) - fix bugs: resp.date typo, fanSpeedNumber undefined, DisplayName capitalisation,
 *		                redundant fetchDeviceState calls, null crash on child device deletion,
 *		                List<String> return type mismatch, polling chain duplication,
 *		                presetLevel null fallback, unguarded log.debug, implicit global params,
 *		                no-op conditional preferences removed; used Claude
 *		2023-08-15v03 - perfect, shiny, and new
 *		2023-08-15v02 - clean up preferences (/shrug)
 *		2023-08-15v01 - add preference to turn on fan when speed set or light when level set
 *		2023-08-13v11 - clean up, use lastRunningSpeed
 *		2023-08-13v10 - further fix states and speeds x10
 *		2023-08-13v09 - fix child device deletion
 *		2023-08-13v08 - change stated fan speed to off if fan is off #becausehomekit x2
 *		2023-08-13v07 - state fixes x6
 *		2023-08-13v06 - additional clean up
 *		2023-08-13v05 - initial launch
 *		2023-08-13v04 - homekit idiosyncrasies
 *		2023-08-13v03 - clean up
 *		2023-08-13v02 - fixing fan speed states x5
 *		2023-08-13v01 - proper child device creation
 *		2023-08-12v01 - returned to 2023-08-11 base, versioning changes, added state updates
 *		2023-08-10v01 - initial try
 *
 *	ToDo:
 *		add command "adaptiveLearning"
 *		add command "awayMode"
 *		add command "feedbackToneMute"
 *		add command "wind"
 *		add attribute "windSpeed"
 *		add if (enabledLight) in front of light fixture references (do same for fan)
 */

/*
 *	Considerations / Notes:
 *
 *		requires an IP address for the device; suggest to
 *		set a static LAN IP via DHCP on your router for stability
 * 
 *		fanSpeed for Modern Forms has 6 choices, while Hubitat has 5.  so
 *		settings allow user to select a default Low speed
 *
 *		example fan response from 1info:
 *			"clientId": "MF_XXXXXXXXXXXX",
 *			"lightOn": false,
 *			"fanOn": true,
 *			"lightBrightness": 45,
 *			"fanSpeed": 2,
 *			"fanDirection": "forward",
 *			"rfPairModeActive": false,
 *			"resetRfPairList": false,
 *			"factoryReset": false,
 *			"awayModeEnabled": false,
 *			"fanSleepTimer": 0,
 *			"lightSleepTimer": 0,
 *			"decommission": false,
 *			"schedule": "",
 *			"adaptiveLearning": false
*/

metadata {
    definition(name: "Modern Forms Fan and Light for HomeKit", namespace: "staines", author: "Chris Staines", importUrl: "https://raw.githubusercontent.com/staines/modern-forms-for-hubitat-and-homekit/main/modern-forms-for-hubitat-and-homekit.groovy") {
        capability "Initialize"
        capability "Refresh"

        command "reboot"
        command "changeDirection" // Retained as parent alias delegating to fan child
    }
    
    preferences {
        input name: "ipAddress", type: "text", title: "IP address of the fan", required: true
        input name: "logsEnabled", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "pollingInterval", type: "enum", title: "State Polling Interval", options: [
            "0": "Disabled",
            "60": "1 Minute",
            "120": "2 Minutes",
            "300": "5 Minutes (Recommended)",
            "600": "10 Minutes",
            "900": "15 Minutes"
        ], defaultValue: "300"
        
        input name: "blackoutEnabled", type: "bool", title: "Enable daily polling blackout (for wall switch power cycles)", defaultValue: true
        input name: "blackoutStart", type: "time", title: "Blackout window start time", defaultValue: "00:58"
        input name: "blackoutEnd", type: "time", title: "Blackout window end time", defaultValue: "01:06"

        input name: "enabledLight", type: "bool", title: "Enable light device (disabling deletes child)", defaultValue: true
        input name: "lightOnWithSetLevel", type: "bool", title: "Turn light on when setting a light level (helps HomeKit)", defaultValue: true
        input name: "enabledFan", type: "bool", title: "Enable fan device (disabling deletes child)", defaultValue: true
        input name: "fanSpeedLow", type: "number", title: "Modern Forms fan speed to use as Hubitat's low speed (1 or 2)", defaultValue: 2, range: 1..2
        input name: "fanOnWithSetSpeed", type: "bool", title: "Turn fan on when setting a fan speed (helps HomeKit)", defaultValue: true
    }
}

// Lifecycle Hooks

void installed() {
    if (logsEnabled) log.debug "installed()"
    setupDevice()
}

void updated() {
    if (logsEnabled) log.debug "updated()"
    setupDevice()
}

void initialize() {
    if (logsEnabled) log.debug "initialize()"
    setupDevice()
}

void refresh() {
    if (logsEnabled) log.debug "refresh()"
    fetchDeviceState()
}

String deviceURI() {
    return "http://${ipAddress}/mf"
}

// Setup & Scheduling

void setupDevice() {
    if (logsEnabled) log.debug "setupDevice()"
    unschedule()
        
    try {
        createChildDevices()
    } catch (Exception ex) {
        log.warn "Could not create child devices: ${ex}"
    }

    if (enabledFan) {
        def fanChild = getChildDevice("${device.id}-fan")
        if (fanChild) {
            List<String> fanSpeedList = ["low", "medium-low", "medium", "medium-high", "high", "off", "on"]
            fanChild.sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(fanSpeedList))
        }
    }
        
    fetchDeviceState()
    schedulePollingCron()
}

void schedulePollingCron() {
    int interval = (settings.pollingInterval ?: "300") as int
    if (interval <= 0) {
        if (logsEnabled) log.debug "Polling disabled"
        return
    }

    int mins = Math.max(1, Math.round(interval / 60))
    String cronStr = (mins == 1) ? "0 * * ? * *" : "0 */${mins} * ? * *"
    
    if (logsEnabled) log.debug "Scheduling device state poll every ${mins} minute(s) via cron: ${cronStr}"
    schedule(cronStr, 'runPoll')
}

void runPoll() {
    if (isInBlackoutWindow()) {
        if (logsEnabled) log.debug "Skipping poll; inside power-cycle blackout window"
        return
    }
    fetchDeviceState()
}

boolean isInBlackoutWindow() {
    if (!settings.blackoutEnabled || !settings.blackoutStart || !settings.blackoutEnd) return false
    
    try {
        Date now = new Date()
        Date startTime = timeToday(settings.blackoutStart, location.timeZone)
        Date endTime = timeToday(settings.blackoutEnd, location.timeZone)
        
        if (startTime && endTime) {
            return (endTime < startTime) ? (now >= startTime || now <= endTime) : (now >= startTime && now <= endTime)
        }
    } catch (Exception e) {
        log.warn "Could not evaluate blackout window: ${e}"
    }
    return false
}

// Device Commands

void reboot() {
    if (logsEnabled) log.debug "reboot()"
    sendCommandToDevice(["reboot": true])
}

// Parent alias for changeDirection delegating to fan child
void changeDirection() {
    if (!enabledFan) {
        if (logsEnabled) log.warn "Ignoring changeDirection; fan device is disabled."
        return
    }
    def fanChild = getChildDevice("${device.id}-fan")
    if (fanChild) {
        componentChangeDirection(fanChild)
    } else {
        log.error "Cannot change direction; fan child device does not exist"
    }
}

void fetchDeviceState() {
    if (isInBlackoutWindow()) return
    if (logsEnabled) log.debug "Obtaining device state"
    sendCommandToDevice([queryDynamicShadowData: 1])
}

// Asynchronous Network Handlers

void sendCommandToDevice(Map jsonBodyMap) {
    if (!ipAddress) {
        log.warn "IP Address not configured"
        return
    }

    Map params = [
        uri: deviceURI(),
        requestContentType: "application/json",
        contentType: "application/json",
        body: groovy.json.JsonOutput.toJson(jsonBodyMap),
        timeout: 5
    ]

    try {
        if (logsEnabled) log.debug "Sending async command: ${jsonBodyMap}"
        asynchttpPost("asyncHttpCallback", params, [body: jsonBodyMap])
    } catch (Exception e) {
        log.error "Error dispatching async HTTP request: ${e}"
    }
}

void asyncHttpCallback(response, data) {
    if (response.hasError()) {
        if (logsEnabled) log.warn "Fan communication error: ${response.errorMessage}"
        return
    }

    try {
        def responseData = parseJson(response.data)
        if (logsEnabled) log.debug "Received async response: ${responseData}"
        sendEventsForNewState(responseData)
    } catch (Exception e) {
        log.error "Failed to parse fan response JSON: ${e}"
    }
}

// Conversion Utilities

String convertFanSpeedToEnumerated(fanSpeedNumber) {
    switch (fanSpeedNumber) {
        case 1: case 2:
            return "low"
        case 3:
            return "medium-low"
        case 4:
            return "medium"
        case 5:
            return "medium-high"
        case 6:
            return "high"
        case 0: case null:
            return "off"
        default:
            log.error "Unable to enumerate fan speed of ${fanSpeedNumber}"
            return null
    }
}

int convertFanSpeedToNumber(String fanSpeedEnumeratedValue) {
    switch (fanSpeedEnumeratedValue) {
        case "low":
            return (settings.fanSpeedLow ?: 2) as int
        case "medium-low":
            return 3
        case "medium":
            return 4
        case "medium-high":
            return 5
        case "high":
            return 6
        case "off":
            return 0
        default:
            log.error "Unable to convert fan speed of ${fanSpeedEnumeratedValue} to number"
            return (settings.fanSpeedLow ?: 2) as int
    }
}

void createChildDevices() {
    String thisId = device.id
    def lightChild = getChildDevice("${thisId}-light")
    def fanChild = getChildDevice("${thisId}-fan")
   
    if (!lightChild && enabledLight) {
        addChildDevice("hubitat", "Generic Component Dimmer", "${thisId}-light", [name: "${device.displayName} Light", isComponent: false])
    }
    if (!fanChild && enabledFan) {
        addChildDevice("hubitat", "Generic Component Fan Control", "${thisId}-fan", [name: "${device.displayName} Fan", isComponent: false])
    }

    if (lightChild && !enabledLight) deleteChildDevice(lightChild.deviceNetworkId)
    if (fanChild && !enabledFan) deleteChildDevice(fanChild.deviceNetworkId)
}

// Component Device Commands

void componentOn(cd) {
    if (logsEnabled) log.debug "componentOn(${cd})"
    if (cd.deviceNetworkId.endsWith("-light")) {
        if (!enabledLight) return
        def lightChild = getChildDevice("${device.id}-light")
        int brightness = (lightChild?.currentValue("presetLevel") ?: lightChild?.currentValue("level") ?: 100) as int
        sendCommandToDevice(["lightOn": true, "lightBrightness": brightness])
    } else if (cd.deviceNetworkId.endsWith("-fan")) {
        if (!enabledFan) return
        sendCommandToDevice(["fanOn": true])
    }
}

void componentOff(cd) {
    if (logsEnabled) log.debug "componentOff(${cd})"
    if (cd.deviceNetworkId.endsWith("-light")) {
        if (!enabledLight) return
        sendCommandToDevice(["lightOn": false])
    } else if (cd.deviceNetworkId.endsWith("-fan")) {
        if (!enabledFan) return
        sendCommandToDevice(["fanOn": false])
    }
}

void componentCycleSpeed(cd) {
    if (logsEnabled) log.debug "componentCycleSpeed(${cd})"
    if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

    def fanChild = getChildDevice("${device.id}-fan")
    String currentFanSpeed = fanChild?.currentValue("speed")
    int defaultLow = (settings.fanSpeedLow ?: 2) as int

    int newFanSpeed
    switch (currentFanSpeed) {
        case "off":
        case null:
            newFanSpeed = defaultLow
            break
        case "low":
            newFanSpeed = 3
            break
        case "medium-low":
            newFanSpeed = 4
            break
        case "medium":
            newFanSpeed = 5
            break
        case "medium-high":
            newFanSpeed = 6
            break
        case "high":
            newFanSpeed = defaultLow
            break
        default:
            newFanSpeed = defaultLow
            break
    }
    sendCommandToDevice(["fanOn": true, "fanSpeed": newFanSpeed])
}

void componentSetSpeed(cd, value) {
    if (logsEnabled) log.debug "componentSetSpeed(${cd}, ${value})"
    if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

    if (value == "off") {
        componentOff(cd)
    } else if (value == "on") {
        componentOn(cd)
    } else {
        int speedValue = convertFanSpeedToNumber(value)
        if (fanOnWithSetSpeed) {
            sendCommandToDevice(["fanOn": true, "fanSpeed": speedValue])
        } else {
            sendCommandToDevice(["fanSpeed": speedValue])
        }
    }
}

void componentSetLevel(cd, level, transitionTime = null) {
    if (logsEnabled) log.debug "componentSetLevel(${cd}, ${level})"
    
    if (cd.deviceNetworkId.endsWith("-light")) {
        if (!enabledLight) return
        if (level == 0) {
            componentOff(cd)
        } else {
            if (lightOnWithSetLevel) {
                sendCommandToDevice(["lightOn": true, "lightBrightness": level])
            } else {
                sendCommandToDevice(["lightBrightness": level])
            }
        }
    } else if (cd.deviceNetworkId.endsWith("-fan")) {
        if (!enabledFan) return
        // Map 1-100% dimmer levels to 6-speed fan values if called by percentage controllers
        if (level == 0) {
            componentOff(cd)
        } else {
            int speedValue = Math.min(6, Math.max(1, Math.round((level as float) / 100 * 6)))
            if (fanOnWithSetSpeed) {
                sendCommandToDevice(["fanOn": true, "fanSpeed": speedValue])
            } else {
                sendCommandToDevice(["fanSpeed": speedValue])
            }
        }
    }
}

void componentChangeDirection(cd) {
    if (logsEnabled) log.debug "componentChangeDirection(${cd})"
    if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

    def fanChild = getChildDevice("${device.id}-fan")
    String currentDirection = fanChild?.currentValue("direction")
    if (!currentDirection) {
        log.warn "Current fan direction unknown, defaulting to forward"
        currentDirection = "reverse"
    }
    String newDirection = (currentDirection == "forward") ? "reverse" : "forward"
    sendCommandToDevice(["fanDirection": newDirection])
}

void componentSetDirection(cd, String direction) {
    if (logsEnabled) log.debug "componentSetDirection(${cd}, ${direction})"
    if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

    String target = direction.toLowerCase()
    if (target.contains("forward") || target.contains("clockwise")) {
        sendCommandToDevice(["fanDirection": "forward"])
    } else if (target.contains("reverse") || target.contains("counter")) {
        sendCommandToDevice(["fanDirection": "reverse"])
    } else {
        log.warn "Unsupported direction value: ${direction}"
    }
}

void componentRefresh(cd) {
    if (logsEnabled) log.debug "componentRefresh(${cd})"
    fetchDeviceState()
}

// State Handling

void sendEventsForNewState(newState) {
    if (!newState) return

    if (enabledFan) {
        def fanChild = getChildDevice("${device.id}-fan")
        if (fanChild) {
            String fanSpeedEnumerated = convertFanSpeedToEnumerated(newState.fanSpeed)
            String fanNewSwitchStatus = newState.fanOn ? "on" : "off"

            fanChild.sendEvent(name: "lastRunningSpeed", value: fanSpeedEnumerated, descriptionText: "${fanChild.displayName} lastRunningSpeed was set to ${fanSpeedEnumerated}")
            fanChild.sendEvent(name: "speed", value: (newState.fanOn ? fanSpeedEnumerated : "off"), descriptionText: "${fanChild.displayName} fan speed was set to ${(newState.fanOn ? fanSpeedEnumerated : 'off')}")
            fanChild.sendEvent(name: "switch", value: fanNewSwitchStatus, descriptionText: "${fanChild.displayName} was turned ${fanNewSwitchStatus}")
            fanChild.sendEvent(name: "direction", value: newState.fanDirection, descriptionText: "${fanChild.displayName} direction was set to ${newState.fanDirection}")
        }
    }
    
    if (enabledLight) {
        def lightChild = getChildDevice("${device.id}-light")
        if (lightChild) {
            String lightNewSwitchStatus = newState.lightOn ? "on" : "off"
            
            if (lightChild.currentValue("switch") != lightNewSwitchStatus) {
                lightChild.sendEvent(name: "switch", value: lightNewSwitchStatus, descriptionText: "${lightChild.displayName} was turned ${lightNewSwitchStatus}")
            }
            if (lightChild.currentValue("level") != newState.lightBrightness) {
                lightChild.sendEvent(name: "level", value: newState.lightBrightness, descriptionText: "${lightChild.displayName} level was set to ${newState.lightBrightness}%", unit: "%")
                lightChild.sendEvent(name: "presetLevel", value: newState.lightBrightness, descriptionText: "${lightChild.displayName} presetLevel was set to ${newState.lightBrightness}%", unit: "%")
            }
        }
    }
}

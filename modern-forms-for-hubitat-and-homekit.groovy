/*
 *	Modern Forms Fan and Light Driver for HomeKit
 *
 *	Copyright 2026 Chris Staines
 *	Based on code from Robert Morris, Ben Hamilton, 1info, and Hubitat
 * 
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 * 
 *	Changelog:
 *		2026-02-25v01 - fix bugs: resp.date typo, fanSpeedNumber undefined, DisplayName capitalisation,
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

/**
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
		command "changeDirection"
	  
	}
    
	preferences {
	   
		input name: "ipAddress", type: "text", title: "IP address of the fan", required: true

		input name: "logsEnabled", type: "bool", title: "Enable debug logging", defaultValue: false

		input name: "pollingInterval", type: "number", title: "Polling interval in seconds (or use 0 to disable)", defaultValue: 30

		input name: "enabledLight", type: "bool", title: "Enable light device (disabling deletes the light device)", defaultValue: true

		input name: "lightOnWithSetLevel", type: "bool", title: "Turn light on when setting a light level or brightness (helps with HomeKit idiosyncrasies)", defaultValue: true

		input name: "enabledFan", type: "bool", title: "Enable fan device (disabling deletes the fan device)", defaultValue: true

		input name: "fanSpeedLow", type: "number", title: "Modern Forms fan speed to use as Hubitat's low speed setting (1 or 2)", defaultValue: 2, range: 1..2

		input name: "fanOnWithSetSpeed", type: "bool", title: "Turn fan on when setting a fan speed (helps with HomeKit idiosyncrasies)", defaultValue: true
		
	}
	
}

// capabilities

void installed() {
// setup device after installation
	
	if (logsEnabled) log.debug("Installed")
	
	setupDevice()
	
}

void updated() {
// setup device after update
	
	if (logsEnabled) log.debug("Updated")
	
	setupDevice()
	
}

void initialize() {
// setup device after initialization
	
	if (logsEnabled) log.debug("Initialized")
	
	setupDevice()
	
}

void refresh() {
// refresh device
	
	if (logsEnabled) log.debug("Refresh")
	
	fetchDeviceState()
	
}

// variables

String deviceURI() {
// set device URL based on ipAddress

	return "http://${ipAddress}/mf"
	
}

// device-specific functions

void setupDevice() {
// create child devices, set basic fan speed parameter, obtain initial state, and set polling interval

	if (logsEnabled) log.debug("setupDevice()")

	// Cancel any existing polling chain before starting a new one
	unschedule('runPoll')
		
	try {
		
		createChildDevices()
		
	} catch (Exception ex) {
		
		log.warn "Could not create child devices: ${ex}"
		
	}

	List<String> fanSpeedList = ["low", "medium-low", "medium", "medium-high", "high", "off", "on"]
	groovy.json.JsonBuilder fanSpeedsJSON = new groovy.json.JsonBuilder(fanSpeedList)
	sendEvent(name: "supportedFanSpeeds", value: fanSpeedsJSON)
		
	fetchDeviceState()
	
	if (pollingInterval > 0) scheduleNextPoll()
		
}

void reboot() {
// reboot the device
	
	if (logsEnabled) log.debug("reboot()")
		
	sendCommandToDeviceWithParams(
	
		uri: deviceURI(),
		
		body: ["reboot": true],
		
		timeout: 1,
		
	) { resp ->
	
		if (logsEnabled) log.debug("Device not rebooted and unexpected response received: ${resp.data}")
			
	}
	
}

void changeDirection() {
// change fan direction
	
	if (logsEnabled) log.debug("changeDirection()")
		
	String currentDirection = device.currentValue("direction")
	
	if (!currentDirection) {
		
		log.error("No current direction obtained")
		
		return
		
	}
	
	String newDirection = currentDirection == "forward" ? "reverse" : "forward"
	
	sendCommandToDevice(["fanDirection": newDirection]) { resp ->
	
		if (logsEnabled) log.debug("Received response: ${resp.data}")
			
		sendEventsForNewState(resp.data)
	
	}
	
}

String convertFanSpeedToEnumerated(fanSpeedNumber) {
// convert fan speed number from Modern Forms to fan speed enumerated value for Hubitat
	
	switch (fanSpeedNumber) {
		
		case 1: case 2:
		
			// due to Modern Forms using 6 speeds and Hubitat supporting 5, we consolidate 1 and 2 into "low"
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
		
			log.error("Unable to enumerate fan speed of ${fanSpeedNumber}")

			return null
			
	}
	
}

int convertFanSpeedToNumber(String fanSpeedEnumeratedValue) {
// convert fan speed enumerated value from Hubitat to fan speed number for Modern Forms

	switch (fanSpeedEnumeratedValue) {
		
		case "low":
		
			return settings.fanSpeedLow
			
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
		
			// FIX: was incorrectly referencing undefined variable fanSpeedNumber
			log.error("Unable to convert fan speed of ${fanSpeedEnumeratedValue} to number")

			return settings.fanSpeedLow
			
	}
	
}

void scheduleNextPoll() {
// add a timer to run the next polling event if polling is enabled

	if (pollingInterval > 0) {
		
		if (logsEnabled) log.debug("Scheduling next device state poll for ${pollingInterval} seconds")

		// FIX: unschedule before scheduling to prevent duplicate polling chains
		// if updated() is called multiple times in quick succession
		unschedule('runPoll')
		runIn(pollingInterval, 'runPoll')
	
	}
	
}

void runPoll() {
// poll for device state if polling is enabled
	
	if (pollingInterval > 0) {
		
		if (logsEnabled) log.debug("Running poll")
			
		fetchDeviceState()
		
		scheduleNextPoll()
		
	}
	
}

void sendCommandToDevice(Map jsonBodyMap, Closure callback) {
// build command to send to device
	
	// FIX: was an implicit global variable (missing type declaration)
	Map params = [
		uri: deviceURI(),
		body: jsonBodyMap,
	]
	
	sendCommandToDeviceWithParams(params, callback)
	
}

void sendCommandToDeviceWithParams(Map params, Closure callback) {
// send command to device

	try {
		
		if (logsEnabled) log.debug("Sending command: ${params.body}")
		
		httpPostJson(params, callback)
		
	} catch (SocketTimeoutException exceptionResponse) {
		
		log.error("Timed out sending command; response: ${exceptionResponse}")
		
	} catch (Exception exceptionResponse) {
		
		log.error("Error sending command; response: ${exceptionResponse}")
		
	}
	
}

void fetchDeviceState() {
// obtain the device state

	if (logsEnabled) log.debug("Obtaining device state")
		
	sendCommandToDevice([queryDynamicShadowData: 1]) { resp ->
		
		if (logsEnabled) log.debug("Received response: ${resp.data}")
			
		sendEventsForNewState(resp.data)
		
	}
	
}

void createChildDevices() {
// create child light and fan devices if enabled
	
	String thisId = device.id
   
	com.hubitat.app.ChildDeviceWrapper lightChild = getChildDevice("${thisId}-light")
	com.hubitat.app.ChildDeviceWrapper fanChild = getChildDevice("${thisId}-fan")
   
	if (!lightChild && enabledLight) {
	   
		lightChild = addChildDevice("hubitat", "Generic Component Dimmer", "${thisId}-light", [name: "${device.displayName} Light", isComponent: false])
	  
	}
   
	if (!fanChild && enabledFan) {
	   
		fanChild = addChildDevice("hubitat", "Generic Component Fan Control", "${thisId}-fan", [name: "${device.displayName} Fan", isComponent: false])
	  
	}

	// Delete child devices if their feature has been disabled
	if (lightChild && !enabledLight) {
		deleteChildDevice(lightChild.deviceNetworkId)
	}

	if (fanChild && !enabledFan) {
		deleteChildDevice(fanChild.deviceNetworkId)
	}
	
}

// component device commands

void componentOn(cd) {
// turn on child device
	
	if (logsEnabled) log.debug "componentOn(${cd})"
	
	if (cd.deviceNetworkId.endsWith("-light")) {

		com.hubitat.app.ChildDeviceWrapper lightChild = getChildDevice("${device.id}-light")

		// FIX: guard against null presetLevel (e.g. on first boot before any state fetch)
		int brightness = (lightChild?.currentValue("presetLevel") ?: 100) as int
		
		sendCommandToDevice(["lightOn": true, "lightBrightness": brightness]) { resp ->
		
			if (logsEnabled) log.debug("Received response for light on: ${resp.data}")
				
			sendEventsForNewState(resp.data)
			
		}
		
	} else if (cd.deviceNetworkId.endsWith("-fan")) {

		sendCommandToDevice(["fanOn": true]) { resp ->
		
			if (logsEnabled) log.debug("Received response for fan on: ${resp.data}")
				
			sendEventsForNewState(resp.data)

		}
		
	} else {
		
		log.error "Unknown child device: ${cd}"
	
	}

	// FIX: removed redundant fetchDeviceState() — the POST response already contains the new state,
	// which is processed by sendEventsForNewState(resp.data) in the callback above

}

void componentOff(cd) {
// turn off child device
	
	if (logsEnabled) log.debug "componentOff(${cd})"
	
	if (cd.deviceNetworkId.endsWith("-light")) {
		
		sendCommandToDevice(["lightOn": false]) { resp ->
		
			if (logsEnabled) log.debug("Received response: ${resp.data}")
				
			sendEventsForNewState(resp.data)
			
		}
		
	} else if (cd.deviceNetworkId.endsWith("-fan")) {
		
		sendCommandToDevice(["fanOn": false]) { resp ->
		
			if (logsEnabled) log.debug("Received response: ${resp.data}")
				
			sendEventsForNewState(resp.data)

		}
		
	} else {
		
		log.error "Unknown child device: ${cd}"
	
	}

	// FIX: removed redundant fetchDeviceState()

}

void componentCycleSpeed(cd) {
// cycle fan speed of child device
	
	if (logsEnabled) log.debug "componentCycleSpeed($cd)"
	
	com.hubitat.app.ChildDeviceWrapper fanChild = getChildDevice("${device.id}-fan")
	
	String currentFanSpeed = fanChild.currentValue("speed")
	
	if (!currentFanSpeed) {
		
		log.error("No current speed to cycle from")
		
		return
		
	}
	
	int newFanSpeed = 0
	
	switch (currentFanSpeed) {
		
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
			newFanSpeed = settings.fanSpeedLow
			break
			
	}
		
	sendCommandToDevice(["fanOn": true, "fanSpeed": newFanSpeed]) { resp ->
	
		if (logsEnabled) log.debug("Received response: ${resp.data}")
			
		sendEventsForNewState(resp.data)
	
	}

	// FIX: removed redundant fetchDeviceState()

}

void componentSetSpeed(cd, value) {
// set fan speed of child device

	if (logsEnabled) log.debug("componentSetSpeed(${cd}, ${value})")
	
	if (value == "off") {
		
		componentOff(cd)
	
	} else if (value == "on") {

		componentOn(cd)
		
	} else {

		int speedValue = convertFanSpeedToNumber(value)

		if (logsEnabled) log.debug("changing fan speed to ${speedValue}")

		if (fanOnWithSetSpeed) {
		
			sendCommandToDevice(["fanOn": true, "fanSpeed": speedValue]) { resp ->
			
				// FIX: was resp.date (typo) — silently discarded state updates
				if (logsEnabled) log.debug("Received response: ${resp.data}")
					
				sendEventsForNewState(resp.data)

			}

		} else {

			sendCommandToDevice(["fanSpeed": speedValue]) { resp ->
			
				// FIX: was resp.date (typo) — silently discarded state updates
				if (logsEnabled) log.debug("Received response: ${resp.data}")
					
				sendEventsForNewState(resp.data)

			}

		}

	}

	// FIX: removed redundant fetchDeviceState()
	
}

void componentSetLevel(cd, level, transitionTime = null) {
// set light level

	// FIX: removed unguarded log.debug that always fired regardless of logsEnabled
	if (logsEnabled) log.debug("componentSetLevel(${cd}, ${level}, ${transitionTime})")
	
	if (level == 0) {
		
		componentOff(cd)
		
	} else {

		if (lightOnWithSetLevel) {
		
			sendCommandToDevice(["lightOn": true, "lightBrightness": level]) { resp ->
			
				if (logsEnabled) log.debug("Received response: ${resp.data}")
					
				sendEventsForNewState(resp.data)

			}

		} else {
		
			sendCommandToDevice(["lightBrightness": level]) { resp ->
			
				if (logsEnabled) log.debug("Received response: ${resp.data}")
					
				sendEventsForNewState(resp.data)

			}
			
		}
	
	}

	// FIX: removed redundant fetchDeviceState()
	
}

void componentRefresh(cd) {
// refresh device
	
	if (logsEnabled) log.debug("componentRefresh(${cd})")
		
	fetchDeviceState()
	
}

void sendEventsForNewState(newState) {
// set child device states
	
	if (enabledFan) {
		
		com.hubitat.app.ChildDeviceWrapper fanChild = getChildDevice("${device.id}-fan")
	
		String fanSpeedEnumerated = convertFanSpeedToEnumerated(newState.fanSpeed)

		String fanNewSwitchStatus = newState.fanOn ? "on" : "off"

		fanChild.sendEvent(name: "lastRunningSpeed", value: fanSpeedEnumerated, descriptionText: "${fanChild.displayName} lastRunningSpeed was set to ${fanSpeedEnumerated}")

		if (newState.fanOn) {

			fanChild.sendEvent(name: "speed", value: fanSpeedEnumerated, descriptionText: "${fanChild.displayName} fan speed was set to ${fanSpeedEnumerated}")

		} else {

			fanChild.sendEvent(name: "speed", value: "off", descriptionText: "${fanChild.displayName} fan speed was set to off due to fan being off")

		}

		// FIX: was fanChild.DisplayName (capital D) — Groovy property access is case-sensitive
		fanChild.sendEvent(name: "switch", value: fanNewSwitchStatus, descriptionText: "${fanChild.displayName} was turned ${fanNewSwitchStatus}")

		fanChild.sendEvent(name: "direction", value: newState.fanDirection, descriptionText: "${fanChild.displayName} direction was set to ${newState.fanDirection}")

		// FIX: child device deletion moved to createChildDevices() where it belongs,
		// rather than triggering on every state update
		
	}
	
	if (enabledLight) {
		
		com.hubitat.app.ChildDeviceWrapper lightChild = getChildDevice("${device.id}-light")
		
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

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
 *		2026-09-23v01 - Report fan Level from fanSpeed so the fan % always matches the speed sent to the fan.
 *		                Add rolling 24-hour count of requests sent to the fan in device state.
 *		2026-09-03v06 - Add blackout window check inside setupDevice() to prevent state fetches on hub reboot.
 *		                Safely parse settings.fanSpeedLow using null-safe check to prevent cast errors on install.
 *		                Harden componentSetLevel against 0% light race conditions.
 *		                Add pauseExecution(250) after async POSTs for componentOn, componentOff, setSpeed, setLevel.
 *		                Add explicit enabledLight and enabledFan safety checks to all component commands.
 *		                Guard changeDirection against disabled fan state.
 *		                Update componentCycleSpeed to start at fanSpeedLow when cycled from off/null.
 *		                Move direction control to fan child device (with parent alias).
 *		                Add componentSetDirection for discrete HomeKit/rule commands.
 *		                Fix supportedFanSpeeds sent to parent instead of fan child.
 *		                Fix componentSetLevel accidentally adjusting light when called on fan child.
 *		                Migrate to non-blocking asynchttpPost to prevent hub thread lockups.
 *		                Replace recursive runIn chaining with Quartz cron schedules.
 *		                Add configurable polling blackout period for physical power cycles.
 *		                Increase default polling interval to 300s (5m) to prevent web-server exhaustion.
 *                      used Gemini Flash 3.8 after review by Gemini Flash 3.8 and Claude Sonnet 5.
 *      2026-09-03v04 - Added pauseExecution(250) after asynchronous POST dispatch for command pacing.
 *                      used Gemini Flash 3.8 after review by Claude Sonnet 5.
 *      2026-09-03v03 - Added explicit enabledLight and enabledFan checks to all component commands.
 *                      Guarded changeDirection against disabled fan state.
 *                      Updated componentCycleSpeed to start at fanSpeedLow when cycled from off/null.
 *                      Moved direction control to fan child device (with parent alias).
 *                      Added componentSetDirection for discrete HomeKit/rule commands.
 *                      Fixed supportedFanSpeeds being sent to parent instead of fan child.
 *                      Fixed componentSetLevel accidentally adjusting light when called on fan child.
 *                      used Gemini Flash 3.8.
 *      2026-09-02v01 - Migrated to non-blocking asynchttpPost to prevent hub thread lockups.
 *                      Replaced recursive runIn chaining with Quartz cron schedules.
 *                      Added configurable polling blackout period for physical power cycles.
 *                      Increased default polling interval to 300s (5m) to prevent web-server exhaustion.
 *                      used Gemini Flash 3.8.
 *      2026-02-25v01 - Fix bugs: resp.date typo, fanSpeedNumber undefined, DisplayName capitalization,
 *                      redundant fetchDeviceState calls, null crash on child device deletion,
 *                      List<String> return type mismatch, polling chain duplication,
 *                      presetLevel null fallback, unguarded log.debug, implicit global params.
 *                      used Claude Opus.
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

// request counter
//
// held in memory rather than read-modify-written through `state`. a command's execution
// and its async callback can overlap, and overlapping executions can overwrite each
// other's `state` saves, which made the 2026-09-20v02 counter read low. `state` is only
// written to, as a display copy and as a backup so the count survives a hub reboot or a
// driver code save.
@groovy.transform.Field static java.util.concurrent.ConcurrentHashMap requestCounters = new java.util.concurrent.ConcurrentHashMap()

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
		
	if (!isInBlackoutWindow()) {
		fetchDeviceState()
	} else if (logsEnabled) {
		log.debug "Skipping initial setup state fetch; inside blackout window."
	}
	
	schedulePollingCron()
		
}

void schedulePollingCron() {
// schedule state polling via Quartz cron

	int interval = (settings?.pollingInterval ?: "300") as int
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
// poll for device state if outside blackout window

	if (isInBlackoutWindow()) {
		if (logsEnabled) log.debug "Skipping poll; inside power-cycle blackout window"
		return
	}
	fetchDeviceState()

}

boolean isInBlackoutWindow() {
// check if current time falls within blackout window

	if (!settings?.blackoutEnabled || !settings?.blackoutStart || !settings?.blackoutEnd) return false
	
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

void reboot() {
// reboot the device
	
	if (logsEnabled) log.debug("reboot()")
		
	sendCommandToDevice(["reboot": true])
	
}

void changeDirection() {
// change fan direction (parent alias delegating to fan child)
	
	if (logsEnabled) log.debug("changeDirection()")
		
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

	int defaultLow = (settings?.fanSpeedLow != null) ? (settings.fanSpeedLow as int) : 2

	switch (fanSpeedEnumeratedValue) {
		
		case "low":
		
			return defaultLow
			
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
		
			log.error("Unable to convert fan speed of ${fanSpeedEnumeratedValue} to number")

			return defaultLow
			
	}
	
}

int levelToSpeed(int level) {
// convert a fan Level (%) to a Modern Forms fan speed (1-6), in even ~17-point buckets
//
// the exact inverse of speedToLevel, so speed -> level -> speed never drifts.
// fanSpeedLow does not apply here: a percentage can address all 6 speeds directly.

	if (level <= 17) return 1
	if (level <= 33) return 2
	if (level <= 50) return 3
	if (level <= 67) return 4
	if (level <= 84) return 5
	return 6

}

int speedToLevel(int fanSpeedNumber) {
// convert a Modern Forms fan speed (1-6) to the fan Level (%) reported to Hubitat and HomeKit

	switch (fanSpeedNumber) {
		case 1: return 17
		case 2: return 33
		case 3: return 50
		case 4: return 67
		case 5: return 84
		case 6: return 100
		default: return 0
	}

}

void sendCommandToDevice(Map jsonBodyMap) {
// build and send asynchronous command to device
	
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
		return
	}

	// counted only after a successful dispatch, and isolated so that a fault in the
	// counter can never interfere with sending a command
	try {
		countRequest()
	} catch (Exception e) {
		if (logsEnabled) log.debug "Request counter error: ${e}"
	}

}

void countRequest() {
// add one request to a rolling 24-hour count kept in 24 hourly buckets
//
// state.requestCount24h is the number shown on the device page. it covers the current
// partial hour plus the 23 before it, so it is accurate to within an hour.

	String key = device.id.toString()
	long hour = (long) (now() / 3600000L)

	Map counter = requestCounters.get(key) as Map

	if (counter == null) {

		// first request since a hub reboot or driver save: resume from the backup in state
		List saved = (state.requestBuckets instanceof List) ? (state.requestBuckets as List) : null
		List buckets = (saved != null && saved.size() == 24) ? saved.collect { (it ?: 0) as int } : ([0] * 24)
		long savedHour = (state.requestBucketHour != null) ? (state.requestBucketHour as long) : hour

		counter = [hour: savedHour, buckets: buckets]

	}

	List buckets = counter.buckets as List
	long lastHour = counter.hour as long

	if (hour > lastHour) {

		// clear the buckets for every hour that has passed since the last request, up to all 24
		long gap = Math.min(24L, hour - lastHour)

		for (long h = lastHour + 1; h <= lastHour + gap; h++) {
			buckets[(int) (h % 24)] = 0
		}

		counter.hour = hour

	}

	int index = (int) (hour % 24)
	buckets[index] = (buckets[index] as int) + 1

	counter.buckets = buckets
	requestCounters.put(key, counter)

	int total = 0
	buckets.each { total += (it as int) }

	state.requestCount24h = total
	state.requestBuckets = buckets
	state.requestBucketHour = counter.hour

}

void asyncHttpCallback(response, data) {
// handle response from asynchronous HTTP call

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

void fetchDeviceState() {
// obtain the device state

	if (isInBlackoutWindow()) return

	if (logsEnabled) log.debug("Obtaining device state")
		
	sendCommandToDevice([queryDynamicShadowData: 1])
	
}

void createChildDevices() {
// create child light and fan devices if enabled
	
	String thisId = device.id
   
	def lightChild = getChildDevice("${thisId}-light")
	def fanChild = getChildDevice("${thisId}-fan")
   
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

		if (!enabledLight) return

		def lightChild = getChildDevice("${device.id}-light")
		int brightness = (lightChild?.currentValue("presetLevel") ?: lightChild?.currentValue("level") ?: 100) as int
		
		sendCommandToDevice(["lightOn": true, "lightBrightness": brightness])
		pauseExecution(250)
		
	} else if (cd.deviceNetworkId.endsWith("-fan")) {

		if (!enabledFan) return

		sendCommandToDevice(["fanOn": true])
		pauseExecution(250)

	} else {
		
		log.error "Unknown child device: ${cd}"
	
	}

}

void componentOff(cd) {
// turn off child device
	
	if (logsEnabled) log.debug "componentOff(${cd})"
	
	if (cd.deviceNetworkId.endsWith("-light")) {
		
		if (!enabledLight) return

		sendCommandToDevice(["lightOn": false])
		pauseExecution(250)
		
	} else if (cd.deviceNetworkId.endsWith("-fan")) {
		
		if (!enabledFan) return

		sendCommandToDevice(["fanOn": false])
		pauseExecution(250)

	} else {
		
		log.error "Unknown child device: ${cd}"
	
	}

}

void componentCycleSpeed(cd) {
// cycle fan speed of child device
	
	if (logsEnabled) log.debug "componentCycleSpeed($cd)"
	
	if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

	def fanChild = getChildDevice("${device.id}-fan")
	String currentFanSpeed = fanChild?.currentValue("speed")
	int defaultLow = (settings?.fanSpeedLow != null) ? (settings.fanSpeedLow as int) : 2

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
	pauseExecution(250)

}

void componentSetSpeed(cd, value) {
// set fan speed of child device

	if (logsEnabled) log.debug("componentSetSpeed(${cd}, ${value})")
	
	if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

	if (value == "off") {
		
		componentOff(cd)
	
	} else if (value == "on") {

		componentOn(cd)
		
	} else {

		int speedValue = convertFanSpeedToNumber(value)

		if (logsEnabled) log.debug("changing fan speed to ${speedValue}")

		if (fanOnWithSetSpeed) {
		
			sendCommandToDevice(["fanOn": true, "fanSpeed": speedValue])

		} else {

			sendCommandToDevice(["fanSpeed": speedValue])

		}
		pauseExecution(250)

	}
	
}

void componentSetLevel(cd, level, transitionTime = null) {
// set light level or fan dimmer level

	if (logsEnabled) log.debug("componentSetLevel(${cd}, ${level}, ${transitionTime})")
	
	int targetLevel = (level != null) ? (level as int) : 0

	if (cd.deviceNetworkId.endsWith("-light")) {

		if (!enabledLight) return

		if (targetLevel <= 0) {
			
			componentOff(cd)
			
		} else {

			if (lightOnWithSetLevel) {
			
				sendCommandToDevice(["lightOn": true, "lightBrightness": targetLevel])

			} else {
			
				sendCommandToDevice(["lightBrightness": targetLevel])

			}
			pauseExecution(250)
		
		}

	} else if (cd.deviceNetworkId.endsWith("-fan")) {

		if (!enabledFan) return

		if (targetLevel <= 0) {

			componentOff(cd)

		} else {

			// the Level reported back from the fan's response is speedToLevel(speedValue),
			// so the % snaps to match the speed actually sent (e.g. 60% -> speed 4 -> 67%)
			int speedValue = levelToSpeed(targetLevel)
			if (fanOnWithSetSpeed) {
				sendCommandToDevice(["fanOn": true, "fanSpeed": speedValue])
			} else {
				sendCommandToDevice(["fanSpeed": speedValue])
			}
			pauseExecution(250)

		}

	}
	
}

void componentChangeDirection(cd) {
// change fan direction on child device

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
	pauseExecution(250)

}

void componentSetDirection(cd, String direction) {
// set discrete fan direction on child device

	if (logsEnabled) log.debug "componentSetDirection(${cd}, ${direction})"
	if (!enabledFan || !cd.deviceNetworkId.endsWith("-fan")) return

	String target = direction?.toLowerCase() ?: ""
	if (target.contains("forward") || target.contains("clockwise")) {
		sendCommandToDevice(["fanDirection": "forward"])
		pauseExecution(250)
	} else if (target.contains("reverse") || target.contains("counter")) {
		sendCommandToDevice(["fanDirection": "reverse"])
		pauseExecution(250)
	} else {
		log.warn "Unsupported direction value: ${direction}"
	}

}

void componentRefresh(cd) {
// refresh device
	
	if (logsEnabled) log.debug("componentRefresh(${cd})")
		
	fetchDeviceState()
	
}

void sendEventsForNewState(newState) {
// set child device states
	
	if (!newState) return

	if (enabledFan) {
		
		def fanChild = getChildDevice("${device.id}-fan")
		if (fanChild) {

			String fanSpeedEnumerated = convertFanSpeedToEnumerated(newState.fanSpeed)
			String fanNewSwitchStatus = newState.fanOn ? "on" : "off"

			fanChild.sendEvent(name: "lastRunningSpeed", value: fanSpeedEnumerated, descriptionText: "${fanChild.displayName} lastRunningSpeed was set to ${fanSpeedEnumerated}")

			if (newState.fanOn) {

				fanChild.sendEvent(name: "speed", value: fanSpeedEnumerated, descriptionText: "${fanChild.displayName} fan speed was set to ${fanSpeedEnumerated}")

			} else {

				fanChild.sendEvent(name: "speed", value: "off", descriptionText: "${fanChild.displayName} fan speed was set to off due to fan being off")

			}

			fanChild.sendEvent(name: "switch", value: fanNewSwitchStatus, descriptionText: "${fanChild.displayName} was turned ${fanNewSwitchStatus}")

			// fan Level follows the speed the fan reports, whichever path set it: HomeKit %,
			// a named speed in Hubitat, cycleSpeed, or the remote/app picked up by a poll.
			// 0 when the fan is off. sent straight to sendEvent like switch and speed above;
			// Hubitat already drops unchanged events for standard attributes such as level.
			if (newState.containsKey("fanOn") && newState.containsKey("fanSpeed")) {

				int rawSpeed = 0

				try {
					rawSpeed = (newState.fanSpeed as BigDecimal).intValue()
				} catch (Exception e) {
					rawSpeed = 0
				}

				int fanLevel = newState.fanOn ? speedToLevel(rawSpeed) : 0

				fanChild.sendEvent(name: "level", value: fanLevel, unit: "%", descriptionText: "${fanChild.displayName} level was set to ${fanLevel}%")

			}
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

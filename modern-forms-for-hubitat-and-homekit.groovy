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
 *		2026-09-20v02 - Add optional fan level reporting to keep Level in sync with fanSpeed.
 *		                Add daily request counting to measure outbound calls to the fan.
 *		2026-09-20v01 - Remove fan direction control; writing fanDirection drops the fan off wifi.
 *		                Add 15s floor between state fetches to drop redundant reads.
 *		                Gate child events on last-sent value; fix lastRunningSpeed to track raw speed.
 *		                Guard state parsing on containsKey so partial responses no longer report off.
 *		                Log fan communication errors unconditionally.
 *		                Even out the level-to-speed mapping in componentSetLevel.
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
 *		add attribute "windSpeed"   <-- caution: wind is a motor behavior like direction was.
 *		                                test it on a fan you don't mind knocking offline.
 */

/**
 *	Considerations / Notes:
 *
 *		requires an IP address for the device; suggest to
 *		set a static LAN IP via DHCP on your router for stability
 *
 *		THE FAN'S WEB SERVER LEAKS MEMORY.  too many HTTP calls and it stops
 *		responding to commands after roughly three days.  total outbound
 *		request count is the binding constraint on any change to this driver.
 *		5 minute polling plus async requests is what made it stable.
 *
 *		fan direction cannot be controlled from this driver.  writing a
 *		"fanDirection" key makes the fan drop off wifi every time, whatever
 *		value is sent, so all direction commands were removed in v11.  the
 *		current direction is still read and reported.  reverse the fan with
 *		the wall control or the Modern Forms app.
 *
 *		the Hubitat HomeKit bridge only exposes generic fan and light child
 *		devices, so the children must stay "Generic Component Fan Control"
 *		and "Generic Component Dimmer"; a custom child driver is not picked up.
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

		capability "Actuator"
		capability "Initialize"
		capability "Refresh"

		command "reboot"

		// deliberately no changeDirection command -- writing fanDirection drops the fan
		// off wifi every time. see the note in Considerations above.

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

		// Hubitat does not reliably honor defaultValue on a type: "time" input, so these
		// fall back to fixed times in code rather than appearing set but being null
		input name: "blackoutStart", type: "time", title: "Blackout window start time (defaults to 00:58 if unset)"

		input name: "blackoutEnd", type: "time", title: "Blackout window end time (defaults to 01:06 if unset)"

		input name: "enabledLight", type: "bool", title: "Enable light device (disabling deletes child)", defaultValue: true

		input name: "lightOnWithSetLevel", type: "bool", title: "Turn light on when setting a light level (helps HomeKit)", defaultValue: true

		input name: "enabledFan", type: "bool", title: "Enable fan device (disabling deletes child)", defaultValue: true

		input name: "fanSpeedLow", type: "number", title: "Modern Forms fan speed to use as Hubitat's low speed (1 or 2)", defaultValue: 2, range: 1..2

		input name: "fanOnWithSetSpeed", type: "bool", title: "Turn fan on when setting a fan speed (helps HomeKit)", defaultValue: true

		// OFF by default on purpose. Reporting a level gives the HomeKit bridge a live
		// RotationSpeed characteristic, which is what makes percentage voice commands work
		// -- but it is also the prime suspect for the traffic increase that forced the
		// v07-v10 revert. Turn it on, then watch the daily request count logged by this
		// driver for a few days before trusting it.
		input name: "reportFanLevel", type: "bool", title: "Report fan speed as a Level percentage (enables % control; may increase fan traffic -- watch the daily request count)", defaultValue: false

	}
	
}

// constants

@groovy.transform.Field static final String FALLBACK_BLACKOUT_START = "00:58"
@groovy.transform.Field static final String FALLBACK_BLACKOUT_END = "01:06"

// The fan's web server leaks memory: too many HTTP calls and it stops responding to
// commands after roughly three days. Total outbound request count is therefore the
// binding constraint on this driver.
//
// This is the floor between STATE FETCHES (reads). Commands are never suppressed, but
// every request -- command or read -- resets the clock, because a command response
// carries the full shadow document and so already gives us fresh state. That makes a
// read within this window redundant by definition.
//
// It is not a debounce: nothing is queued or scheduled, and no timer fires. A read that
// arrives too soon is simply dropped.
@groovy.transform.Field static final long MIN_FETCH_INTERVAL_MS = 15000

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

	// forget the last-sent values so the next state update re-syncs every attribute
	clearSentEventCache()

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

	if (!settings?.blackoutEnabled) return false

	// fall back explicitly; an untouched install would otherwise have null times here and
	// silently disable the blackout even though the toggle reads as enabled
	String startSetting = settings?.blackoutStart ?: FALLBACK_BLACKOUT_START
	String endSetting = settings?.blackoutEnd ?: FALLBACK_BLACKOUT_END

	try {
		Date now = new Date()
		Date startTime = timeToday(startSetting, location.timeZone)
		Date endTime = timeToday(endSetting, location.timeZone)
		
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

// DIRECTION CONTROL IS DELIBERATELY ABSENT -- DO NOT RE-ADD
//
// Sending a "fanDirection" key to the Mykonos 5 makes the fan drop off wifi every time,
// whatever value is sent. It is a firmware fault with no driver-side workaround, so
// changeDirection(), componentChangeDirection() and componentSetDirection() were all
// removed in v11 rather than left in place to knock the fan off the network.
//
// fanDirection is still READ from the shadow document and reported to the fan child as an
// informational attribute; reading it sends nothing to the fan and is safe. Reverse the
// fan with the wall control or the Modern Forms app.

String convertFanSpeedToEnumerated(fanSpeedNumber) {
// convert fan speed number from Modern Forms to fan speed enumerated value for Hubitat

	// coerce first; JSON parsing can hand back BigDecimal or String, in which case the
	// integer cases below would all miss and fall through to the error branch
	Integer speed = null

	if (fanSpeedNumber != null) {

		try {
			speed = (fanSpeedNumber as BigDecimal).intValue()
		} catch (Exception e) {
			log.error("Unable to interpret fan speed of ${fanSpeedNumber}")
			return null
		}

	}

	switch (speed) {

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
// convert a percentage from a setLevel command to a Modern Forms fan speed (1-6)
//
// even buckets of roughly 17 points each. the old inline math
// (Math.round(level / 100 * 6), clamped) gave speed 1 a 24-point range and speed 6 only
// 9, so the top and bottom of the slider behaved differently from the middle.
//
// fanSpeedLow deliberately does NOT apply here. It exists because Hubitat's 5-name speed
// enumeration cannot express the fan's 6 speeds, so "low" has to pick one of 1 or 2. A
// percentage has no such limit, so this path is the only way to address all six.

	if (level <= 17) return 1
	if (level <= 33) return 2
	if (level <= 50) return 3
	if (level <= 67) return 4
	if (level <= 84) return 5
	return 6

}

int speedToLevel(int fanSpeedNumber) {
// convert a Modern Forms fan speed (1-6) to the percentage reported as Level
//
// the exact inverse of levelToSpeed: each value is the top of that speed's bucket, so
// speed -> level -> speed round-trips without drift. this is what makes medium (4) and
// medium-high (5) show as distinct levels instead of both landing on 67.

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

		long nowMs = now()

		// Request accounting. The fan's web server leaks memory, so the actual daily
		// request count is the only honest way to evaluate any change to this driver --
		// including the reportFanLevel toggle above. Kept in state rather than as an
		// attribute so it generates no Hubitat events, and logged once per 24h window.
		Long since = (state.requestCountSince != null) ? (state.requestCountSince as Long) : null
		int count = (state.requestCount != null) ? (state.requestCount as int) : 0

		if (since == null) {

			state.requestCountSince = nowMs
			count = 0

		} else if ((nowMs - since) >= 86400000L) {

			log.info "Modern Forms driver sent ${count} requests to the fan in the last 24h (level reporting ${reportFanLevel ? 'ON' : 'off'})"
			state.requestCountSince = nowMs
			count = 0

		}

		state.requestCount = count + 1

		// stamped at dispatch, not on response, so a hung request cannot let a burst
		// of reads through behind it. every request resets the read floor.
		state.lastRequestMs = nowMs

		asynchttpPost("asyncHttpCallback", params, [body: jsonBodyMap])
	} catch (Exception e) {
		log.error "Error dispatching async HTTP request: ${e}"
	}

}

void asyncHttpCallback(response, data) {
// handle response from asynchronous HTTP call

	if (response.hasError()) {
		// logged unconditionally. with debug off this was the only signal that the fan
		// was unreachable, and it was being swallowed.
		log.warn "Modern Forms fan at ${ipAddress} unreachable (request: ${data?.body}): ${response.errorMessage}"
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
//
// blackout stays absolute here, including for manual refresh. overriding it would add
// requests during the nightly power cycle, which is the opposite of what this fan needs.

	if (isInBlackoutWindow()) {
		if (logsEnabled) log.debug "Skipping state fetch; inside power-cycle blackout window"
		return
	}

	// drop reads that arrive within MIN_FETCH_INTERVAL_MS of any previous request. the
	// common case this catches is both children being refreshed back to back, which
	// otherwise sends two POSTs for identical data.
	Long last = (state.lastRequestMs != null) ? (state.lastRequestMs as Long) : null

	if (last != null) {

		long age = now() - last

		if (age >= 0 && age < MIN_FETCH_INTERVAL_MS) {
			if (logsEnabled) log.debug "Skipping state fetch; a request went out ${age}ms ago"
			return
		}

	}

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

			int speedValue = levelToSpeed(targetLevel)

			if (logsEnabled) log.debug("level ${targetLevel}% maps to fan speed ${speedValue}")

			if (fanOnWithSetSpeed) {
				sendCommandToDevice(["fanOn": true, "fanSpeed": speedValue])
			} else {
				sendCommandToDevice(["fanSpeed": speedValue])
			}
			pauseExecution(250)

		}

	}
	
}

// componentChangeDirection and componentSetDirection removed in v11.
// See the do-not-re-add note further up: writing fanDirection drops the fan off wifi.

void componentRefresh(cd) {
// refresh device
	
	if (logsEnabled) log.debug("componentRefresh(${cd})")
		
	fetchDeviceState()
	
}

void sendChildEvent(cd, String name, value, String descriptionText, String unit = null) {
// send a child event only when the value has actually changed
//
// gated on what the parent last sent, recorded in its own state, rather than on
// cd.currentValue(name). currentValue does not reliably round-trip the child's custom
// attributes (direction, lastRunningSpeed) back to the parent, so a currentValue
// comparison let every poll through and the event log kept filling.
//
// this sends nothing to the fan either way -- it only decides whether to write a Hubitat
// event -- so it has no effect on outbound request count.
//
// setupDevice() clears these keys, so saving preferences forces a full re-sync if the
// child and the parent's record ever drift apart.

	if (!cd || !name) return

	String key = "sent_${cd.deviceNetworkId}_${name}"
	String incoming = (value == null) ? "" : value.toString()
	String previous = (state[key] == null) ? null : state[key].toString()

	if (previous == incoming) return

	state[key] = incoming

	Map evt = [name: name, value: value, descriptionText: descriptionText]
	if (unit) evt.unit = unit

	cd.sendEvent(evt)

}

void clearSentEventCache() {
// forget what we last sent, so the next state update re-syncs every attribute

	// collect first, then remove; avoids mutating state while iterating it
	List toRemove = []

	state.each { k, v ->
		if (k != null && k.toString().startsWith("sent_")) toRemove.add(k)
	}

	toRemove.each { state.remove(it) }

}

void sendEventsForNewState(newState) {
// set child device states
//
// every block is guarded on containsKey. a partial response -- the reboot POST, or a
// truncated reply -- used to read as fanOn/lightOn == null == false and report the fan
// and light as off.

	if (!(newState instanceof Map)) return

	if (enabledFan) {

		def fanChild = getChildDevice("${device.id}-fan")
		if (fanChild) {

			// read-only. reporting direction sends nothing to the fan and is safe; the
			// driver has no way to CHANGE it. this just surfaces whatever was set at the
			// wall control or in the Modern Forms app.
			if (newState.containsKey("fanDirection") && newState.fanDirection) {

				sendChildEvent(fanChild, "direction", newState.fanDirection, "${fanChild.displayName} direction is ${newState.fanDirection}")

			}

			if (newState.containsKey("fanOn")) {

				boolean fanIsOn = (newState.fanOn == true)
				String fanNewSwitchStatus = fanIsOn ? "on" : "off"

				sendChildEvent(fanChild, "switch", fanNewSwitchStatus, "${fanChild.displayName} was turned ${fanNewSwitchStatus}")

				if (newState.containsKey("fanSpeed")) {

					String rawSpeedEnumerated = convertFanSpeedToEnumerated(newState.fanSpeed)
					String fanSpeedEnumerated = fanIsOn ? rawSpeedEnumerated : "off"

					if (fanSpeedEnumerated) {
						sendChildEvent(fanChild, "speed", fanSpeedEnumerated, "${fanChild.displayName} fan speed was set to ${fanSpeedEnumerated}")
					}

					// lastRunningSpeed is meant to hold the speed the fan was at even while
					// it reads off, so it tracks the raw speed rather than the
					// switch-masked value. v06 overwrote it with "off" when the fan
					// stopped, which defeated its purpose.
					if (rawSpeedEnumerated && rawSpeedEnumerated != "off") {
						sendChildEvent(fanChild, "lastRunningSpeed", rawSpeedEnumerated, "${fanChild.displayName} lastRunningSpeed was set to ${rawSpeedEnumerated}")
					}

					// opt-in; see the reportFanLevel preference. without this the child's
					// level attribute only ever changes when something calls setLevel on
					// it, so setting a speed by name leaves a stale percentage behind.
					if (reportFanLevel && fanChild.hasAttribute("level")) {

						int rawSpeed = 0

						try {
							rawSpeed = (newState.fanSpeed as BigDecimal).intValue()
						} catch (Exception e) {
							rawSpeed = 0
						}

						int fanLevel = fanIsOn ? speedToLevel(rawSpeed) : 0

						sendChildEvent(fanChild, "level", fanLevel, "${fanChild.displayName} level was set to ${fanLevel}%", "%")

					}

				}

			}

		}

	}

	if (enabledLight) {

		def lightChild = getChildDevice("${device.id}-light")
		if (lightChild) {

			if (newState.containsKey("lightOn")) {

				String lightNewSwitchStatus = (newState.lightOn == true) ? "on" : "off"

				sendChildEvent(lightChild, "switch", lightNewSwitchStatus, "${lightChild.displayName} was turned ${lightNewSwitchStatus}")

			}

			if (newState.containsKey("lightBrightness") && newState.lightBrightness != null) {

				sendChildEvent(lightChild, "level", newState.lightBrightness, "${lightChild.displayName} level was set to ${newState.lightBrightness}%", "%")
				sendChildEvent(lightChild, "presetLevel", newState.lightBrightness, "${lightChild.displayName} presetLevel was set to ${newState.lightBrightness}%", "%")

			}

		}

	}

}

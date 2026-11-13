/*
 *	Modern Forms Fan and Light Driver for HomeKit
 *
 *	Copyright 2026 Chris Staines
 *
 *	Thank you to Robert Morris, Ben Hamilton, 1info, and Hubitat for inspiration and prior solutions.
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
 *		2026-11-13v02 - Shorten the request timeout to 5 seconds.
 *		                Retry a command once if the fan does not answer it.
 *		2026-11-13v01 - Rewrite from scratch.
 */

/*
 *	How it works
 *
 *		The fan runs a small web server. Every exchange is one HTTP POST of a JSON object to
 *		http://<ip>/mf, and the reply is the fan's full state as a JSON object:
 *
 *			status query   {"queryDynamicShadowData": 1}
 *			command        {"fanOn": true, "fanSpeed": 4}   (any of the writable keys below)
 *
 *		Writable keys used here: fanOn, fanSpeed (1-6), lightOn, lightBrightness (1-100).
 *
 *		NEVER send "fanDirection". On the Mykonos 5 it makes the fan drop off wifi every time,
 *		whatever value is sent. Reverse the fan with the wall control, remote or Modern Forms app.
 *
 *		The fan's web server has very little memory, so this driver:
 *			- never has more than one request in flight (see "request pipeline" below)
 *			- sends nothing at all during the blackout window
 *			- polls no faster than every 2 minutes
 *			- counts every request it sends (state.requestCount24h)
 *
 *		The parent device talks to the fan. The fan and the light are child devices using
 *		Hubitat's own Generic Component drivers, so the HomeKit integration can expose them.
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String DRIVER_VERSION = "2026-11-13v02"

@Field static final List<String> SPEED_NAMES = ["low", "medium-low", "medium", "medium-high", "high"]

@Field static final int HTTP_TIMEOUT_SECONDS = 5
@Field static final long STALE_REQUEST_MS = 20000L	// a request with no callback after this long is treated as lost
@Field static final int PACING_MS = 300				// gap between one reply and the next request

@Field static final int DEFAULT_BLACKOUT_START = 58		// 00:58, as minutes after midnight
@Field static final int DEFAULT_BLACKOUT_END = 66		// 01:06

// Shared working memory, keyed by parent device id. Held in memory, not in `state`: a command
// and the reply to an earlier request run as separate executions that can overlap, and
// overlapping executions can overwrite each other's `state`. Memory is lost on a hub reboot
// or a driver save, which is harmless here.
@Field static final ConcurrentHashMap inFlight = new ConcurrentHashMap()			// id -> time the open request was sent
@Field static final ConcurrentHashMap pendingCommands = new ConcurrentHashMap()	// id -> map of keys waiting to be sent
@Field static final ConcurrentHashMap requestCounters = new ConcurrentHashMap()	// id -> hourly request buckets
@Field static final ConcurrentHashMap unreachable = new ConcurrentHashMap()		// id -> true while the fan is not answering
@Field static final ConcurrentHashMap retryWaiting = new ConcurrentHashMap()		// id -> true when the waiting commands include a retry

metadata {

	definition(name: "Modern Forms Fan and Light for HomeKit", namespace: "staines", author: "Chris Staines", importUrl: "https://raw.githubusercontent.com/staines/modern-forms-for-hubitat-and-homekit/main/modern-forms-for-hubitat-and-homekit.groovy") {

		capability "Initialize"
		capability "Refresh"

	}

	preferences {

		input name: "ipAddress", type: "text", title: "IP address of the fan", required: true

		input name: "enabledFan", type: "bool", title: "Enable fan device (disabling deletes the child device)", defaultValue: true

		input name: "enabledLight", type: "bool", title: "Enable light device (disabling deletes the child device)", defaultValue: true

		input name: "fanSpeedLow", type: "enum", title: "Fan speed used for Low (the fan has 6 speeds, Hubitat has 5)", options: [
			"1": "Speed 1 (speed 2 is not exposed)",
			"2": "Speed 2 (speed 1 is not exposed)"
		], defaultValue: "2"

		input name: "pollingInterval", type: "enum", title: "Status polling interval", options: [
			"120": "2 minutes",
			"300": "5 minutes (recommended)",
			"600": "10 minutes"
		], defaultValue: "300"

		input name: "blackoutEnabled", type: "bool", title: "Enable daily blackout window (nothing is sent to the fan during it)", defaultValue: true

		input name: "blackoutStart", type: "time", title: "Blackout window start (00:58 if not set)"

		input name: "blackoutEnd", type: "time", title: "Blackout window end (01:06 if not set)"

		input name: "logsEnabled", type: "bool", title: "Enable debug logging (turns itself off after 30 minutes)", defaultValue: false

	}

}

// ---------------------------------------------------------------------------------------
// lifecycle
// ---------------------------------------------------------------------------------------

void installed() {

	setup()

}

void updated() {

	setup()

}

void initialize() {
// called by Hubitat when the hub starts

	setup()

}

void setup() {

	unschedule()

	// state written by an earlier driver version is not carried forward
	if (state.driverVersion == null) state.clear()

	state.driverVersion = DRIVER_VERSION

	syncChildDevices()
	schedulePolling()

	if (logsEnabled) runIn(1800, "debugLoggingOff")

	poll()

}

void debugLoggingOff() {

	device.updateSetting("logsEnabled", [value: "false", type: "bool"])
	log.info "${device.displayName}: debug logging turned off"

}

void refresh() {
// Refresh command on the parent device

	poll()

}

// ---------------------------------------------------------------------------------------
// child devices
// ---------------------------------------------------------------------------------------

String fanDni() { return "${device.id}-fan" }

String lightDni() { return "${device.id}-light" }

boolean fanEnabled() { return settings?.enabledFan != false }

boolean lightEnabled() { return settings?.enabledLight != false }

boolean isFan(cd) { return cd?.deviceNetworkId == fanDni() }

boolean isLight(cd) { return cd?.deviceNetworkId == lightDni() }

void syncChildDevices() {
// create the child devices that are enabled and delete the ones that are not

	syncChild(fanDni(), fanEnabled(), "Generic Component Fan Control", "${device.displayName} Fan")
	syncChild(lightDni(), lightEnabled(), "Generic Component Dimmer", "${device.displayName} Light")

	def fan = getChildDevice(fanDni())

	if (fan) {
		fan.sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(SPEED_NAMES + ["on", "off"]))
	}

}

void syncChild(String dni, boolean enabled, String driverName, String childName) {

	def child = getChildDevice(dni)

	try {

		if (enabled && !child) {
			addChildDevice("hubitat", driverName, dni, [name: childName, isComponent: false])
		} else if (!enabled && child) {
			deleteChildDevice(dni)
		}

	} catch (Exception e) {

		log.warn "${device.displayName}: could not ${enabled ? 'create' : 'delete'} child device ${childName}: ${e.message}"

	}

}

// ---------------------------------------------------------------------------------------
// fan speed mapping
//
// the fan has speeds 1-6 and Hubitat has 5 names, so speeds 1 and 2 share "low". the
// fanSpeedLow preference picks which of the two is sent for "low"; the other is not exposed.
// fan Level (the % HomeKit uses) is five even steps: 20, 40, 60, 80, 100.
// ---------------------------------------------------------------------------------------

int lowSpeedNumber() {

	try {
		return (settings?.fanSpeedLow?.toString()?.toInteger() == 1) ? 1 : 2
	} catch (Exception e) {
		return 2
	}

}

int speedIndexFromFan(fanSpeed) {
// fan speed number (1-6) -> position in SPEED_NAMES (0-4)

	int number = 0

	try {
		number = (fanSpeed as BigDecimal).intValue()
	} catch (Exception e) {
		number = 0
	}

	if (number <= 2) return 0
	if (number >= 6) return 4

	return number - 2

}

int fanSpeedFromIndex(int index) {
// position in SPEED_NAMES (0-4) -> fan speed number to send

	return (index <= 0) ? lowSpeedNumber() : Math.min(index, 4) + 2

}

int speedIndexFromLevel(int level) {
// fan Level % -> position in SPEED_NAMES (0-4): 1-20 low, 21-40 medium-low, ... 81-100 high

	int clamped = Math.max(1, Math.min(100, level))

	return (int) ((clamped - 1) / 20)

}

// ---------------------------------------------------------------------------------------
// commands from the child devices (called by the Generic Component drivers)
// ---------------------------------------------------------------------------------------

void componentRefresh(cd) {

	poll()

}

void componentOn(cd) {

	if (isFan(cd)) {
		queueCommand([fanOn: true])
	} else if (isLight(cd)) {
		// the fan keeps its own brightness, so only the switch is sent
		queueCommand([lightOn: true])
	}

}

void componentOff(cd) {

	if (isFan(cd)) {
		queueCommand([fanOn: false])
	} else if (isLight(cd)) {
		queueCommand([lightOn: false])
	}

}

void componentSetLevel(cd, level, duration = null) {

	int target = 0

	try {
		target = (level as BigDecimal).intValue()
	} catch (Exception e) {
		target = 0
	}

	if (target <= 0) {
		componentOff(cd)
		return
	}

	if (isLight(cd)) {

		// HomeKit can send a brightness without an On, so a level always turns the light on too
		queueCommand([lightOn: true, lightBrightness: Math.min(100, target)])

	} else if (isFan(cd)) {

		queueCommand([fanOn: true, fanSpeed: fanSpeedFromIndex(speedIndexFromLevel(target))])

	}

}

void componentSetSpeed(cd, speed) {

	if (!isFan(cd)) return

	String name = speed?.toString()

	if (name == "off") {
		componentOff(cd)
	} else if (name == "on") {
		componentOn(cd)
	} else if (SPEED_NAMES.contains(name)) {
		queueCommand([fanOn: true, fanSpeed: fanSpeedFromIndex(SPEED_NAMES.indexOf(name))])
	} else {
		log.warn "${device.displayName}: fan speed '${name}' is not supported"
	}

}

void componentCycleSpeed(cd) {

	if (!isFan(cd)) return

	int current = SPEED_NAMES.indexOf(cd.currentValue("speed")?.toString())	// -1 when off or unknown
	int next = (current < 0 || current >= SPEED_NAMES.size() - 1) ? 0 : current + 1

	queueCommand([fanOn: true, fanSpeed: fanSpeedFromIndex(next)])

}

// the Generic Component Dimmer has Start/Stop Level Change buttons. the fan has no ramping,
// so these exist only so that pressing them does not log an error.
void componentStartLevelChange(cd, direction) {

	logDebug "start level change is not supported"

}

void componentStopLevelChange(cd) {

	logDebug "stop level change is not supported"

}

// ---------------------------------------------------------------------------------------
// blackout window
// ---------------------------------------------------------------------------------------

Integer minuteOfDay(value) {
// "HH:mm", or the date-time string a Hubitat time input stores -> minutes after midnight

	if (value == null) return null

	try {

		String text = value.toString()

		if (!(text ==~ /\d{1,2}:\d{2}/)) text = toDateTime(text).format("HH:mm", location.timeZone)

		List parts = text.tokenize(":")

		return parts[0].toInteger() * 60 + parts[1].toInteger()

	} catch (Exception e) {

		return null

	}

}

boolean inBlackout() {

	if (settings?.blackoutEnabled == false) return false

	Integer start = minuteOfDay(settings?.blackoutStart)
	Integer end = minuteOfDay(settings?.blackoutEnd)
	Integer current = minuteOfDay(new Date().format("HH:mm", location.timeZone))

	if (start == null) start = DEFAULT_BLACKOUT_START
	if (end == null) end = DEFAULT_BLACKOUT_END
	if (current == null) return false

	// a window that runs past midnight (e.g. 23:50 to 00:10) wraps around
	return (start <= end) ? (current >= start && current < end) : (current >= start || current < end)

}

// ---------------------------------------------------------------------------------------
// polling
// ---------------------------------------------------------------------------------------

void schedulePolling() {

	int minutes = 5

	switch (settings?.pollingInterval?.toString()) {
		case "120": minutes = 2; break
		case "600": minutes = 10; break
	}

	// a random second keeps this from firing with everything else on the hub at :00
	int second = new Random().nextInt(60)

	String cron = "${second} 0/${minutes} * ? * *"

	schedule(cron, "poll")

}

void poll() {
// ask the fan for its state. skipped when a request is already open or a command is waiting,
// because the reply to that request carries the same state.

	if (!ipConfigured()) return

	if (inBlackout()) {
		logDebug "status request skipped: inside the blackout window"
		return
	}

	if (!pending().isEmpty()) return
	if (!claimRequestSlot()) return

	send([queryDynamicShadowData: 1], false)

}

// ---------------------------------------------------------------------------------------
// request pipeline
//
// one request at a time. a command is sent straight away when nothing is open. if a request
// is open, the command waits and is sent when the reply (or the timeout) comes back. commands
// that arrive while waiting are combined into that one request, newest value per key, so a
// burst from HomeKit (on + level) costs the fan one request instead of several.
// ---------------------------------------------------------------------------------------

String deviceKey() { return device.id.toString() }

boolean ipConfigured() {

	if (settings?.ipAddress?.toString()?.trim()) return true

	log.warn "${device.displayName}: the fan's IP address is not set"

	return false

}

ConcurrentHashMap pending() {

	pendingCommands.putIfAbsent(deviceKey(), new ConcurrentHashMap())

	return pendingCommands.get(deviceKey()) as ConcurrentHashMap

}

boolean claimRequestSlot() {
// true when no request is open, and marks one as open

	long nowMs = now()
	def openedAt = inFlight.putIfAbsent(deviceKey(), nowMs)

	if (openedAt == null) return true

	if (nowMs - (openedAt as long) > STALE_REQUEST_MS) {
		// the reply never arrived (e.g. hub restarted mid-request); do not stay locked forever
		inFlight.put(deviceKey(), nowMs)
		return true
	}

	return false

}

void releaseRequestSlot() {

	inFlight.remove(deviceKey())

}

void queueCommand(Map command) {

	if (!ipConfigured()) return

	if (inBlackout()) {
		log.info "${device.displayName}: command ${command} ignored: inside the blackout window"
		return
	}

	pending().putAll(command)

	sendPending()

}

void sendPending() {
// send everything that is waiting as one request, if no request is open

	ConcurrentHashMap waiting = pending()

	if (waiting.isEmpty()) return

	if (inBlackout()) {
		waiting.clear()
		retryWaiting.remove(deviceKey())
		return
	}

	if (!claimRequestSlot()) return

	Map body = [:]

	waiting.keySet().toList().each { key ->
		def value = waiting.remove(key)
		if (value != null) body[key] = value
	}

	if (body.isEmpty()) {
		releaseRequestSlot()
		return
	}

	// a request that carries a retry is marked, so that it is never retried again
	send(body, retryWaiting.remove(deviceKey()) != null)

}

void send(Map body, boolean isRetry) {
// the only place a request to the fan is made. the caller must hold the request slot.

	Map params = [
		uri: "http://${settings.ipAddress.toString().trim()}/mf",
		requestContentType: "application/json",
		contentType: "application/json",
		body: groovy.json.JsonOutput.toJson(body),
		timeout: HTTP_TIMEOUT_SECONDS
	]

	try {

		logDebug "sending ${body}"

		asynchttpPost("handleResponse", params, [body: body, isRetry: isRetry])

	} catch (Exception e) {

		releaseRequestSlot()
		log.error "${device.displayName}: could not send ${body}: ${e.message}"
		return

	}

	try {
		countRequest()
	} catch (Exception e) {
		logDebug "request counter error: ${e.message}"
	}

}

void handleResponse(response, data) {

	try {

		if (response.hasError()) {

			// logged once when the fan stops answering, not on every failed request
			if (unreachable.put(deviceKey(), true) == null) {
				log.warn "${device.displayName}: fan is not answering (${response.getErrorMessage()})"
			}

			retryOnce(data)

		} else {

			if (unreachable.remove(deviceKey()) != null) {
				log.info "${device.displayName}: fan is answering again"
			}

			def reply = parseJson(response.getData())

			logDebug "received ${reply}"

			if (reply instanceof Map) applyFanState(reply)

		}

	} catch (Exception e) {

		log.warn "${device.displayName}: could not read the fan's reply: ${e.message}"

	} finally {

		releaseRequestSlot()

		// anything that queued up while this request was open goes next, after a short gap
		if (!pending().isEmpty()) runInMillis(PACING_MS, "sendPending")

	}

}

void retryOnce(data) {
// put an unanswered command back in line, one time only
//
// this cannot loop or pile up:
//   - status polls are never retried
//   - a request that already carried a retry is never retried again, so one failed command
//     causes at most one extra request
//   - a key that a newer command has set since is left alone, so the newer command wins
//   - the retry goes through the same one-at-a-time pipeline as everything else

	Map body = (data?.body instanceof Map) ? (data.body as Map) : null

	if (body == null || body.containsKey("queryDynamicShadowData")) return

	if (data?.isRetry == true) {
		log.warn "${device.displayName}: command ${body} was not answered twice and has been dropped"
		return
	}

	if (inBlackout()) return

	ConcurrentHashMap waiting = pending()

	body.each { key, value ->
		if (value != null) waiting.putIfAbsent(key, value)
	}

	retryWaiting.put(deviceKey(), true)

	logDebug "command ${body} was not answered; it will be sent once more"

}

// ---------------------------------------------------------------------------------------
// fan state -> child device events
//
// each value is used only if the reply contains its key, so a partial reply can never be
// read as "off". Hubitat drops events whose value has not changed, so these can be sent on
// every reply without filling the event log.
// ---------------------------------------------------------------------------------------

void applyFanState(Map reply) {

	def fan = getChildDevice(fanDni())

	if (fan && reply.containsKey("fanOn")) {

		boolean on = (reply.fanOn == true)

		childEvent(fan, "switch", on ? "on" : "off")

		if (!on) {

			childEvent(fan, "speed", "off")
			childEvent(fan, "level", 0, "%")

		} else if (reply.containsKey("fanSpeed")) {

			int index = speedIndexFromFan(reply.fanSpeed)

			childEvent(fan, "speed", SPEED_NAMES[index])
			childEvent(fan, "level", (index + 1) * 20, "%")

		}

	}

	def light = getChildDevice(lightDni())

	if (light) {

		if (reply.containsKey("lightOn")) {
			childEvent(light, "switch", (reply.lightOn == true) ? "on" : "off")
		}

		if (reply.containsKey("lightBrightness") && reply.lightBrightness != null) {

			int brightness = 0

			try {
				brightness = (reply.lightBrightness as BigDecimal).intValue()
			} catch (Exception e) {
				brightness = 0
			}

			// the fan keeps its brightness while the light is off, as HomeKit expects
			if (brightness > 0) childEvent(light, "level", brightness, "%")

		}

	}

}

void childEvent(child, String name, value, String unit = null) {

	Map event = [name: name, value: value, descriptionText: "${child.displayName} ${name} is ${value}${unit ?: ''}"]

	if (unit) event.unit = unit

	child.sendEvent(event)

}

// ---------------------------------------------------------------------------------------
// request counter
//
// state.requestCount24h is the number of requests sent to the fan in the current hour plus
// the 23 before it. the count is kept in memory in 24 hourly buckets; state holds the
// display value and a backup so the count survives a hub reboot or a driver save.
// ---------------------------------------------------------------------------------------

void countRequest() {

	String key = deviceKey()
	long hour = (long) (now() / 3600000L)

	Map counter = requestCounters.get(key) as Map

	if (counter == null) {

		List saved = (state.requestBuckets instanceof List) ? (state.requestBuckets as List) : null
		List restored = (saved != null && saved.size() == 24) ? saved.collect { (it ?: 0) as int } : ([0] * 24)
		long savedHour = (state.requestBucketHour != null) ? (state.requestBucketHour as long) : hour

		counter = [hour: savedHour, buckets: restored]

	}

	List buckets = counter.buckets as List
	long lastHour = counter.hour as long

	if (hour > lastHour) {

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

// ---------------------------------------------------------------------------------------
// logging
// ---------------------------------------------------------------------------------------

void logDebug(String message) {

	if (logsEnabled) log.debug "${device.displayName}: ${message}"

}

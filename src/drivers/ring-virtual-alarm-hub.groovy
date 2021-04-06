/**
 *  Ring Virtual Alarm Hub Driver
 *
 *  Copyright 2019 Ben Rimmasch
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
 *
 *  Change Log:
 *  2019-04-26: Initial
 *  2019-11-15: Import URL
 *  2019-12-20: Added description to syncing preference for clarity
 *  2020-01-09: Fixed update to HSM status to only happen when necessary
 *              Added fireAlarm attribute to hold fire alarm status
 *  2020-02-29: Changed namespace
 *  2020-05-16: Fixed base station brightness command
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
  definition(name: "Ring Virtual Alarm Hub", namespace: "ring-hubitat-codahq", author: "Ben Rimmasch",
    importUrl: "https://raw.githubusercontent.com/codahq/ring_hubitat_codahq/master/src/drivers/ring-virtual-alarm-hub.groovy") {
    capability "Actuator"
    capability "Audio Volume"
    capability "Alarm"
    capability "Refresh"

    attribute "mode", "string"
    attribute "entryDelay", "string"
    attribute "exitDelay", "string"
    attribute "fireAlarm", "string"
    attribute "brightness", "number"
    attribute "countdownTimeLeft", "number"
    attribute "countdownTotal", "number"

    command "setBrightness", [[name: "Set LED Brightness*", type: "NUMBER", range: "0..100", description: "Choose a value between 0 and 100"]]
    command "setMode", [[name: "Set Mode*", type: "ENUM", description: "Set the Ring Alarm's mode", constraints: ["Disarmed", "Home", "Away"]]]
    command "sirenTest"
    command "createDevices"
  }

  preferences {
    input name: "syncRingToHsm", type: "bool", title: "<b>Sync Ring Alarm mode to HSM mode?</b>", description: "When the Ring mode changes would you like the HSM mode to follow it?", defaultValue: false
    input name: "cancelAlertsOnDisarm", type: "bool", title: "Cancel HSM Alerts on Ring Alarm Disarm?", defaultValue: true
    input name: "descriptionTextEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "traceLogEnable", type: "bool", title: "Enable trace logging", defaultValue: false
  }
}

private logInfo(msg) {
  if (descriptionTextEnable) log.info msg
}

def logDebug(msg) {
  if (logEnable) log.debug msg
}

def logTrace(msg) {
  if (traceLogEnable) log.trace msg
}

private getVOLUME_INC() {
  return 5 //somebody can make this a preference if they feel strongly about it
}

def createDevices() {
  logDebug "Attempting to create devices."
  parent.createDevices(device.getDataValue("zid"))
}

def refresh() {
  logDebug "Attempting to refresh."
  parent.refresh(device.getDataValue("zid"))
}

def setMode(mode) {
  logDebug "setMode(${mode})"
  if (mode == "Disarmed" && device.currentValue("mode") != "off") {
    def data = ["mode": "none"]
    parent.simpleRequest("setcommand", [type: "security-panel.switch-mode", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: data])
  }
  else if (mode == "Home" && device.currentValue("mode") != "home") {
    def data = ["mode": "some"]
    parent.simpleRequest("setcommand", [type: "security-panel.switch-mode", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: data])
  }
  else if (mode == "Away" && device.currentValue("mode") != "away") {
    def data = ["mode": "all"]
    parent.simpleRequest("setcommand", [type: "security-panel.switch-mode", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: data])
  }
  else {
    logInfo "${device.label} already set to ${mode}.  No change necessary"
  }
}

/*alarm capabilities start*/

def off() {
  logDebug "Attempting to stop siren and/or strobe"
  def alarm = device.currentValue("alarm")
  logTrace "previous value alarm: $alarm"
  //sendEvent(name: "alarm", value: "off")
  parent.simpleRequest("setcommand", [type: "security-panel.silence-siren", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: {
  }])
}

def siren() {
  logDebug "Attempting to turn on siren."
  parent.simpleRequest("setcommand", [type: "security-panel.sound-siren", zid: device.getDataValue("security-panel-zid"), dst: device.getDataValue("src"), data: {
  }])
}

def strobe() {
  log.error "The device ${device.getDisplayName()} does not support the strobe functionality"
}

def both() {
  logDebug "Attempting to turn on siren and strobe."
  strobe()
  siren()
}

def sirenTest() {
  if (device.currentValue("mode") != "off") {
    log.warn "Please disarm the alarm before testing the siren."
    return
  }
  //siren-test.stop to cancel
  parent.simpleRequest("setcommand", [type: "siren-test.start", zid: device.getDataValue("security-panel-zid"), dst: null, data: {
  }])
}
/*alarm capabilities end*/

def setVolume(vol) {
  logDebug "Attempting to set volume."
  vol > 100 ? 100 : vol
  vol < 0 ? 0 : vol
  if (vol == 0 && !isMuted()) {
    logTrace "muting"
    state.prevVolume = device.currentValue("volume")
    sendEvent(name: "mute", value: "muted")
  }
  else if (vol != 0 && isMuted()) {
    logTrace "unmuting"
    sendEvent(name: "mute", value: "unmuted")
  }
  else {
    logDebug "No mute/unmute needed..."
  }
  if (device.currentValue("volume") != vol) {
    logTrace "requesting volume change from ${device.currentValue("volume")} to ${vol}"
    def data = ["volume": (vol == null ? 50 : vol).toDouble() / 100]
    parent.simpleRequest("setdevice", [zid: device.getDataValue("hub.redsky-zid"), dst: null, data: data])
  }
  else {
    logInfo "Already at volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeUp() {
  logDebug "Attempting to raise volume."
  def nextVol = device.currentValue("volume") + VOLUME_INC
  if (nextVol <= 100) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already max volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def volumeDown() {
  logDebug "Attempting to lower volume."
  def nextVol = device.currentValue("volume") - VOLUME_INC
  if (nextVol >= 0) {
    setVolume(nextVol)
  }
  else {
    logInfo "Already min volume."
    sendEvent(name: "volume", value: device.currentValue("volume"))
  }
}

def mute() {
  logDebug "Attempting to mute."
  setVolume(0)
}

def unmute() {
  logDebug "Attempting to unmute."
  setVolume(state.prevVolume)
}

private isMuted() {
  return device.currentValue("mute") == "muted"
}

def setBrightness(brightness) {
  logDebug "Attempting to set brightness ${brightness}."
  brightness = brightness > 100 ? 100 : brightness
  brightness = brightness < 0 ? 0 : brightness
  def data = ["brightness": (brightness == null ? 100 : brightness).toDouble() / 100]
  parent.simpleRequest("setdevice", [zid: device.getDataValue("hub.redsky-zid"), dst: device.getDataValue("src"), data: data])
}

private getRING_TO_HSM_MODE_MAP() {
  return [
    "home": [set: "armHome", status: "armedHome"],
    "away": [set: "armAway", status: "armedAway"],
    "off": [set: "disarm", status: "disarmed"]
  ]
}

def setValues(deviceInfo) {
  logDebug "setValues(deviceInfo)"
  logTrace "deviceInfo: ${JsonOutput.prettyPrint(JsonOutput.toJson(deviceInfo))}"

  saveImportantInfo(deviceInfo)

  if (deviceInfo.state?.mode != null) {
    def mode = MODES["${deviceInfo.state?.mode}"]
    checkChanged("mode", mode)
    if (mode == "off") {
      sendEvent(name: "countdownTimeLeft", value: 0)
      sendEvent(name: "countdownTotal", value: 0)
      checkChanged("entryDelay", "inactive")
      checkChanged("exitDelay", "inactive")
    }
  }
  if (deviceInfo.state?.mode && syncRingToHsm && location.hsmStatus != RING_TO_HSM_MODE_MAP[MODES["${deviceInfo.state?.mode}"]].status) {
    def hsmMode = RING_TO_HSM_MODE_MAP[MODES["${deviceInfo.state?.mode}"]].set
    logInfo "Setting HSM to ${hsmMode}"
    logTrace "mode: ${MODES["${deviceInfo.state?.mode}"]} hsmStatus: ${location.hsmStatus}"
    sendLocationEvent(name: "hsmSetArm", value: hsmMode)
  }
  if (deviceInfo.state?.siren && device.currentValue("alarm") != deviceInfo.state?.siren.state) {
    def alarm = deviceInfo.state?.siren.state == "on" ? "siren" : "off"
    sendEvent(name: "alarm", value: alarm)
    if (alarm != "off") {
      sendEvent(name: "countdownTimeLeft", value: 0)
      sendEvent(name: "countdownTotal", value: 0)
      checkChanged("entryDelay", "inactive")
    }
  }
  if (deviceInfo.state?.alarmInfo) {
    def entryDelay = deviceInfo.state?.alarmInfo.state == "entry-delay" ? "active" : "inactive"
    checkChanged("entryDelay", entryDelay)

    //TODO: after a small cooking mishap noticed that fire-alarm has a different alarmInfo.state than intrusion so I added an attribute and a
    //case for it while I decide what to do with it long term.  should this also set the "alarm" attribute or should the base also implement
    //smoke alarm? or neither and it's just fine in the attribute since there will be a device for the smoke detector?  in fact, do I just
    //ignore this update because the smoke detector device will already get its own update?  or does it?

    def fireAlarm = deviceInfo.state?.alarmInfo.state == "fire-alarm" ? "active" : "inactive"
    checkChanged("fireAlarm", fireAlarm)

    //TODO: work on faulted devices
    //state.faultedDevices.each {
    //  def faultedDev = parent.getChildByZID(it)
    //  [DNI: faultedDev.dni, Name: faultedDev.name]
    //}.collect()
  }
  if (deviceInfo.state?.transition) {
    def exitDelay = deviceInfo.state?.transition == "exit" ? "active" : "inactive"
    checkChanged("exitDelay", exitDelay)
    sendEvent(name: "countdownTimeLeft", value: deviceInfo.state?.timeLeft)
    sendEvent(name: "countdownTotal", value: deviceInfo.state?.total)
  }
  if (deviceInfo.state?.keySet()?.contains("transitionDelayEndTimestamp")) {
    def exitDelay = deviceInfo.state?.transitionDelayEndTimestamp != null ? "active" : "inactive"
    checkChanged("exitDelay", exitDelay)
  }
  if (deviceInfo.state?.percent) {
    log.warn "${device.label} is updating firmware: ${deviceInfo.state?.percent}% complete"
  }
  if (cancelAlertsOnDisarm && MODES["${deviceInfo.state?.mode}"] == "off") {
    sendLocationEvent(name: "hsmSetArm", value: "cancelAlerts")
  }
  if (deviceInfo.state?.volume != null) {
    checkChanged("volume", (deviceInfo.state.volume * 100) as Integer)
  }
  if (deviceInfo.batteryLevel != null) {
    checkChanged("battery", deviceInfo.batteryLevel)
  }
  if (deviceInfo.state?.brightness != null) {
    checkChanged("brightness", (deviceInfo.state.brightness * 100) as Integer)
  }
  if (deviceInfo.lastUpdate) {
    state.lastUpdate = deviceInfo.lastUpdate
  }
  if (deviceInfo.impulseType) {
    state.impulseType = deviceInfo.impulseType
  }
  if (deviceInfo.lastCommTime) {
    state.signalStrength = deviceInfo.lastCommTime
  }
  if (deviceInfo.nextExpectedWakeup) {
    state.nextExpectedWakeup = deviceInfo.nextExpectedWakeup
  }
  if (deviceInfo.signalStrength) {
    state.signalStrength = deviceInfo.signalStrength
  }
  if (deviceInfo.firmware && device.getDataValue("firmware") != deviceInfo.firmware) {
    device.updateDataValue("firmware", deviceInfo.firmware)
  }
  if (deviceInfo.state?.version && deviceInfo.state?.version?.softwareVersion
    && device.getDataValue("softwareVersion") != deviceInfo.state?.version?.softwareVersion) {
    device.updateDataValue("softwareVersion", deviceInfo.state?.version?.softwareVersion)
  }
  if (deviceInfo.state?.networks) {
    def nw = deviceInfo.state?.networks
    if (nw.ppp0) {
      sendEvent(name: nw.ppp0.type.capitalize(), value: "${nw.ppp0.name} RSSI ${nw.ppp0.rssi}")
    }
    if (nw.wlan0.type != null) {
      sendEvent(name: nw.wlan0.type.capitalize(), value: "${nw.wlan0.ssid} RSSI ${nw.wlan0.rssi}")
    }
  }
  if (deviceInfo.state?.batteryBackup) {
    sendEvent(name: "batteryBackup", value: deviceInfo.state?.batteryBackup)
  }
}

def checkChanged(attribute, newStatus) {
  if (device.currentValue(attribute) != newStatus) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
    sendEvent(name: attribute, value: newStatus)
  }
}

def checkChanged(attribute, newStatus, unit) {
  if (device.currentValue(attribute) != newStatus) {
    logInfo "${attribute.capitalize()} for device ${device.label} is ${newStatus}"
    sendEvent(name: attribute, value: newStatus, unit: unit)
  }
}

def saveImportantInfo(deviceInfo) {
  if (deviceInfo.deviceType == "security-panel" && device.getDataValue("security-panel-zid") != deviceInfo.zid) {
    device.updateDataValue("security-panel-zid", deviceInfo.zid)
  }
  if (deviceInfo.deviceType == "adapter.zwave" && device.getDataValue("adapter.zwave-zid") != deviceInfo.zid) {
    device.updateDataValue("adapter.zwave-zid", deviceInfo.zid)
  }
  if (deviceInfo.deviceType == "hub.redsky" && device.getDataValue("hub.redsky-zid") != deviceInfo.zid) {
    device.updateDataValue("hub.redsky-zid", deviceInfo.zid)
  }
  if (deviceInfo.deviceType == "access-code.vault" && device.getDataValue("access-code.vault-zid") != deviceInfo.zid) {
    device.updateDataValue("access-code.vault-zid", deviceInfo.zid)
  }
}

private getMODES() {
  return [
    "none": "off",
    "some": "home",
    "all": "away"
  ]
}

/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the software
 * to the public domain. We make this dedication for the benefit of the
 * public at large and to the detriment of our heirs and successors. We
 * intend this dedication to be an overt act of relinquishment in perpetuity
 * of all present and future rights to this software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Sensor Monitor App
 *
 * Links up to 16 Hubitat motion, contact, water, or smoke sensors to a SenseCAP Indicator display.
 *
 *
 * Author: jlslate (slate)
 * Version: 3.1.0  — 1x1 through 7x7 grids, empty slot support, sensor name as default label, emoji stripping
 */

definition(
    name: "SenseCAP Sensor Monitor",
    namespace: "community",
    author: "jlslate (slate)",
    description: "Shows motion, contact, water, and smoke sensor states on a SenseCAP Indicator display via MQTT",
    category: "Integration",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Indicator MQTT simplified", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Indicator Device</b>") {
            input name: "indicatorDevice",
                type: "capability.actuator",
                title: "Select your SenseCAP Indicator device",
                required: true,
                multiple: false
        }

        section("<b>Display Layout</b>") {
            input name: "gridLayout",
                type: "enum",
                title: "Grid Layout",
                options: [
                    "1x1": "1×1 (1 sensor)",
                    "2x2": "2×2 (4 sensors)",
                    "3x3": "3×3 (9 sensors)",
                    "4x4": "4×4 (16 sensors)",
                    "5x5": "5×5 (25 sensors)",
                    "6x6": "6×6 (36 sensors)",
                    "7x7": "7×7 (49 sensors)"
                ],
                defaultValue: "2x2",
                required: true,
                submitOnChange: true
        }

        int maxSlots = maxSlots()

        section("<b>Sensor → Display Object Mapping</b>") {
            paragraph "For each slot choose the sensor type, assign a device, and set a label."

            (1..maxSlots).each { idx ->
                String typeKey = "sensorType${idx}"
                String type    = settings[typeKey] ?: "motion"

                paragraph "<hr><b>Slot ${idx}</b>", width: 12

                input name: typeKey,
                    type: "enum",
                    title: "Slot ${idx} — Sensor Type",
                    options: ["none": "— No sensor —", "motion": "Motion sensor", "contact": "Contact sensor", "water": "Water sensor", "smoke": "Smoke sensor"],
                    defaultValue: "none",
                    required: true,
                    submitOnChange: true,
                    width: 4

                if (type == "none") {
                    // Empty slot — no device picker or label shown
                } else if (type == "contact") {
                    input name: "sensor${idx}",
                        type: "capability.contactSensor",
                        title: "Slot ${idx} — Contact Sensor",
                        required: false,
                        multiple: false,
                        width: 4
                } else if (type == "water") {
                    input name: "sensor${idx}",
                        type: "capability.waterSensor",
                        title: "Slot ${idx} — Water Sensor",
                        required: false,
                        multiple: false,
                        width: 4
                } else if (type == "smoke") {
                    input name: "sensor${idx}",
                        type: "capability.smokeDetector",
                        title: "Slot ${idx} — Smoke Sensor",
                        required: false,
                        multiple: false,
                        width: 4
                } else {
                    input name: "sensor${idx}",
                        type: "capability.motionSensor",
                        title: "Slot ${idx} — Motion Sensor",
                        required: false,
                        multiple: false,
                        width: 4
                }

                if (type != "none") {
                    input name: "label${idx}",
                        type: "text",
                        title: "Slot ${idx} — Label (leave blank to use sensor name)",
                        required: false,
                        width: 3
                }
            }
        }

        section("<b>Options</b>") {
            input name: "syncOnStartup",
                type: "bool",
                title: "Sync all sensor states to display on startup / save",
                defaultValue: true,
                description: "Recommended — keeps display accurate after a hub reboot"

            input name: "logLevel",
                type: "enum",
                title: "Logging Level",
                options: [
                    "0": "None",
                    "1": "Info only",
                    "2": "Info + Debug"
                ],
                defaultValue: "1",
                required: true
        }

        section("<b>Status</b>") {
            paragraph "Sensors configured: <b>${subscribedCount()}</b>"
            if (indicatorDevice) {
                paragraph "Indicator MQTT status: <b>${indicatorDevice.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
        }
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    infoLog "[SensorMonitor] Installed"
    initialize()
}

def updated() {
    infoLog "[SensorMonitor] Updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    unsubscribe()
}

def initialize() {
    (1..49).each { idx ->
        def dev  = settings["sensor${idx}"]
        String t = settings["sensorType${idx}"] ?: "none"
        if (dev && t != "none") {
            if (t == "contact") {
                subscribe(dev, "contact", "contactHandler${idx}")
            } else if (t == "water") {
                subscribe(dev, "water", "waterHandler${idx}")
            } else if (t == "smoke") {
                subscribe(dev, "smoke", "smokeHandler${idx}")
            } else {
                subscribe(dev, "motion", "motionHandler${idx}")
            }
        }
    }

    if (settings.syncOnStartup) {
        runIn(12, syncAllSensors)
    }

    // Push layout only when grid size changes, then push labels and types
    if (settings.indicatorDevice) {
        String newGrid = settings.gridLayout ?: "2x2"
        try {
            settings.indicatorDevice.setGridLayout(newGrid)
            settings.indicatorDevice.pushLayout()
        } catch (Exception e) {
            infoLog "[SensorMonitor] WARN — device call failed: ${e.message}"
        }
        // Schedule AFTER device calls — even if they fail, labels/types should still sync
        runIn(8, pushLabelsAndTypes)
    }

    // Re-sync all sensor states whenever the SenseCAP display reboots
    if (settings.indicatorDevice) {
        subscribe(settings.indicatorDevice, "displayRebooted", displayRebootedHandler)
    }

    infoLog "[SensorMonitor] Initialized — ${subscribedCount()} sensor(s) subscribed"
}

// ── Deferred label/type push ─────────────────────────────────────────────────

def pushLabelsAndTypes() {
    infoLog "[SensorMonitor] pushLabelsAndTypes fired — grid=${settings.gridLayout} maxSlots=${maxSlots()}"
    if (!settings.indicatorDevice) return
    int maxChars = (settings.gridLayout == "7x7") ? 4 : (settings.gridLayout == "1x1") ? 30 : (settings.gridLayout == "6x6") ? 5 : (settings.gridLayout == "5x5") ? 6 : (settings.gridLayout == "4x4") ? 7 : (settings.gridLayout == "3x3") ? 11 : 16
    def labels    = [:]
    def slotTypes = [:]
    (1..maxSlots()).each { idx ->
        String slotT = settings["sensorType${idx}"] ?: "none"
        def slotDev = settings["sensor${idx}"]
        boolean hasDevice = slotDev != null && slotT != "none"
        String sensorName   = hasDevice ? stripEmoji(slotDev.displayName ?: "") : ""
        String customLabel  = settings["label${idx}"]?.toString()?.trim() ?: ""
        // Use custom label if set and not the old default "Sensor N" placeholder
        boolean isDefaultLabel = customLabel ==~ /Sensor \d+/
        String raw = hasDevice ? ((!customLabel || isDefaultLabel) ? sensorName : stripEmoji(customLabel)) : ""
        if (!raw) raw = hasDevice ? "Sensor ${idx}" : ""  // final fallback
        labels[idx]    = wrapLabel(raw, maxChars)
        slotTypes[idx] = slotT
    }
    settings.indicatorDevice.updateSlotTypes(slotTypes)
    pauseExecution(300)
    settings.indicatorDevice.updateLabels(labels)
}

// ── Motion Event Handlers ────────────────────────────────────────────────────

def motionHandler1(evt)  { handleMotion(evt, 1)  }
def motionHandler2(evt)  { handleMotion(evt, 2)  }
def motionHandler3(evt)  { handleMotion(evt, 3)  }
def motionHandler4(evt)  { handleMotion(evt, 4)  }
def motionHandler5(evt)  { handleMotion(evt, 5)  }
def motionHandler6(evt)  { handleMotion(evt, 6)  }
def motionHandler7(evt)  { handleMotion(evt, 7)  }
def motionHandler8(evt)  { handleMotion(evt, 8)  }
def motionHandler9(evt)  { handleMotion(evt, 9)  }
def motionHandler10(evt) { handleMotion(evt, 10) }
def motionHandler11(evt) { handleMotion(evt, 11) }
def motionHandler12(evt) { handleMotion(evt, 12) }
def motionHandler13(evt) { handleMotion(evt, 13) }
def motionHandler14(evt) { handleMotion(evt, 14) }
def motionHandler15(evt) { handleMotion(evt, 15) }
def motionHandler16(evt) { handleMotion(evt, 16) }
def motionHandler17(evt)  { handleMotion(evt, 17)  }
def motionHandler18(evt)  { handleMotion(evt, 18)  }
def motionHandler19(evt)  { handleMotion(evt, 19)  }
def motionHandler20(evt)  { handleMotion(evt, 20)  }
def motionHandler21(evt)  { handleMotion(evt, 21)  }
def motionHandler22(evt)  { handleMotion(evt, 22)  }
def motionHandler23(evt)  { handleMotion(evt, 23)  }
def motionHandler24(evt)  { handleMotion(evt, 24)  }
def motionHandler25(evt)  { handleMotion(evt, 25)  }
def motionHandler26(evt)  { handleMotion(evt, 26)  }
def motionHandler27(evt)  { handleMotion(evt, 27)  }
def motionHandler28(evt)  { handleMotion(evt, 28)  }
def motionHandler29(evt)  { handleMotion(evt, 29)  }
def motionHandler30(evt)  { handleMotion(evt, 30)  }
def motionHandler31(evt)  { handleMotion(evt, 31)  }
def motionHandler32(evt)  { handleMotion(evt, 32)  }
def motionHandler33(evt)  { handleMotion(evt, 33)  }
def motionHandler34(evt)  { handleMotion(evt, 34)  }
def motionHandler35(evt)  { handleMotion(evt, 35)  }
def motionHandler36(evt)  { handleMotion(evt, 36)  }
def motionHandler37(evt)  { handleMotion(evt, 37) }
def motionHandler38(evt)  { handleMotion(evt, 38) }
def motionHandler39(evt)  { handleMotion(evt, 39) }
def motionHandler40(evt)  { handleMotion(evt, 40) }
def motionHandler41(evt)  { handleMotion(evt, 41) }
def motionHandler42(evt)  { handleMotion(evt, 42) }
def motionHandler43(evt)  { handleMotion(evt, 43) }
def motionHandler44(evt)  { handleMotion(evt, 44) }
def motionHandler45(evt)  { handleMotion(evt, 45) }
def motionHandler46(evt)  { handleMotion(evt, 46) }
def motionHandler47(evt)  { handleMotion(evt, 47) }
def motionHandler48(evt)  { handleMotion(evt, 48) }
def motionHandler49(evt)  { handleMotion(evt, 49) }

private void handleMotion(evt, int idx) {
    debugLog "Motion slot ${idx} (${evt.displayName}): ${evt.value}"
    if (!settings.indicatorDevice) { infoLog "[SensorMonitor] WARN — No Indicator device configured"; return }
    if (evt.value == "active") {
        settings.indicatorDevice.setMotionActive(idx)
    } else {
        settings.indicatorDevice.setMotionInactive(idx)
    }
}

// ── Contact Event Handlers ───────────────────────────────────────────────────

def contactHandler1(evt)  { handleContact(evt, 1)  }
def contactHandler2(evt)  { handleContact(evt, 2)  }
def contactHandler3(evt)  { handleContact(evt, 3)  }
def contactHandler4(evt)  { handleContact(evt, 4)  }
def contactHandler5(evt)  { handleContact(evt, 5)  }
def contactHandler6(evt)  { handleContact(evt, 6)  }
def contactHandler7(evt)  { handleContact(evt, 7)  }
def contactHandler8(evt)  { handleContact(evt, 8)  }
def contactHandler9(evt)  { handleContact(evt, 9)  }
def contactHandler10(evt) { handleContact(evt, 10) }
def contactHandler11(evt) { handleContact(evt, 11) }
def contactHandler12(evt) { handleContact(evt, 12) }
def contactHandler13(evt) { handleContact(evt, 13) }
def contactHandler14(evt) { handleContact(evt, 14) }
def contactHandler15(evt) { handleContact(evt, 15) }
def contactHandler16(evt) { handleContact(evt, 16) }
def contactHandler17(evt) { handleContact(evt, 17) }
def contactHandler18(evt) { handleContact(evt, 18) }
def contactHandler19(evt) { handleContact(evt, 19) }
def contactHandler20(evt) { handleContact(evt, 20) }
def contactHandler21(evt) { handleContact(evt, 21) }
def contactHandler22(evt) { handleContact(evt, 22) }
def contactHandler23(evt) { handleContact(evt, 23) }
def contactHandler24(evt) { handleContact(evt, 24) }
def contactHandler25(evt) { handleContact(evt, 25) }
def contactHandler26(evt) { handleContact(evt, 26) }
def contactHandler27(evt) { handleContact(evt, 27) }
def contactHandler28(evt) { handleContact(evt, 28) }
def contactHandler29(evt) { handleContact(evt, 29) }
def contactHandler30(evt) { handleContact(evt, 30) }
def contactHandler31(evt) { handleContact(evt, 31) }
def contactHandler32(evt) { handleContact(evt, 32) }
def contactHandler33(evt) { handleContact(evt, 33) }
def contactHandler34(evt) { handleContact(evt, 34) }
def contactHandler35(evt) { handleContact(evt, 35) }
def contactHandler36(evt) { handleContact(evt, 36) }
def contactHandler37(evt) { handleContact(evt, 37) }
def contactHandler38(evt) { handleContact(evt, 38) }
def contactHandler39(evt) { handleContact(evt, 39) }
def contactHandler40(evt) { handleContact(evt, 40) }
def contactHandler41(evt) { handleContact(evt, 41) }
def contactHandler42(evt) { handleContact(evt, 42) }
def contactHandler43(evt) { handleContact(evt, 43) }
def contactHandler44(evt) { handleContact(evt, 44) }
def contactHandler45(evt) { handleContact(evt, 45) }
def contactHandler46(evt) { handleContact(evt, 46) }
def contactHandler47(evt) { handleContact(evt, 47) }
def contactHandler48(evt) { handleContact(evt, 48) }
def contactHandler49(evt) { handleContact(evt, 49) }

private void handleContact(evt, int idx) {
    debugLog "Contact slot ${idx} (${evt.displayName}): ${evt.value}"
    if (!settings.indicatorDevice) { infoLog "[SensorMonitor] WARN — No Indicator device configured"; return }
    if (evt.value == "open") {
        settings.indicatorDevice.setMotionActive(idx)
    } else {
        settings.indicatorDevice.setMotionInactive(idx)
    }
}

// ── Water Event Handlers ─────────────────────────────────────────────────────

def waterHandler1(evt)  { handleWater(evt, 1)  }
def waterHandler2(evt)  { handleWater(evt, 2)  }
def waterHandler3(evt)  { handleWater(evt, 3)  }
def waterHandler4(evt)  { handleWater(evt, 4)  }
def waterHandler5(evt)  { handleWater(evt, 5)  }
def waterHandler6(evt)  { handleWater(evt, 6)  }
def waterHandler7(evt)  { handleWater(evt, 7)  }
def waterHandler8(evt)  { handleWater(evt, 8)  }
def waterHandler9(evt)  { handleWater(evt, 9)  }
def waterHandler10(evt) { handleWater(evt, 10) }
def waterHandler11(evt) { handleWater(evt, 11) }
def waterHandler12(evt) { handleWater(evt, 12) }
def waterHandler13(evt) { handleWater(evt, 13) }
def waterHandler14(evt) { handleWater(evt, 14) }
def waterHandler15(evt) { handleWater(evt, 15) }
def waterHandler16(evt) { handleWater(evt, 16) }
def waterHandler17(evt)  { handleWater(evt, 17)  }
def waterHandler18(evt)  { handleWater(evt, 18)  }
def waterHandler19(evt)  { handleWater(evt, 19)  }
def waterHandler20(evt)  { handleWater(evt, 20)  }
def waterHandler21(evt)  { handleWater(evt, 21)  }
def waterHandler22(evt)  { handleWater(evt, 22)  }
def waterHandler23(evt)  { handleWater(evt, 23)  }
def waterHandler24(evt)  { handleWater(evt, 24)  }
def waterHandler25(evt)  { handleWater(evt, 25)  }
def waterHandler26(evt)  { handleWater(evt, 26)  }
def waterHandler27(evt)  { handleWater(evt, 27)  }
def waterHandler28(evt)  { handleWater(evt, 28)  }
def waterHandler29(evt)  { handleWater(evt, 29)  }
def waterHandler30(evt)  { handleWater(evt, 30)  }
def waterHandler31(evt)  { handleWater(evt, 31)  }
def waterHandler32(evt)  { handleWater(evt, 32)  }
def waterHandler33(evt)  { handleWater(evt, 33)  }
def waterHandler34(evt)  { handleWater(evt, 34)  }
def waterHandler35(evt)  { handleWater(evt, 35)  }
def waterHandler36(evt)  { handleWater(evt, 36)  }
def waterHandler37(evt)   { handleWater(evt, 37) }
def waterHandler38(evt)   { handleWater(evt, 38) }
def waterHandler39(evt)   { handleWater(evt, 39) }
def waterHandler40(evt)   { handleWater(evt, 40) }
def waterHandler41(evt)   { handleWater(evt, 41) }
def waterHandler42(evt)   { handleWater(evt, 42) }
def waterHandler43(evt)   { handleWater(evt, 43) }
def waterHandler44(evt)   { handleWater(evt, 44) }
def waterHandler45(evt)   { handleWater(evt, 45) }
def waterHandler46(evt)   { handleWater(evt, 46) }
def waterHandler47(evt)   { handleWater(evt, 47) }
def waterHandler48(evt)   { handleWater(evt, 48) }
def waterHandler49(evt)   { handleWater(evt, 49) }

private void handleWater(evt, int idx) {
    debugLog "Water slot ${idx} (${evt.displayName}): ${evt.value}"
    if (!settings.indicatorDevice) { infoLog "[SensorMonitor] WARN — No Indicator device configured"; return }
    if (evt.value == "wet") {
        settings.indicatorDevice.setMotionActive(idx)
    } else {
        settings.indicatorDevice.setMotionInactive(idx)
    }
}

// ── Smoke Event Handlers ─────────────────────────────────────────────────────

def smokeHandler1(evt)  { handleSmoke(evt, 1)  }
def smokeHandler2(evt)  { handleSmoke(evt, 2)  }
def smokeHandler3(evt)  { handleSmoke(evt, 3)  }
def smokeHandler4(evt)  { handleSmoke(evt, 4)  }
def smokeHandler5(evt)  { handleSmoke(evt, 5)  }
def smokeHandler6(evt)  { handleSmoke(evt, 6)  }
def smokeHandler7(evt)  { handleSmoke(evt, 7)  }
def smokeHandler8(evt)  { handleSmoke(evt, 8)  }
def smokeHandler9(evt)  { handleSmoke(evt, 9)  }
def smokeHandler10(evt) { handleSmoke(evt, 10) }
def smokeHandler11(evt) { handleSmoke(evt, 11) }
def smokeHandler12(evt) { handleSmoke(evt, 12) }
def smokeHandler13(evt) { handleSmoke(evt, 13) }
def smokeHandler14(evt) { handleSmoke(evt, 14) }
def smokeHandler15(evt) { handleSmoke(evt, 15) }
def smokeHandler16(evt) { handleSmoke(evt, 16) }
def smokeHandler17(evt)  { handleSmoke(evt, 17)  }
def smokeHandler18(evt)  { handleSmoke(evt, 18)  }
def smokeHandler19(evt)  { handleSmoke(evt, 19)  }
def smokeHandler20(evt)  { handleSmoke(evt, 20)  }
def smokeHandler21(evt)  { handleSmoke(evt, 21)  }
def smokeHandler22(evt)  { handleSmoke(evt, 22)  }
def smokeHandler23(evt)  { handleSmoke(evt, 23)  }
def smokeHandler24(evt)  { handleSmoke(evt, 24)  }
def smokeHandler25(evt)  { handleSmoke(evt, 25)  }
def smokeHandler26(evt)  { handleSmoke(evt, 26)  }
def smokeHandler27(evt)  { handleSmoke(evt, 27)  }
def smokeHandler28(evt)  { handleSmoke(evt, 28)  }
def smokeHandler29(evt)  { handleSmoke(evt, 29)  }
def smokeHandler30(evt)  { handleSmoke(evt, 30)  }
def smokeHandler31(evt)  { handleSmoke(evt, 31)  }
def smokeHandler32(evt)  { handleSmoke(evt, 32)  }
def smokeHandler33(evt)  { handleSmoke(evt, 33)  }
def smokeHandler34(evt)  { handleSmoke(evt, 34)  }
def smokeHandler35(evt)  { handleSmoke(evt, 35)  }
def smokeHandler36(evt)  { handleSmoke(evt, 36)  }
def smokeHandler37(evt)   { handleSmoke(evt, 37) }
def smokeHandler38(evt)   { handleSmoke(evt, 38) }
def smokeHandler39(evt)   { handleSmoke(evt, 39) }
def smokeHandler40(evt)   { handleSmoke(evt, 40) }
def smokeHandler41(evt)   { handleSmoke(evt, 41) }
def smokeHandler42(evt)   { handleSmoke(evt, 42) }
def smokeHandler43(evt)   { handleSmoke(evt, 43) }
def smokeHandler44(evt)   { handleSmoke(evt, 44) }
def smokeHandler45(evt)   { handleSmoke(evt, 45) }
def smokeHandler46(evt)   { handleSmoke(evt, 46) }
def smokeHandler47(evt)   { handleSmoke(evt, 47) }
def smokeHandler48(evt)   { handleSmoke(evt, 48) }
def smokeHandler49(evt)   { handleSmoke(evt, 49) }

private void handleSmoke(evt, int idx) {
    debugLog "Smoke slot ${idx} (${evt.displayName}): ${evt.value}"
    if (!settings.indicatorDevice) { infoLog "[SensorMonitor] WARN — No Indicator device configured"; return }
    if (evt.value == "detected") {
        settings.indicatorDevice.setMotionActive(idx)
    } else {
        settings.indicatorDevice.setMotionInactive(idx)
    }
}

// ── Sync ─────────────────────────────────────────────────────────────────────

def syncAllSensors() {
    infoLog "[SensorMonitor] Syncing all sensor states → display"

    (1..maxSlots()).each { idx ->
        def dev  = settings["sensor${idx}"]
        String t = settings["sensorType${idx}"] ?: "motion"

        if (!dev || t == "none") {
            settings.indicatorDevice?.setSlotEmpty(idx)
        } else if (t == "contact") {
            String st = dev.currentValue("contact") ?: "closed"
            debugLog "Sync slot ${idx} contact (${dev.displayName}) = ${st}"
            if (st == "open") {
                settings.indicatorDevice.setMotionActive(idx)
            } else {
                settings.indicatorDevice.setMotionInactive(idx)
            }
        } else if (t == "water") {
            String st = dev.currentValue("water") ?: "dry"
            debugLog "Sync slot ${idx} water (${dev.displayName}) = ${st}"
            if (st == "wet") {
                settings.indicatorDevice.setMotionActive(idx)
            } else {
                settings.indicatorDevice.setMotionInactive(idx)
            }
        } else if (t == "smoke") {
            String st = dev.currentValue("smoke") ?: "clear"
            debugLog "Sync slot ${idx} smoke (${dev.displayName}) = ${st}"
            if (st == "detected") {
                settings.indicatorDevice.setMotionActive(idx)
            } else {
                settings.indicatorDevice.setMotionInactive(idx)
            }
        } else {
            String st = dev.currentValue("motion") ?: "inactive"
            debugLog "Sync slot ${idx} motion (${dev.displayName}) = ${st}"
            if (st == "active") {
                settings.indicatorDevice.setMotionActive(idx)
            } else {
                settings.indicatorDevice.setMotionInactive(idx)
            }
        }
    }
}

// ── Display Reboot Handler ────────────────────────────────────────────────────

def displayRebootedHandler(evt) {
    infoLog "[SensorMonitor] SenseCAP rebooted — pushing grid, types, labels and syncing sensor states"
    if (settings.indicatorDevice) {
        settings.indicatorDevice.setGridLayout(settings.gridLayout ?: "2x2")
    }
    // Layout was already pushed by driver on reboot — just resync labels/types/states
    runIn(2, pushLabelsAndTypes)
    runIn(5, syncAllSensors)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private int maxSlots() {
    switch (settings.gridLayout) {
        case "1x1": return 1
        case "7x7": return 49
        case "6x6": return 36
        case "5x5": return 25
        case "4x4": return 16
        case "3x3": return 9
        default:    return 4
    }
}



/**
 * stripEmoji — removes emoji and other non-printable/non-ASCII characters
 * that would render as missing glyphs on the openHASP display.
 * Keeps ASCII printable characters (0x20-0x7E) only.
 */
private String stripEmoji(String text) {
    if (!text) return ""
    // Remove emoji and non-ASCII — keep only printable ASCII 0x20-0x7E
    return text.replaceAll(/[^\x20-\x7E]/, "").replaceAll(/\s+/, " ").trim()
}

/**
 * wrapLabel — word-wraps a plain label string to maxChars per line.
 * No icon is prepended here; icons are sent separately to the overlay object.
 */
private String wrapLabel(String text, int maxChars) {
    if (text.contains("\n")) {
        return text.split("\n").collect { wrapLabel(it, maxChars) }.join("\n")
    }
    if (text.length() <= maxChars) return text
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String current = ""
    words.each { word ->
        if (current.isEmpty()) {
            current = word
        } else if ((current + " " + word).length() <= maxChars) {
            current += " " + word
        } else {
            lines << current
            current = word
        }
    }
    if (current) lines << current
    return lines.join("\n")
}

private int subscribedCount() {
    (1..49).count { settings["sensor${it}"] != null && (settings["sensorType${it}"] ?: "none") != "none" }
}

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}

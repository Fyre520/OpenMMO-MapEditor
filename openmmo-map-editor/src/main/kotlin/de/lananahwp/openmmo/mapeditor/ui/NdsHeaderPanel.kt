package de.lananahwp.openmmo.mapeditor.ui

import de.lananahwp.openmmo.mapeditor.model.NdsMap
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/** Edits the header fields of a Gen 4 map. */
class NdsHeaderPanel(private val onApply: (NdsMap) -> Unit) : JPanel(BorderLayout()) {

  private var map: NdsMap? = null

  private val weather = JComboBox(WEATHERS.toTypedArray())
  private val mapType = JComboBox(MAP_TYPES.toTypedArray())
  private val matrixId = JSpinner(SpinnerNumberModel(0, 0, 0x7FFF, 1))
  private val areaDataBank = JSpinner(SpinnerNumberModel(0, 0, 0xFFFF, 1))
  private val moveModelBank = JSpinner(SpinnerNumberModel(15, 0, 0xFF, 1))
  private val worldMapX = JSpinner(SpinnerNumberModel(0, -512, 512, 1))
  private val worldMapY = JSpinner(SpinnerNumberModel(0, -512, 512, 1))
  private val cameraType = JSpinner(SpinnerNumberModel(0, 0, 0xFFFF, 1))
  private val dayMusic = JTextField(24)
  private val nightMusic = JTextField(24)
  private val mapsec = JTextField(24)
  private val region = JTextField(24)
  private val battleBg = JTextField(24)
  private val followMode = JTextField(24)
  private val bike = JCheckBox("Bike")
  private val running = JCheckBox("Running")
  private val escapeRope = JCheckBox("Escape rope")
  private val fly = JCheckBox("Fly")
  private val outgoing = JCheckBox("Outgoing calls")
  private val incoming = JCheckBox("Incoming calls")
  private val radio = JCheckBox("Radio signal")

  init {
    val form = JPanel(GridLayout(0, 2, 6, 4))
    fun row(label: String, comp: java.awt.Component) {
      form.add(JLabel(label))
      form.add(comp)
    }
    row("Weather", weather)
    row("Map type", mapType)
    row("Matrix id", matrixId)
    row("Area data bank", areaDataBank)
    row("Move model bank", moveModelBank)
    row("World map X", worldMapX)
    row("World map Y", worldMapY)
    row("Camera type", cameraType)
    row("Day music", dayMusic)
    row("Night music", nightMusic)
    row("Map section", mapsec)
    row("Region", region)
    row("Battle background", battleBg)
    row("Follow mode", followMode)

    val flags = JPanel(GridLayout(0, 2, 4, 2))
    flags.add(bike); flags.add(running)
    flags.add(escapeRope); flags.add(fly)
    flags.add(outgoing); flags.add(incoming)
    flags.add(radio)
    form.add(JLabel("Flags"))
    form.add(flags)

    val apply = JButton("Apply Header")
    apply.preferredSize = Dimension(140, 28)
    apply.addActionListener { _: ActionEvent -> applyHeader() }
    val south = JPanel(BorderLayout())
    south.add(apply, BorderLayout.EAST)

    add(JScrollPane(form), BorderLayout.CENTER)
    add(south, BorderLayout.SOUTH)
  }

  fun setMap(map: NdsMap) {
    this.map = map
    val h = map.header
    weather.selectedItem = WEATHERS.getOrElse(h.weather) { WEATHERS[0] }
    mapType.selectedItem = MAP_TYPES.getOrElse(h.mapType) { MAP_TYPES[0] }
    matrixId.value = h.matrixId
    areaDataBank.value = h.areaDataBank
    moveModelBank.value = h.moveModelBank
    worldMapX.value = h.worldMapX
    worldMapY.value = h.worldMapY
    cameraType.value = h.cameraType
    dayMusic.text = h.dayMusicId
    nightMusic.text = h.nightMusicId
    mapsec.text = h.mapsec
    region.text = h.regionNo
    battleBg.text = h.battleBg
    followMode.text = h.followMode
    bike.isSelected = h.bikeAllowed
    running.isSelected = h.runningAllowed
    escapeRope.isSelected = h.escapeRopeAllowed
    fly.isSelected = h.flyAllowed
    outgoing.isSelected = h.outgoingCalls
    incoming.isSelected = h.incomingCalls
    radio.isSelected = h.radioSignal
  }

  private fun applyHeader() {
    val map = this.map ?: return
    val h = map.header
    h.weather = weather.selectedIndex.coerceAtLeast(0)
    h.mapType = mapType.selectedIndex.coerceAtLeast(0)
    h.matrixId = (matrixId.value as Number).toInt()
    h.areaDataBank = (areaDataBank.value as Number).toInt()
    h.moveModelBank = (moveModelBank.value as Number).toInt()
    h.worldMapX = (worldMapX.value as Number).toInt()
    h.worldMapY = (worldMapY.value as Number).toInt()
    h.cameraType = (cameraType.value as Number).toInt()
    h.dayMusicId = dayMusic.text.trim()
    h.nightMusicId = nightMusic.text.trim()
    h.mapsec = mapsec.text.trim()
    h.regionNo = region.text.trim()
    h.battleBg = battleBg.text.trim()
    h.followMode = followMode.text.trim()
    h.bikeAllowed = bike.isSelected
    h.runningAllowed = running.isSelected
    h.escapeRopeAllowed = escapeRope.isSelected
    h.flyAllowed = fly.isSelected
    h.outgoingCalls = outgoing.isSelected
    h.incomingCalls = incoming.isSelected
    h.radioSignal = radio.isSelected
    onApply(map)
  }

  companion object {
    val WEATHERS =
        listOf(
            "Clear", "Rain light", "Rain", "Heavy rain", "Snow light", "Snow", "Snow heavy",
            "Sandstorm", "Hail", "Fog", "Deep fog", "Dark flash", "Dust", "Rain storm",
        )
    val MAP_TYPES =
        listOf("Invalid", "City/Town", "Route", "Cave", "Interior", "Poké Center", "Underground")
  }
}

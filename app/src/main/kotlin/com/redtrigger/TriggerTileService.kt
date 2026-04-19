package com.redtrigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class TriggerTileService : TileService() {
    private var active = false
    private var prerequisitesMet = false

    override fun onStartListening() {
        active = TriggerManager.isTriggersEnabled(this)
        prerequisitesMet = TriggerManager.arePrerequisitesMet(this)
        sync()
    }

    override fun onClick() {
        prerequisitesMet = TriggerManager.arePrerequisitesMet(this)
        if (!prerequisitesMet) {
            sync()
            return
        }

        if (active) {
            TriggerManager.disableTriggers(this)
        } else {
            TriggerManager.enableTriggers(this)
        }
        active = TriggerManager.isTriggersEnabled(this)

        sync()
    }


    private fun sync() {
        qsTile?.apply {
            state =
                if (prerequisitesMet)
                    if (active) Tile.STATE_ACTIVE
                    else Tile.STATE_INACTIVE
                else Tile.STATE_UNAVAILABLE
            label = "RedTrigger"
            updateTile()
        }
    }
}

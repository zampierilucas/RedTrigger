package com.redtrigger;

interface ITriggerCallback {
    /** Called when a trigger event is detected. trigger: 0=LEFT, 1=RIGHT. isDown: true=pressed */
    void onTriggerEvent(int trigger, boolean isDown);
    /** Called for any EV_KEY event from the device (for debug logging). keyName e.g. "KEY_F7" */
    void onRawKeyEvent(String keyName, boolean isDown);
    /** Called to report debug messages from the Shizuku process back to the app */
    void onDebugMessage(String tag, String message);
}
